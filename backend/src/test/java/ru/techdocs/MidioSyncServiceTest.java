package ru.techdocs;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.techdocs.midio.MidioClient;
import ru.techdocs.midio.MidioClient.ExternalWork;
import ru.techdocs.midio.MidioEquipmentMatcher;
import ru.techdocs.midio.MidioEquipmentMatcher.ExternalEquipment;
import ru.techdocs.midio.MidioSyncService;
import ru.techdocs.uniqueequipment.PlannedWork;
import ru.techdocs.uniqueequipment.PlannedWorkRepository;
import ru.techdocs.uniqueequipment.UniqueEquipment;
import ru.techdocs.uniqueequipment.UniqueEquipmentRepository;
import ru.techdocs.uniqueequipment.UniqueEquipmentService;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Перенос регламентов Midio в реестр: что привязываем, что откладываем. */
class MidioSyncServiceTest {

    private final UniqueEquipmentRepository equipmentRepository = mock(UniqueEquipmentRepository.class);
    private final PlannedWorkRepository plannedWorks = mock(PlannedWorkRepository.class);
    private final ru.techdocs.midio.MidioSyncReportRepository reports =
            mock(ru.techdocs.midio.MidioSyncReportRepository.class);

    private UniqueEquipment ue(Long id, String name, String model, String system) {
        UniqueEquipment u = new UniqueEquipment();
        u.setId(id);
        u.setName(name);
        u.setModel(model);
        u.setManufacturer("Рубеж");
        u.setNormKey(UniqueEquipmentService.normKey(name, model, "Рубеж", system));
        u.setEquipKey(UniqueEquipmentService.equipKey(name, model, "Рубеж"));
        return u;
    }

    private MidioSyncService service(List<UniqueEquipment> registry,
                                     List<ExternalEquipment> external, List<ExternalWork> works) {
        when(equipmentRepository.findAll()).thenReturn(registry);
        registry.forEach(u -> when(equipmentRepository.findById(u.getId())).thenReturn(Optional.of(u)));
        MidioClient client = new MidioClient() {
            @Override public boolean isConfigured() { return true; }
            @Override public List<ExternalEquipment> equipment() { return external; }
            @Override public List<ExternalWork> works() { return works; }
        };
        return new MidioSyncService(client, new MidioEquipmentMatcher(equipmentRepository),
                equipmentRepository, plannedWorks, reports);
    }

