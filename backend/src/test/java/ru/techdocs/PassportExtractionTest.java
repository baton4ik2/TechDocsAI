package ru.techdocs;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.techdocs.ai.AiClient;
import ru.techdocs.config.AppProperties;
import ru.techdocs.processing.OcrExtractor;
import ru.techdocs.processing.PageImageRenderer;
import ru.techdocs.processing.PageText;
import ru.techdocs.processing.TextExtractor;
import ru.techdocs.storage.FileStorage;
import ru.techdocs.uniqueequipment.PlannedWork;
import ru.techdocs.uniqueequipment.PlannedWorkRepository;
import ru.techdocs.uniqueequipment.UniqueEquipment;
import ru.techdocs.uniqueequipment.UniqueEquipmentPassportService;
import ru.techdocs.uniqueequipment.UniqueEquipmentRepository;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Разбор паспорта: выбор модели, цитаты и пометка непроверяемых работ. */
class PassportExtractionTest {

    private static final String PASSPORT_TEXT = """
            6. Техническое обслуживание
            Технический осмотр извещателя проводится ежемесячно.
            Проверка работоспособности — не реже одного раза в шесть месяцев.
            """;

    private final UniqueEquipmentRepository repository = mock(UniqueEquipmentRepository.class);
    private final PlannedWorkRepository plannedWorks = mock(PlannedWorkRepository.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final TextExtractor textExtractor = mock(TextExtractor.class);
    private final OcrExtractor ocrExtractor = mock(OcrExtractor.class);
    private final PageImageRenderer renderer = mock(PageImageRenderer.class);
    private final AiClient aiClient = mock(AiClient.class);

    private UniqueEquipmentPassportService service(String models) {
        AppProperties props = new AppProperties(null, null,
                new AppProperties.Ai(null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, "cheap/model", models),
                null, null, null);
        return new UniqueEquipmentPassportService(repository, plannedWorks, fileStorage,
                textExtractor, ocrExtractor, renderer, aiClient, props);
    }

    private UniqueEquipment equipment() {
        UniqueEquipment ue = new UniqueEquipment();
        ue.setId(1L);
        ue.setPassportFilename("passport.pdf");
        ue.setPassportStoragePath("passports/1/passport.pdf");
        return ue;
    }

    private void givenTextPassport() throws Exception {
        when(fileStorage.load(any())).thenReturn(new ByteArrayInputStream("pdf".getBytes()));
        when(textExtractor.extract(any(), any(), any())).thenReturn(
                new TextExtractor.ExtractionResult(List.of(new PageText(4, PASSPORT_TEXT)), false));
        when(aiClient.hasPassportModel()).thenReturn(true);
        when(aiClient.defaultPassportModel()).thenReturn("cheap/model");
    }

    private List<PlannedWork> savedWorks() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlannedWork>> captor = ArgumentCaptor.forClass(List.class);
        verify(plannedWorks).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void quoteFromPassportIsVerifiedAndPagedFromText() throws Exception {
        givenTextPassport();
        UniqueEquipment ue = equipment();
        when(repository.findById(1L)).thenReturn(Optional.of(ue));
        when(aiClient.completePassport(any(), any(), eq("cheap/model"))).thenReturn("""
                [{"тип":"осмотр","наименование":"Технический осмотр","периодичность":"Ежемесячно",
                  "цитата":"Технический осмотр извещателя проводится ежемесячно","страница":1}]
                """);

        service(null).process(1L, null);

        List<PlannedWork> works = savedWorks();
        assertThat(works).hasSize(1);
        PlannedWork w = works.getFirst();
        assertThat(w.getQuoteVerified()).isTrue();
        // страницу берём из текста, а не из ответа модели: она ошиблась и сказала «1»
        assertThat(w.getSourcePage()).isEqualTo(4);
        assertThat(w.getSourceLabel()).isEqualTo("Паспорт, с. 4");
        assertThat(w.getPeriodicityPerYear()).isEqualByComparingTo("12");
        assertThat(ue.getPassportStatus()).isEqualTo(UniqueEquipment.PASSPORT_READY);
        assertThat(ue.getPassportError()).isNull();
    }

    @Test
    void inventedWorkIsSavedButFlagged() throws Exception {
        givenTextPassport();
        UniqueEquipment ue = equipment();
        when(repository.findById(1L)).thenReturn(Optional.of(ue));
        when(aiClient.completePassport(any(), any(), any())).thenReturn("""
                [{"тип":"ТО","наименование":"Техническое обслуживание","периодичность":"Ежеквартально",
                  "цитата":"Техническое обслуживание проводится один раз в квартал","страница":6}]
                """);

        service(null).process(1L, null);

        PlannedWork w = savedWorks().getFirst();
        assertThat(w.getQuoteVerified()).isFalse();
        // страницы в тексте не нашли — остаётся та, что назвала модель
        assertThat(w.getSourcePage()).isEqualTo(6);
        assertThat(ue.getPassportError()).contains("не подтверждены цитатой");
    }

    @Test
    void unlistedModelIsRejected() {
        UniqueEquipment ue = equipment();
        when(repository.findById(1L)).thenReturn(Optional.of(ue));
        when(aiClient.defaultPassportModel()).thenReturn("cheap/model");

        service("cheap/model,other/model").process(1L, "expensive/model");

        assertThat(ue.getPassportStatus()).isEqualTo(UniqueEquipment.PASSPORT_ERROR);
        assertThat(ue.getPassportError()).contains("не разрешена");
        verifyNoInteractions(fileStorage);
    }

    @Test
    void scanGoesThroughOcrWhenAvailable() throws Exception {
        when(fileStorage.load(any())).thenReturn(new ByteArrayInputStream("pdf".getBytes()));
        when(textExtractor.extract(any(), any(), any())).thenReturn(
                new TextExtractor.ExtractionResult(List.of(new PageText(1, "")), true));
        when(ocrExtractor.isAvailable()).thenReturn(true);
        when(ocrExtractor.extract(any(), any())).thenReturn(List.of(new PageText(2, PASSPORT_TEXT)));
        when(aiClient.hasPassportModel()).thenReturn(true);
        when(aiClient.defaultPassportModel()).thenReturn("cheap/model");
        when(aiClient.completePassport(any(), any(), any())).thenReturn("""
                [{"наименование":"Технический осмотр","периодичность":"Ежемесячно",
                  "цитата":"Технический осмотр извещателя проводится ежемесячно"}]
                """);
        UniqueEquipment ue = equipment();
        when(repository.findById(1L)).thenReturn(Optional.of(ue));

        service(null).process(1L, null);

        assertThat(ue.getPassportMode()).isEqualTo(UniqueEquipmentPassportService.MODE_OCR);
        assertThat(savedWorks().getFirst().getQuoteVerified()).isTrue();
        verify(aiClient, never()).completeVision(any(), any(), any());
    }

    @Test
    void scanWithoutOcrFallsBackToVision() throws Exception {
        when(fileStorage.load(any())).thenReturn(new ByteArrayInputStream("not a pdf".getBytes()));
        when(textExtractor.extract(any(), any(), any())).thenReturn(
                new TextExtractor.ExtractionResult(List.of(new PageText(1, "")), true));
        when(ocrExtractor.isAvailable()).thenReturn(false);
        when(aiClient.hasVisionModel()).thenReturn(true);
        when(renderer.renderPng(any(), any(), anyInt(), anyInt())).thenReturn(new byte[]{1, 2, 3});
        when(aiClient.completeVision(any(), any(), any())).thenReturn("""
                [{"наименование":"Технический осмотр","периодичность":"Ежемесячно",
                  "цитата":"Технический осмотр извещателя проводится ежемесячно"}]
                """);
        UniqueEquipment ue = equipment();
        ue.setPassportFilename("scan.png");
        when(repository.findById(1L)).thenReturn(Optional.of(ue));

        service(null).process(1L, null);

        assertThat(ue.getPassportMode()).isEqualTo(UniqueEquipmentPassportService.MODE_VISION);
        PlannedWork w = savedWorks().getFirst();
        // текста нет — сверять не с чем, зато страница известна точно
        assertThat(w.getQuoteVerified()).isNull();
        assertThat(w.getSourcePage()).isEqualTo(1);
    }
}
