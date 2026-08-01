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
        String body = "{\"section\":\"АПС\",\"equipmentName\":\"Извещатель пожарный дымовой\","
                + "\"rateCode\":\"22-2203-113-1/1\",\"periodicity\":\"раз в год\",\"qty\":%d}";
        for (int qty : new int[]{40, 60, 16}) {
            mockMvc.perform(post("/api/estimates/" + est + "/rows").header("Authorization", bearer())
                            .contentType("application/json").content(String.format(body, qty)))
                    .andExpect(status().isOk());
        }

        String groupsResp = mockMvc.perform(get("/api/estimates/" + est + "/duplicate-groups")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].totalQty").value(116))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode ids = json.readTree(groupsResp).get(0).get("rowIds");

        mockMvc.perform(post("/api/estimates/" + est + "/merge-rows").header("Authorization", bearer())
                        .contentType("application/json").content("{\"rowIds\":" + ids + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qty").value(116));

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

    @Test
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/estimates")).andExpect(status().isUnauthorized());
    }
}
