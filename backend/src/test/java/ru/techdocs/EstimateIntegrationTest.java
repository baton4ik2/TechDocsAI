package ru.techdocs;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.normative.NormativeRate;
import ru.techdocs.normative.NormativeRateRepository;
import ru.techdocs.normative.NormativeSourcebook;
import ru.techdocs.normative.NormativeSourcebookRepository;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
class EstimateIntegrationTest extends IntegrationTestBase {

    @Autowired NormativeSourcebookRepository sourcebookRepository;
    @Autowired NormativeRateRepository rateRepository;
    @Autowired ru.techdocs.estimate.EstimateReviewRepository reviewRepository;

    private long facility() throws Exception {
        String resp = mockMvc.perform(post("/api/facilities").header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Лицей 123\",\"systems\":[]}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    /** Расценка каталога, как строка №1 эталона (сервер СКУД). */
    private void seedRate() {
        NormativeSourcebook book = new NormativeSourcebook();
        book.setName("СН-2012 сборник 22");
        book.setStatus(NormativeSourcebook.STATUS_READY);
        book = sourcebookRepository.saveAndFlush(book);

        NormativeRate rate = new NormativeRate();
        rate.setSourcebookId(book.getId());
        rate.setCode("22-2203-113-1/1");
        rate.setName("Техническое обслуживание сервера СКУД - ежемесячное");
        rate.setUnit("1 шт.");
        rate.setLaborCost(new BigDecimal("362.26"));
        rate.setMachineCost(BigDecimal.ZERO);
        rate.setMachineLabor(BigDecimal.ZERO);
        rate.setMaterialCost(new BigDecimal("0.15"));
        rate.setLaborHours(new BigDecimal("0.52"));
        rateRepository.saveAndFlush(rate);
    }

    private long createEstimate(long facilityId) throws Exception {
        String resp = mockMvc.perform(post("/api/estimates?facilityId=" + facilityId)
                        .header("Authorization", bearer())
                        .contentType("application/json").content("{\"name\":\"Смета СКУД 2026\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("id").asLong();
    }

    @Test
    void addRowFillsPricesFromCatalogAndComputesMoney() throws Exception {
        seedRate();
        long est = createEstimate(facility());

        // добавляем строку: только шифр, количество и периодичность —
        // цены и измеритель должны подтянуться из каталога, J — из периодичности
        String rowResp = mockMvc.perform(post("/api/estimates/" + est + "/rows")
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"rateCode\":\"22-2203-113-1/1\",\"qty\":1,\"periodicity\":\"раз в 1 мес.\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode row = json.readTree(rowResp);
        // автозаполнение из каталога
        org.assertj.core.api.Assertions.assertThat(row.get("priceZp").asDouble()).isEqualTo(362.26);
        org.assertj.core.api.Assertions.assertThat(row.get("unitBasis").asDouble()).isEqualTo(1.0);
        org.assertj.core.api.Assertions.assertThat(row.get("opsPerYear").asDouble()).isEqualTo(12.0);
        org.assertj.core.api.Assertions.assertThat(row.get("rateName").asText()).contains("сервера СКУД");

        // расчёт строки и итогов сметы — до копейки как в эталоне
        mockMvc.perform(get("/api/estimates/" + est).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].calc.zp").value(4347.12))
                .andExpect(jsonPath("$.rows[0].calc.vat").value(1721.86))
                .andExpect(jsonPath("$.totals.vat").value(1721.86))
                .andExpect(jsonPath("$.totals.totalWithVat").value(9548.476));
    }

    @Test
    void updateCoefficientsRecomputesTotals() throws Exception {
        seedRate();
        long est = createEstimate(facility());
        mockMvc.perform(post("/api/estimates/" + est + "/rows").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"rateCode\":\"22-2203-113-1/1\",\"qty\":1,\"opsPerYear\":12}"))
                .andExpect(status().isOk());

        // обнуляем НДС → итог с НДС равен итогу без НДС
        mockMvc.perform(patch("/api/estimates/" + est).header("Authorization", bearer())
                        .contentType("application/json").content("{\"vat\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/estimates/" + est).header("Authorization", bearer()))
                .andExpect(jsonPath("$.totals.vat").value(0.00))
                .andExpect(jsonPath("$.totals.totalWithVat").value(7826.616));
    }

    @Test
    void exportReturnsXlsx() throws Exception {
        seedRate();
        long est = createEstimate(facility());
        mockMvc.perform(post("/api/estimates/" + est + "/rows").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"rateCode\":\"22-2203-113-1/1\",\"qty\":1,\"opsPerYear\":12}"))
                .andExpect(status().isOk());

        byte[] body = mockMvc.perform(get("/api/estimates/" + est + "/export").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("spreadsheetml")))
                .andReturn().getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(body.length).isGreaterThan(1000);
    }

    @Test
    void deleteRowAndEstimate() throws Exception {
        long est = createEstimate(facility());
        String rowResp = mockMvc.perform(post("/api/estimates/" + est + "/rows").header("Authorization", bearer())
                        .contentType("application/json").content("{\"operationName\":\"ТО\",\"qty\":1,\"opsPerYear\":1}"))
                .andReturn().getResponse().getContentAsString();
        long rowId = json.readTree(rowResp).get("id").asLong();

        mockMvc.perform(delete("/api/estimates/rows/" + rowId).header("Authorization", bearer()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/estimates/" + est).header("Authorization", bearer()))
                .andExpect(jsonPath("$.rows.length()").value(0));
        mockMvc.perform(delete("/api/estimates/" + est).header("Authorization", bearer()))
                .andExpect(status().isNoContent());
    }

    /**
     * Одно и то же оборудование с одной расценкой встречается в реестре несколько раз
     * (разные этажи, шлейфы). В смете это одна позиция — количества складываются.
     */
    @Test
    void mergesDuplicateRowsSummingQuantity() throws Exception {
        seedRate();
        long est = createEstimate(facility());
        // одна расценка, но РАЗНЫЕ модели извещателей — в смете это одна позиция
        String body = "{\"section\":\"АПС\",\"equipmentName\":\"Извещатель пожарный дымовой\","
                + "\"equipmentType\":\"%s\",\"rateCode\":\"22-2203-113-1/1\","
                + "\"periodicity\":\"раз в год\",\"qty\":%d}";
        String[] models = {"ИП 212-64", "ИП 212-45", "ИП 212-64"};
        int[] quantities = {40, 60, 16};
        for (int i = 0; i < models.length; i++) {
            mockMvc.perform(post("/api/estimates/" + est + "/rows").header("Authorization", bearer())
                            .contentType("application/json")
                            .content(String.format(body, models[i], quantities[i])))
                    .andExpect(status().isOk());
        }

        String groupsResp = mockMvc.perform(get("/api/estimates/" + est + "/duplicate-groups")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].totalQty").value(116))
                // видно, что сливаются разные модели по одной расценке
                .andExpect(jsonPath("$[0].equipmentNames.length()").value(2))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode ids = json.readTree(groupsResp).get(0).get("rowIds");

        mockMvc.perform(post("/api/estimates/" + est + "/merge-rows").header("Authorization", bearer())
                        .contentType("application/json").content("{\"rowIds\":" + ids + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qty").value(116))
                .andExpect(jsonPath("$.matchNote").value(org.hamcrest.Matchers.containsString("ИП 212-45")));

        mockMvc.perform(get("/api/estimates/" + est).header("Authorization", bearer()))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].row.position").value(1));
    }

    /** Строки с разными расценками объединять нельзя — количество ушло бы на чужую расценку. */
    @Test
    void refusesToMergeRowsWithDifferentRates() throws Exception {
        seedRate();
        long est = createEstimate(facility());
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (String code : new String[]{"22-2203-113-1/1", "22-2203-999-1/1"}) {
            String resp = mockMvc.perform(post("/api/estimates/" + est + "/rows").header("Authorization", bearer())
                            .contentType("application/json")
                            .content("{\"equipmentName\":\"Извещатель\",\"rateCode\":\"" + code + "\",\"qty\":5}"))
                    .andReturn().getResponse().getContentAsString();
            ids.add(json.readTree(resp).get("id").asLong());
        }

        mockMvc.perform(post("/api/estimates/" + est + "/merge-rows").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"rowIds\":[" + ids.get(0) + "," + ids.get(1) + "]}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Шифр, которого нет в каталоге, не должен унаследовать цены прежней расценки:
     * иначе деньги одной работы молча приписываются другому шифру.
     */
    @Test
    void unknownRateCodeClearsPricesAndFlagsRow() throws Exception {
        seedRate();
        long est = createEstimate(facility());
        String resp = mockMvc.perform(post("/api/estimates/" + est + "/rows").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"rateCode\":\"22-2203-113-1/1\",\"qty\":1,\"periodicity\":\"раз в 1 мес.\"}"))
                .andExpect(jsonPath("$.priceZp").value(362.26))
                .andReturn().getResponse().getContentAsString();
        long rowId = json.readTree(resp).get("id").asLong();

        mockMvc.perform(patch("/api/estimates/rows/" + rowId).header("Authorization", bearer())
                        .contentType("application/json").content("{\"rateCode\":\"22-2203-104-11/1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceZp").doesNotExist())
                .andExpect(jsonPath("$.rateName").doesNotExist())
                .andExpect(jsonPath("$.needsReview").value(true))
                .andExpect(jsonPath("$.matchNote").value(org.hamcrest.Matchers.containsString("нет в каталоге")));
    }

    /**
     * Аналоги для строки: в тестовом профиле ИИ выключен, поэтому предложений нет,
     * но эндпоинт обязан честно сказать об этом, а не молчать.
     */
    @Test
    void alternativesReportAiDisabledInsteadOfEmptySilence() throws Exception {
        seedRate();
        long est = createEstimate(facility());
        String resp = mockMvc.perform(post("/api/estimates/" + est + "/rows").header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"equipmentName\":\"Извещатель пожарный дымовой\","
                                + "\"operationName\":\"Техническое обслуживание\","
                                + "\"rateCode\":\"22-2203-113-1/1\",\"qty\":1}"))
                .andReturn().getResponse().getContentAsString();
        long rowId = json.readTree(resp).get("id").asLong();

        mockMvc.perform(get("/api/estimates/rows/" + rowId + "/alternatives").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiConfigured").value(false))
                .andExpect(jsonPath("$.options.length()").value(0));
    }

    /**
     * Проверка сметы без настроенной модели обязана сказать об этом прямо: пустой
     * список замечаний неотличим от «всё хорошо», а это разные вещи перед сдачей.
     */
    @Test
    void reviewExplainsWhenModelIsNotConfigured() throws Exception {
        seedRate();
        long est = createEstimate(facility());
        mockMvc.perform(post("/api/estimates/" + est + "/rows").header("Authorization", bearer())
                .contentType("application/json")
                .content("{\"equipmentName\":\"Извещатель\",\"rateCode\":\"22-2203-113-1/1\",\"qty\":1}"));

        mockMvc.perform(post("/api/estimates/" + est + "/review").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiConfigured").value(false))
                .andExpect(jsonPath("$.findings.length()").value(0))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("AI_REVIEW_MODEL")));
    }

    /**
     * Модель для проверки принимается только из настроек. Произвольная строка из
     * браузера означала бы запрос к чему угодно за счёт владельца ключа.
     */
    @Test
    void reviewRejectsModelOutsideConfiguredList() throws Exception {
        long est = createEstimate(facility());
        mockMvc.perform(post("/api/estimates/" + est + "/review")
                        .param("model", "какая-нибудь-дорогая-модель")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findings.length()").value(0))
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("AI_REVIEW_MODELS")));
    }

    /** Пустую смету на проверку не отправляем — незачем тратить запрос. */
    @Test
    void reviewOfEmptyEstimateDoesNotCallModel() throws Exception {
        long est = createEstimate(facility());
        mockMvc.perform(post("/api/estimates/" + est + "/review").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("нет строк")));
    }

    /**
     * Проверка живёт рядом со сметой: обновление страницы её не теряет, а удаление
     * сметы уносит вместе с собой. Прогон стоит денег — терять его нельзя.
     */
    @Test
    void savedReviewSurvivesReloadAndDiesWithEstimate() throws Exception {
        long est = createEstimate(facility());

        // проверки ещё не было — предупреждать о затирании нечего
        mockMvc.perform(get("/api/estimates/" + est + "/review").header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        ru.techdocs.estimate.EstimateReview review = new ru.techdocs.estimate.EstimateReview();
        review.setEstimateId(est);
        review.setModel("anthropic/claude-opus-5");
        review.setFindings("[{\"position\":5,\"severity\":\"HIGH\",\"category\":\"пропущенная работа\","
                + "\"title\":\"Нет ТО оповещателя\",\"detail\":\"проверьте\",\"impact\":\"11 153 ₽\"}]");
        reviewRepository.saveAndFlush(review);

        mockMvc.perform(get("/api/estimates/" + est + "/review").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("anthropic/claude-opus-5"))
                .andExpect(jsonPath("$.findings[0].position").value(5))
                .andExpect(jsonPath("$.findings[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.findings[0].title").value("Нет ТО оповещателя"));

        mockMvc.perform(delete("/api/estimates/" + est).header("Authorization", bearer()))
                .andExpect(status().isNoContent());
        // удаление сметы каскадом уносит проверку, но каскад срабатывает в базе —
        // внутри теста надо довести отложенный DELETE до неё
        reviewRepository.flush();
        org.assertj.core.api.Assertions.assertThat(reviewRepository.findByEstimateId(est)).isEmpty();
    }

    /**
     * Применение правок из проверки: замечание с конкретной правкой меняет смету и
     * помечается применённым, замечание без правки не трогается — оно требует
     * решения инженера, а не действия приложения.
     */
    @Test
    void appliesOnlyFindingsWithConcreteFix() throws Exception {
        seedRate();
        long est = createEstimate(facility());
        mockMvc.perform(post("/api/estimates/" + est + "/rows").header("Authorization", bearer())
                .contentType("application/json")
                .content("{\"section\":\"АПС\",\"equipmentName\":\"Извещатель\","
                        + "\"rateCode\":\"22-2203-113-1/1\",\"periodicity\":\"раз в год\",\"qty\":1}"));

        ru.techdocs.estimate.EstimateReview review = new ru.techdocs.estimate.EstimateReview();
        review.setEstimateId(est);
        review.setModel("test");
        review.setFindings("""
                [{"position":1,"severity":"HIGH","title":"Не указано количество",
                  "fix":{"action":"SET_QTY","qty":43}},
                 {"position":1,"severity":"MEDIUM","title":"Проверьте предмет договора"}]
                """);
        reviewRepository.saveAndFlush(review);

        mockMvc.perform(post("/api/estimates/" + est + "/review/apply").header("Authorization", bearer())
                        .contentType("application/json").content("{\"indexes\":[0,1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.skipped").value(1));

        // смета изменилась
        mockMvc.perform(get("/api/estimates/" + est).header("Authorization", bearer()))
                .andExpect(jsonPath("$.rows[0].row.qty").value(43))
                .andExpect(jsonPath("$.rows[0].row.matchNote")
                        .value(org.hamcrest.Matchers.containsString("Не указано количество")));

        // применённое замечание помечено и повторно не предлагается
        mockMvc.perform(get("/api/estimates/" + est + "/review").header("Authorization", bearer()))
                .andExpect(jsonPath("$.findings[0].applied").value(true))
                .andExpect(jsonPath("$.findings[1].applied").value(false));
    }

    /** Пропущенная работа добавляется отдельной строкой к тому же оборудованию. */
    @Test
    void addRowFixCreatesMissingWorkForSameEquipment() throws Exception {
        seedRate();
        long est = createEstimate(facility());
        mockMvc.perform(post("/api/estimates/" + est + "/rows").header("Authorization", bearer())
                .contentType("application/json")
                .content("{\"section\":\"АПС\",\"equipmentName\":\"Оповещатель SWS-103W\","
                        + "\"rateCode\":\"22-2203-113-1/1\",\"periodicity\":\"раз в 6 мес.\",\"qty\":43}"));

        ru.techdocs.estimate.EstimateReview review = new ru.techdocs.estimate.EstimateReview();
        review.setEstimateId(est);
        review.setModel("test");
        review.setFindings("""
                [{"position":1,"severity":"HIGH","title":"Отсутствует ТО оповещателя",
                  "fix":{"action":"ADD_ROW","rateCode":"22-2203-113-1/1","periodicity":"раз в год",
                         "opsPerYear":1,"operationName":"Техническое обслуживание"}}]
                """);
        reviewRepository.saveAndFlush(review);

        mockMvc.perform(post("/api/estimates/" + est + "/review/apply").header("Authorization", bearer())
                        .contentType("application/json").content("{\"indexes\":[0]}"))
                .andExpect(jsonPath("$.applied").value(1));

        mockMvc.perform(get("/api/estimates/" + est).header("Authorization", bearer()))
                .andExpect(jsonPath("$.rows.length()").value(2))
                // количество и описание унаследованы от исходной строки
                .andExpect(jsonPath("$.rows[1].row.qty").value(43))
                .andExpect(jsonPath("$.rows[1].row.equipmentName").value("Оповещатель SWS-103W"))
                .andExpect(jsonPath("$.rows[1].row.operationName").value("Техническое обслуживание"))
                .andExpect(jsonPath("$.rows[1].row.needsReview").value(true));
    }

    @Test
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/estimates")).andExpect(status().isUnauthorized());
    }
}