    @SuppressWarnings("unchecked")
    private List<PlannedWork> savedWorks() {
        ArgumentCaptor<List<PlannedWork>> captor = ArgumentCaptor.forClass(List.class);
        verify(plannedWorks).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void matchedEquipmentGetsLinkedAndItsWorksImported() {
        UniqueEquipment target = ue(1L, "Извещатель пожарный дымовой", "ИП 212-64", "АПС");
        var result = service(List.of(target),
                List.of(new ExternalEquipment("mid-5", "Извещатель пожарный дымовой", "ИП 212-64", "Рубеж", "АПС")),
                List.of(new ExternalWork("w-1", "mid-5", "Технический осмотр", "осмотр", "Ежемесячно",
                                new java.math.BigDecimal("12"), null, true, true, "План"),
                        new ExternalWork("w-2", "mid-5", "Проверка работоспособности", "проверка",
                                "раз в 6 мес.", new java.math.BigDecimal("2"),
                                "Продувка оптической системы", false, true, "План"))).sync();

        assertThat(result.linkedEquipment()).isEqualTo(1);
        assertThat(result.importedWorks()).isEqualTo(2);
        assertThat(target.getMidioId()).isEqualTo("mid-5");
        assertThat(target.getMidioSyncedAt()).isNotNull();

        List<PlannedWork> works = savedWorks();
        assertThat(works).allSatisfy(w -> assertThat(w.getSource()).isEqualTo(PlannedWork.SOURCE_MIDIO));
        assertThat(works.getFirst().getPeriodicityPerYear()).isEqualByComparingTo("12");
        assertThat(works.getFirst().getExternalId()).isEqualTo("w-1");
        assertThat(works.getFirst().getSourceLabel()).isEqualTo("Midio");
        assertThat(works.getFirst().getSourceNote())
                .contains("План").contains("Извещатель пожарный дымовой");
        assertThat(works.get(1).getPeriodicityPerYear()).isEqualByComparingTo("2");
    }

    @Test
    void repeatedSyncReplacesOnlyMidioWorks() {
        UniqueEquipment target = ue(1L, "Извещатель пожарный дымовой", "ИП 212-64", "АПС");
        service(List.of(target),
                List.of(new ExternalEquipment("mid-5", "Извещатель пожарный дымовой", "ИП 212-64", "Рубеж", "АПС")),
                List.of(new ExternalWork("w-1", "mid-5", "Технический осмотр", "осмотр", "Ежемесячно",
                                new java.math.BigDecimal("12"), null, true, true, "План")))
                .sync();

        // паспортные и ручные работы синхронизация не трогает
        verify(plannedWorks).deleteByUniqueEquipmentIdAndSource(1L, PlannedWork.SOURCE_MIDIO);
        verify(plannedWorks, never()).deleteByUniqueEquipmentIdAndSource(1L, PlannedWork.SOURCE_PASSPORT);
        verify(plannedWorks, never()).deleteByUniqueEquipmentIdAndSource(1L, PlannedWork.SOURCE_MANUAL);
    }

    @Test
    void ambiguousEquipmentWaitsForEngineerAndImportsNothing() {
        UniqueEquipment rm1 = ue(1L, "Модуль релейный", "РМ-1К", "АПС");
        UniqueEquipment rm4 = ue(2L, "Модуль релейный", "РМ-4К", "АПС");
        var result = service(List.of(rm1, rm4),
                List.of(new ExternalEquipment("mid-9", "Модуль релейный", "РМ-2К", "Рубеж", "АПС")),
                List.of(new ExternalWork("w-9", "mid-9", "Техническое обслуживание", "ТО", "Ежемесячно",
                                new java.math.BigDecimal("12"), null, true, true, "План")))
                .sync();

        assertThat(result.linkedEquipment()).isZero();
        assertThat(result.importedWorks()).isZero();
        assertThat(result.skippedWorks()).isEqualTo(1);
        assertThat(result.pending()).hasSize(1);
        assertThat(result.pending().getFirst().candidates()).hasSize(2);
        // состав работ виден прямо в отчёте — в Midio за ним ходить не нужно
        assertThat(result.pending().getFirst().works()).hasSize(1);
        assertThat(result.pending().getFirst().works().getFirst().name())
                .isEqualTo("Техническое обслуживание");
        assertThat(result.pending().getFirst().works().getFirst().mandatory()).isTrue();
        verify(plannedWorks, never()).saveAll(any());
        assertThat(rm1.getMidioId()).isNull();
        assertThat(rm4.getMidioId()).isNull();
    }

    @Test
    void equipmentMissingFromRegistryIsReportedSeparately() {
        UniqueEquipment target = ue(1L, "Извещатель пожарный дымовой", "ИП 212-64", "АПС");
        var result = service(List.of(target),
                List.of(new ExternalEquipment("mid-77", "Насос дренажный", "GRUNDFOS UNILIFT", "Grundfos", "ВК"),
                        new ExternalEquipment("mid-78", "Щит без работ", "ЩУ-1", "Прочее", "ВК")),
                List.of(new ExternalWork("w-77", "mid-77", "Техническое обслуживание", "ТО", "Ежемесячно",
                        new java.math.BigDecimal("12"), null, true, true, "План"))).sync();

        // это не «неоднозначно», а «нет в реестре» — и лечится синхронизацией с объектами
        assertThat(result.pending()).isEmpty();
        // mid-78 без работ в отчёт не попадает: подтверждать нечего
        assertThat(result.unknown()).hasSize(1);
        assertThat(result.unknown().getFirst().externalId()).isEqualTo("mid-77");
        assertThat(result.unknown().getFirst().workCount()).isEqualTo(1);
        assertThat(result.unknown().getFirst().reason()).contains("нет такого оборудования");
    }

    @Test
    void manualLinkMovesTheConnectionFromPreviousRecord() {
        UniqueEquipment wrong = ue(1L, "Модуль релейный", "РМ-1К", "АПС");
        wrong.setMidioId("mid-9");
        UniqueEquipment right = ue(2L, "Модуль релейный", "РМ-4К", "АПС");
        MidioSyncService service = service(List.of(wrong, right), List.of(), List.of());

        service.link(List.of(2L), "mid-9");

        // запись вне выбора теряет связь: выбор — полный список для этой карточки
        assertThat(wrong.getMidioId()).isNull();
        assertThat(right.getMidioId()).isEqualTo("mid-9");
    }

    @Test
    void oneMidioCardMayLinkToSeveralRegistryRecords() {
        UniqueEquipment first = ue(1L, "Датчик температуры канальный", "HTF-PT1000", "Вентиляция");
        UniqueEquipment second = ue(2L, "Датчик температуры (канальный)", "HTF-PT1000", "ИТП");
        MidioSyncService service = service(List.of(first, second), List.of(), List.of());

        service.link(List.of(1L, 2L), "mid-33");

        assertThat(first.getMidioId()).isEqualTo("mid-33");
        assertThat(second.getMidioId()).isEqualTo("mid-33");
    }

    @Test
    void syncImportsWorksToEveryLinkedRecord() {
        UniqueEquipment first = ue(1L, "Датчик температуры канальный", "HTF-PT1000", "Вентиляция");
        first.setMidioId("mid-33");
        UniqueEquipment second = ue(2L, "Датчик температуры (канальный)", "HTF-PT1000", "ИТП");
        second.setMidioId("mid-33");
        var result = service(List.of(first, second),
                List.of(new ExternalEquipment("mid-33", "Датчик температуры", "HTF-PT1000", "SHUFT", null)),
                List.of(new ExternalWork("w-1", "mid-33", "ТО HTF-PT1000", "ТО", "раз в 6 мес.",
                        new java.math.BigDecimal("2"), null, true, true, "План ТО датчиков")))
                .sync();

        assertThat(result.linkedEquipment()).isEqualTo(2);
        assertThat(result.importedWorks()).isEqualTo(2);
        verify(plannedWorks).deleteByUniqueEquipmentIdAndSource(1L, PlannedWork.SOURCE_MIDIO);
        verify(plannedWorks).deleteByUniqueEquipmentIdAndSource(2L, PlannedWork.SOURCE_MIDIO);
    }

    @Test
    void dedicatedPlanWorksBeatZonePlanWorks() {
        UniqueEquipment target = ue(1L, "Извещатель пожарный дымовой", "ИП 212-64", "АПС");
        var result = service(List.of(target),
                List.of(new ExternalEquipment("mid-5", "Извещатель пожарный дымовой", "ИП 212-64", "Рубеж", "АПС")),
                List.of(
                        // работа из зонного плана (несколько изделий)
                        new ExternalWork("w-zone", "mid-5", "ТО оборудования пожарной зоны", "ТО",
                                "Ежемесячно", new java.math.BigDecimal("12"), null, true, false, "Зонный план"),
                        // своя работа из плана на одно изделие
                        new ExternalWork("w-own", "mid-5", "Технический осмотр ИП 212-64", "осмотр",
                                "раз в 6 мес.", new java.math.BigDecimal("2"), null, true, true, "План")))
                .sync();

        // своя работа важнее зонной: зонная не переносится, иначе задвоение
        assertThat(result.importedWorks()).isEqualTo(1);
        assertThat(savedWorks()).hasSize(1);
        assertThat(savedWorks().getFirst().getExternalId()).isEqualTo("w-own");
    }

    @Test
    void zonePlanWorksAreUsedWhenNoDedicatedOnesExist() {
        UniqueEquipment target = ue(1L, "Извещатель пожарный дымовой", "ИП 212-64", "АПС");
        var result = service(List.of(target),
                List.of(new ExternalEquipment("mid-5", "Извещатель пожарный дымовой", "ИП 212-64", "Рубеж", "АПС")),
                List.of(new ExternalWork("w-zone", "mid-5", "ТО оборудования пожарной зоны", "ТО",
                        "Ежемесячно", new java.math.BigDecimal("12"), null, true, false, "Зонный план")))
                .sync();

        assertThat(result.importedWorks()).isEqualTo(1);
        assertThat(savedWorks().getFirst().getExternalId()).isEqualTo("w-zone");
    }

    @Test
    void reportSurvivesAndConfirmationCrossesOutThePosition() throws Exception {
        UniqueEquipment rm1 = ue(1L, "Модуль релейный", "РМ-1К", "АПС");
        UniqueEquipment rm4 = ue(2L, "Модуль релейный", "РМ-4К", "АПС");
        MidioSyncService service = service(List.of(rm1, rm4),
                List.of(new ExternalEquipment("mid-9", "Модуль релейный", "РМ-2К", "Рубеж", "АПС")),
                List.of(new ExternalWork("w-9", "mid-9", "Техническое обслуживание", "ТО", "Ежемесячно",
                        new java.math.BigDecimal("12"), null, true, true, "План")));

        // sync сохраняет отчёт; репозиторий-мок возвращает то, что сохранили
        ArgumentCaptor<ru.techdocs.midio.MidioSyncReport> saved =
                ArgumentCaptor.forClass(ru.techdocs.midio.MidioSyncReport.class);
        service.sync();
        verify(reports).save(saved.capture());
        when(reports.findTopByOrderByIdDesc()).thenReturn(Optional.of(saved.getValue()));

        assertThat(service.lastReport().pending()).hasSize(1);

        service.link(List.of(2L), "mid-9");

        verify(reports, org.mockito.Mockito.times(2)).save(saved.capture());
        when(reports.findTopByOrderByIdDesc()).thenReturn(Optional.of(saved.getValue()));
        assertThat(service.lastReport().pending()).isEmpty();
    }

    @Test
    void syncWithoutCredentialsFailsLoudly() {
        MidioClient offline = new MidioClient() {
            @Override public boolean isConfigured() { return false; }
            @Override public List<ExternalEquipment> equipment() { return List.of(); }
            @Override public List<ExternalWork> works() { return List.of(); }
        };
        MidioSyncService service = new MidioSyncService(offline,
                new MidioEquipmentMatcher(equipmentRepository), equipmentRepository, plannedWorks, reports);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                ru.techdocs.common.BadRequestException.class, service::sync).getMessage())
                .contains("не настроена");
    }
}
