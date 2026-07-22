package ru.techdocs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.normative.NormativeRate;
import ru.techdocs.normative.NormativeRateRepository;
import ru.techdocs.normative.NormativeSourcebook;
import ru.techdocs.normative.NormativeSourcebookRepository;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
class NormativeIntegrationTest extends IntegrationTestBase {

    @Autowired
    NormativeSourcebookRepository sourcebookRepository;
    @Autowired
    NormativeRateRepository rateRepository;
    @Autowired
    ru.techdocs.normative.NormativeService normativeService;

    private NormativeSourcebook sourcebook() {
        NormativeSourcebook book = new NormativeSourcebook();
        book.setName("СН-2012. Системы безопасности");
        book.setCode("Сборник 22");
        book.setStatus(NormativeSourcebook.STATUS_READY);
        return sourcebookRepository.saveAndFlush(book);
    }

    private NormativeRate rate(Long bookId, String code, String name, BigDecimal zp) {
        NormativeRate r = new NormativeRate();
        r.setSourcebookId(bookId);
        r.setCode(code);
        r.setName(name);
        r.setUnit("1 прибор");
        r.setLaborCost(zp);
        return rateRepository.saveAndFlush(r);
    }

    @Test
    void fullTextSearchFindsRatesByPartialWords() {
        Long bookId = sourcebook().getId();
        rate(bookId, "22-2203-95-1/1",
                "Техническое обслуживание прибора приёмно-контрольного пожарного", new BigDecimal("421.30"));
        rate(bookId, "22-2203-96-1/1",
                "Техническое обслуживание извещателя пожарного дымового", new BigDecimal("380.50"));
        rate(bookId, "22-2210-10-1/1",
                "Монтаж кабельного лотка металлического", new BigDecimal("150.00"));

        // запрос по нескольким словам — OR-семантика, извещатель ранжируется выше
        List<NormativeRate> found = rateRepository.search("обслуживание извещателя пожарного", 10);
        assertThat(found).isNotEmpty();
        assertThat(found).extracting(NormativeRate::getName)
                .anyMatch(n -> n.contains("извещателя"));
        // кабельный лоток не про обслуживание — не должен быть первым
        assertThat(found.get(0).getName()).contains("обслуживание");
    }

    @Test
    void shifrQueryUsesCodeSearchNotFullText() {
        Long bookId = sourcebook().getId();
        rate(bookId, "22-2203-128-1/1", "Техническое обслуживание извещателя", new BigDecimal("139.33"));
        rate(bookId, "22-2203-95-1/1", "Обслуживание прибора", new BigDecimal("421.30"));

        // запрос-шифр не должен ИЛИ-матчиться по числам 22/2203/1 и тянуть всё подряд
        var found = normativeService.search("22-2203-128-1/1", 15);
        assertThat(found).extracting(NormativeRate::getCode).containsExactly("22-2203-128-1/1");

        // префикс шифра возвращает обе расценки этой группы
        assertThat(normativeService.search("22-2203", 15))
                .extracting(NormativeRate::getCode)
                .containsExactlyInAnyOrder("22-2203-128-1/1", "22-2203-95-1/1");
    }

    @Test
    void searchRespectsLimit() {
        Long bookId = sourcebook().getId();
        for (int i = 0; i < 5; i++) {
            rate(bookId, "22-2203-" + i + "-1/1", "Обслуживание системы номер " + i, new BigDecimal("100.00"));
        }
        assertThat(rateRepository.search("обслуживание системы", 2)).hasSize(2);
    }

    @Test
    void deleteSourcebookCascadesRates() {
        Long bookId = sourcebook().getId();
        rate(bookId, "22-2203-95-1/1", "Обслуживание прибора", new BigDecimal("10.00"));
        assertThat(rateRepository.countBySourcebookId(bookId)).isEqualTo(1);

        sourcebookRepository.deleteById(bookId);
        sourcebookRepository.flush();
        assertThat(rateRepository.countBySourcebookId(bookId)).isZero();
    }

    @Test
    void uploadRejectsNonPdf() throws Exception {
        MockMultipartFile txt = new MockMultipartFile("file", "sbornik.txt",
                "text/plain", "не pdf".getBytes());
        mockMvc.perform(multipart("/api/normatives/upload").file(txt)
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listAndSearchEndpointsWork() throws Exception {
        Long bookId = sourcebook().getId();
        rate(bookId, "22-2203-95-1/1", "Обслуживание прибора приёмно-контрольного", new BigDecimal("421.30"));

        mockMvc.perform(get("/api/normatives").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + bookId + ")]").exists());

        mockMvc.perform(get("/api/normatives/rates/search")
                        .param("query", "обслуживание прибора")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void searchEndpointRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/normatives/rates/search").param("query", "x"))
                .andExpect(status().isUnauthorized());
    }
}
