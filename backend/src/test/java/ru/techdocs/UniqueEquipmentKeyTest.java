package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.uniqueequipment.UniqueEquipmentService;

import static org.assertj.core.api.Assertions.assertThat;

class UniqueEquipmentKeyTest {

    @Test
    void mergesWhitespaceAndDashVariants() {
        String plain = UniqueEquipmentService.normKey(
                "24-портовый Ethernet-коммутатор", "LTV-3S24G4C-P", "LTV", "СКУД");
        // тот же текст, но с неразрывным пробелом (U+00A0) и другими дефисами
        // (U+2013 en-dash, U+2010 hyphen, U+2011 non-breaking hyphen)
        String weird = UniqueEquipmentService.normKey(
                "24–портовый Ethernet–коммутатор", "LTV‐3S24G4C‑P", "LTV", "СКУД");
        assertThat(weird).isEqualTo(plain);
    }

    @Test
    void caseAndYoInsensitive() {
        assertThat(UniqueEquipmentService.normKey("Извещатель Ёмкостный", "ИП-1", "Рубеж", "АПС"))
                .isEqualTo(UniqueEquipmentService.normKey("извещатель емкостный", "ип-1", "рубеж", "апс"));
    }

    @Test
    void differentModelsStayDistinct() {
        assertThat(UniqueEquipmentService.normKey("Коммутатор", "LTV-3S24", "LTV", "СКУД"))
                .isNotEqualTo(UniqueEquipmentService.normKey("Коммутатор", "LTV-2S48", "LTV", "СКУД"));
    }

    @Test
    void sameModelDifferentSystemsAreDistinct() {
        // одна модель в разных инженерных системах → разные ключи (две записи реестра)
        assertThat(UniqueEquipmentService.normKey("Коммутатор", "LTV-3S24", "LTV", "СКУД"))
                .isNotEqualTo(UniqueEquipmentService.normKey("Коммутатор", "LTV-3S24", "LTV", "Видеонаблюдение"));
    }

    @Test
    void systemSynonymsMergeToSameKey() {
        // «Пожарная сигнализация» и «СПС» — одна система → один ключ
        assertThat(UniqueEquipmentService.normKey("Извещатель", "ИП-1", "Рубеж", "Пожарная сигнализация"))
                .isEqualTo(UniqueEquipmentService.normKey("Извещатель", "ИП-1", "Рубеж", "СПС"));
    }
}
