package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.processing.PageText;
import ru.techdocs.uniqueequipment.UniqueEquipmentPassportService.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сверка цитаты с текстом паспорта. Это единственная защита от выдуманной
 * периодичности: модель обязана привести дословный фрагмент, и если его в
 * паспорте нет — работа помечается неподтверждённой.
 */
class PassportQuoteTest {

    private Document doc() {
        return Document.of(List.of(
                new PageText(1, "Паспорт извещателя ИП 212-45.\nНазначение и технические характеристики."),
                new PageText(2, "Гарантийные обязательства. Срок гарантии — 24 месяца."),
                new PageText(3, "6. Техническое обслуживание\n"
                        + "Технический осмотр извещателя проводится\nежемесячно.\n"
                        + "Проверка работоспособности — не реже одного раза в шесть месяцев.")));
    }

    @Test
    void findsQuoteAndItsPage() {
        Document d = doc();
        int at = d.locate("Технический осмотр извещателя проводится ежемесячно");
        assertThat(at).isGreaterThan(0);
        assertThat(d.pageOf(at)).isEqualTo(3);
    }

    @Test
    void ignoresCaseAndLineBreaks() {
        Document d = doc();
        assertThat(d.locate("ПРОВЕРКА   работоспособности — не реже\nодного раза в шесть месяцев"))
                .isGreaterThan(0);
    }

    @Test
    void quoteFromAnotherPageResolvesToThatPage() {
        Document d = doc();
        int at = d.locate("Срок гарантии — 24 месяца");
        assertThat(d.pageOf(at)).isEqualTo(2);
    }

    @Test
    void inventedQuoteIsNotFound() {
        // ровно тот случай, ради которого всё это: модель «вспомнила» типовую
        // периодичность, которой в паспорте нет
        assertThat(doc().locate("Техническое обслуживание проводится один раз в квартал")).isEqualTo(-1);
    }

    @Test
    void ocrTypoStillMatchesByWordOverlap() {
        // OCR путает буквы: дословного вхождения нет, но слова на месте
        assertThat(doc().locate("Технический осмотр извещатепя проводится ежемесячно")).isGreaterThan(0);
    }

    @Test
    void tooShortQuoteIsRejected() {
        assertThat(doc().locate("осмотр")).isEqualTo(-1);
    }

    @Test
    void windowKeepsMaintenanceSection() {
        String window = doc().relevantWindow(200);
        assertThat(window).contains("Техническое обслуживание");
    }
}
