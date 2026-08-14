package ru.techdocs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.estimate.EstimateRateDecision;
import ru.techdocs.estimate.EstimateRateDecisionRepository;
import ru.techdocs.estimate.ReferenceEstimateImportService;
import ru.techdocs.pkm.PkmParser;
import ru.techdocs.uniqueequipment.UniqueEquipment;
import ru.techdocs.uniqueequipment.UniqueEquipmentRepository;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Разбор реальных файлов пользователя: регламент ПКМ (АПС) и эталон АПС/СОУЭ. */
class RealFileParsingTest extends IntegrationTestBase {

    @Autowired PkmParser pkmParser;
    @Autowired ReferenceEstimateImportService importService;
    @Autowired EstimateRateDecisionRepository decisionRepository;
    @Autowired UniqueEquipmentRepository uniqueRepository;

    private byte[] sample(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/samples/" + name)) {
            return in.readAllBytes();
        }
    }

    /** Регламент АПС: шапка таблицы вынесена в отдельную таблицу — работы всё равно читаются. */
    @Test
    void parsesPkmWithHeaderInSeparateTable() throws Exception {
        try (InputStream in = new java.io.ByteArrayInputStream(sample("pkm-aps.docx"))) {
            var result = pkmParser.parse("aps.docx", in);
            assertThat(result.operations()).hasSize(26);
            assertThat(result.operations()).allSatisfy(op ->
                    assertThat(op.getOperationName()).isNotBlank());
            // периодичность распознана у всех работ, где она указана в регламенте
            // (включая «Один раз в шесть месяцев» и опечатку «Один разв шесть месяцев»)
            long withoutPeriodicity = result.operations().stream()
                    .filter(op -> op.getPeriodicityPerYear() == null).count();
            assertThat(withoutPeriodicity).isEqualTo(1);   // одна работа без периодичности в документе
        }
    }

    /** Эталон АПС/СОУЭ без строк-разделов: система берётся из листа и мероприятия, не «без системы». */
    @Test
    @Transactional
    void referenceWithoutSectionsStillGetsSystem() throws Exception {
        var file = new MockMultipartFile("file", "aps.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", sample("reference-aps.xlsx"));
        var res = importService.importXlsx(file);
        assertThat(res.imported()).isGreaterThan(10);

        Map<Long, UniqueEquipment> byId = new HashMap<>();
        uniqueRepository.findAll().forEach(ue -> byId.put(ue.getId(), ue));
        var decisions = decisionRepository.findAll();
        assertThat(decisions).isNotEmpty();

        // ни одно решение не осталось без системы
        assertThat(decisions).allSatisfy(d -> {
            UniqueEquipment ue = byId.get(d.getUniqueEquipmentId());
            assertThat(ue).isNotNull();
            assertThat(ue.getSystemType()).isNotBlank();
        });
        // оповещатели ушли в СОУЭ, остальное — в АПС (система листа)
        var systems = decisions.stream()
                .map(d -> byId.get(d.getUniqueEquipmentId()).getSystemType())
                .distinct().toList();
        assertThat(systems).contains("апс", "соуэ");
        assertThat(decisions).allSatisfy(d ->
                assertThat(d.getSource()).isEqualTo(EstimateRateDecision.SOURCE_REFERENCE));
    }

    /**
     * У извещателя дымового в эталоне ЧЕТЫРЕ работы: осмотр и ТО 1/2/3 с разными
     * расценками (106-1, 106-2, 106-3). Все должны попасть в память, а не схлопнуться
     * в одно решение по категории «то».
     */
    @Test
    @Transactional
    void allFourWorksOfSmokeDetectorAreKept() throws Exception {
        var file = new MockMultipartFile("file", "aps.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", sample("reference-aps.xlsx"));
        importService.importXlsx(file);

        UniqueEquipment detector = uniqueRepository.findAll().stream()
                .filter(ue -> ue.getName() != null && ue.getName().toLowerCase().contains("дымовой адресно"))
                .findFirst().orElseThrow();
        var works = decisionRepository.findByUniqueEquipmentIdOrderByOperationKey(detector.getId());
        assertThat(works).extracting(EstimateRateDecision::getRateCode)
                .containsExactlyInAnyOrder("1-2201-35-1/1", "22-2203-106-1/1",
                        "22-2203-106-2/1", "22-2203-106-3/1");
    }
}
