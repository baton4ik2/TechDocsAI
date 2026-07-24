package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.estimate.EstimateDecisionService;

import static org.assertj.core.api.Assertions.assertThat;

class EstimateDecisionKeyTest {

    @Test
    void categorisesOperationsConsistently() {
        // разные формулировки одной работы дают один ключ → решения совпадают
        assertThat(EstimateDecisionService.operationKey("Техническое обслуживание сервера СКУД с ПО"))
                .isEqualTo("то");
        assertThat(EstimateDecisionService.operationKey("Техническое обслуживание — Извещатель"))
                .isEqualTo("то");
        assertThat(EstimateDecisionService.operationKey("Технический осмотр источника питания"))
                .isEqualTo("осмотр");
        assertThat(EstimateDecisionService.operationKey("Замена извещателя пожарного"))
                .isEqualTo("замена");
        assertThat(EstimateDecisionService.operationKey("Контроль функционирования системы"))
                .isEqualTo("контроль");
        assertThat(EstimateDecisionService.operationKey("Проверка работоспособности"))
                .isEqualTo("проверка");
    }

    @Test
    void distinctOperationsGetDistinctKeys() {
        assertThat(EstimateDecisionService.operationKey("Техническое обслуживание"))
                .isNotEqualTo(EstimateDecisionService.operationKey("Технический осмотр"));
    }
}
