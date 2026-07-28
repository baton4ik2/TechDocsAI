package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.estimate.EstimateDecisionService;

import java.util.List;

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

    /** Нумерованные работы одной категории — разные решения (ТО 1 / ТО 2 / ТО 3). */
    @Test
    void numberedWorksOfSameCategoryStayDistinct() {
        String to1 = EstimateDecisionService.operationKey("Техническое обслуживание 1");
        String to2 = EstimateDecisionService.operationKey("Техническое обслуживание 2 Контроль работоспособности извещателя");
        String to3 = EstimateDecisionService.operationKey("Техническое обслуживание 3 Продувка от пыли");
        assertThat(to1).isEqualTo("то1");
        assertThat(to2).isEqualTo("то2");
        assertThat(to3).isEqualTo("то3");
        assertThat(List.of(to1, to2, to3)).doesNotHaveDuplicates();
        // без номера — обычная категория, чтобы правки по одной работе обновляли её же
        assertThat(EstimateDecisionService.operationKey(
                "Техническое обслуживание приборов контроля и управления (ППКП/ПУ)")).isEqualTo("то");
    }

    /** «Техническое обслуживание …, проверка АКБ» — это ТО, а не отдельная «проверка». */
    @Test
    void maintenanceWinsOverCheckWording() {
        assertThat(EstimateDecisionService.operationKey(
                "Техническое обслуживание источника вторичного электропитания, проверка АКБ"))
                .isEqualTo("то")
                .isEqualTo(EstimateDecisionService.operationKey(
                        "Техническое обслуживание источника вторичного электропитания"));
    }
}
