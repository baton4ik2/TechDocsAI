package ru.techdocs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.estimate.ReferenceEstimateImportService;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Импорт реального эталона СКУД пользователя — без ошибок, решения сохраняются. */
class ReferenceImportRealFileTest extends IntegrationTestBase {

    @Autowired
    ReferenceEstimateImportService importService;

    @Test
    @Transactional
    void importsRealReferenceFile() throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("/samples/reference-skud.xlsx")) {
            bytes = in.readAllBytes();
        }
        var file = new MockMultipartFile("file", "reference-skud.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);

        var res = importService.importXlsx(file);
        // 43-колоночная смета: строки с расценкой (без разделов, ИТОГО и прочерков)
        assertThat(res.imported()).isGreaterThanOrEqualTo(15);
        assertThat(res.rows()).isGreaterThanOrEqualTo(res.imported());
    }
}
