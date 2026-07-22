package ru.techdocs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.pkm.PkmDocument;
import ru.techdocs.pkm.PkmDocumentRepository;
import ru.techdocs.pkm.PkmOperation;
import ru.techdocs.pkm.PkmOperationRepository;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
class PkmIntegrationTest extends IntegrationTestBase {

    @Autowired
    PkmDocumentRepository documentRepository;
    @Autowired
    PkmOperationRepository operationRepository;

    private PkmDocument document(String systemType) {
        PkmDocument d = new PkmDocument();
        d.setName("Регламент СКУД");
        d.setSystemType(systemType);
        d.setStatus(PkmDocument.STATUS_READY);
        return documentRepository.saveAndFlush(d);
    }

    private PkmOperation operation(Long pkmId, String systemType, int pos, String name, BigDecimal perYear) {
        PkmOperation op = new PkmOperation();
        op.setPkmId(pkmId);
        op.setSystemType(systemType);
        op.setPosition(pos);
        op.setOperationName(name);
        op.setPeriodicityPerYear(perYear);
        return operationRepository.saveAndFlush(op);
    }

    @Test
    void uploadRejectsNonDocx() throws Exception {
        MockMultipartFile pdf = new MockMultipartFile("file", "reglament.pdf",
                "application/pdf", "not docx".getBytes());
        mockMvc.perform(multipart("/api/pkm/upload").file(pdf)
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listAndOperationsEndpoints() throws Exception {
        PkmDocument doc = document("СКУД");
        operation(doc.getId(), "СКУД", 1, "Техническое обслуживание УКД", new BigDecimal("12"));
        operation(doc.getId(), "СКУД", 2, "Сезонное обслуживание", new BigDecimal("2"));

        mockMvc.perform(get("/api/pkm").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + doc.getId() + ")].systemType").value(org.hamcrest.Matchers.hasItem("СКУД")));

        mockMvc.perform(get("/api/pkm/" + doc.getId() + "/operations").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].operationName").value("Техническое обслуживание УКД"))
                .andExpect(jsonPath("$[0].periodicityPerYear").value(12));
    }

    @Test
    void findBySystemTypeReturnsOperations() {
        PkmDocument doc = document("СКУД");
        operation(doc.getId(), "СКУД", 1, "ТО УКД", new BigDecimal("12"));
        assertThat(operationRepository.findBySystemTypeOrderByPosition("СКУД")).hasSize(1);
    }

    @Test
    void deleteDocumentCascadesOperations() {
        PkmDocument doc = document("АПС");
        operation(doc.getId(), "АПС", 1, "ТО", new BigDecimal("4"));
        assertThat(operationRepository.countByPkmId(doc.getId())).isEqualTo(1);

        documentRepository.deleteById(doc.getId());
        documentRepository.flush();
        assertThat(operationRepository.countByPkmId(doc.getId())).isZero();
    }

    @Test
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/pkm")).andExpect(status().isUnauthorized());
    }
}
