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
                equipmentRepository, plannedWorks);
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
                                new java.math.BigDecimal("12"), null, true),
                        new ExternalWork("w-2", "mid-5", "Проверка работоспособности", "проверка",
                                "раз в 6 мес.", new java.math.BigDecimal("2"),
                                "Продувка оптической системы", false))).sync();

        assertThat(result.linkedEquipment()).isEqualTo(1);
        assertThat(result.importedWorks()).isEqualTo(2);
        assertThat(target.getMidioId()).isEqualTo("mid-5");
        assertThat(target.getMidioSyncedAt()).isNotNull();

        List<PlannedWork> works = savedWorks();
        assertThat(works).allSatisfy(w -> assertThat(w.getSource()).isEqualTo(PlannedWork.SOURCE_MIDIO));
        assertThat(works.getFirst().getPeriodicityPerYear()).isEqualByComparingTo("12");
        assertThat(works.getFirst().getExternalId()).isEqualTo("w-1");
        assertThat(works.getFirst().getSourceLabel()).isEqualTo("Midio");
        assertThat(works.get(1).getPeriodicityPerYear()).isEqualByComparingTo("2");
    }

    @Test
    void repeatedSyncReplacesOnlyMidioWorks() {
        UniqueEquipment target = ue(1L, "Извещатель пожарный дымовой", "ИП 212-64", "АПС");
        service(List.of(target),
                List.of(new ExternalEquipment("mid-5", "Извещатель пожарный дымовой", "ИП 212-64", "Рубеж", "АПС")),
                List.of(new ExternalWork("w-1", "mid-5", "Технический осмотр", "осмотр", "Ежемесячно",
                                new java.math.BigDecimal("12"), null, true)))
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
                                new java.math.BigDecimal("12"), null, true)))
                .sync();

        assertThat(result.linkedEquipment()).isZero();
        assertThat(result.importedWorks()).isZero();
        assertThat(result.skippedWorks()).isEqualTo(1);
        assertThat(result.pending()).hasSize(1);
        assertThat(result.pending().getFirst().candidates()).hasSize(2);
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
                        new java.math.BigDecimal("12"), null, true))).sync();

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

        service.link(2L, "mid-9");

        // две записи с одним идентификатором Midio задвоили бы работы
        assertThat(wrong.getMidioId()).isNull();
        assertThat(right.getMidioId()).isEqualTo("mid-9");
    }

    @Test
    void syncWithoutCredentialsFailsLoudly() {
        MidioClient offline = new MidioClient() {
            @Override public boolean isConfigured() { return false; }
            @Override public List<ExternalEquipment> equipment() { return List.of(); }
            @Override public List<ExternalWork> works() { return List.of(); }
        };
        MidioSyncService service = new MidioSyncService(offline,
                new MidioEquipmentMatcher(equipmentRepository), equipmentRepository, plannedWorks);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                ru.techdocs.common.BadRequestException.class, service::sync).getMessage())
                .contains("не настроена");
    }
}
