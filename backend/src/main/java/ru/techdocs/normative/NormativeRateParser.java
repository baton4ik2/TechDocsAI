package ru.techdocs.normative;

import org.springframework.stereotype.Service;
import ru.techdocs.processing.PageText;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер расценок СН-2012 из текста PDF-сборника.
 * <p>
 * Структура расценки: шифр (22-2203-95-1/1) → наименование работ → 6 стоимостных
 * значений: прямые затраты, ЗП, ЭМ всего, в т.ч. ЗПМ, МР, затраты труда (чел-ч).
 * Все 6 — с десятичной запятой, нули обозначены прочерком «−»/«-», что позволяет
 * отделить их от чисел в наименовании («тип 6424», «502»).
 * <p>
 * «Состав работ:» и «Измеритель:» относятся к таблице (группе расценок) и часто
 * печатаются на ОТДЕЛЬНОЙ странице выше самих расценок. Поэтому наименование и
 * стоимости берём в пределах страницы расценки, а состав/измеритель ищем по
 * всему документу — по ближайшему заголовку выше данной расценки.
 */
@Service
public class NormativeRateParser {

    // шифр расценки: 22-2203-95-1/1, 1-2203-51-1/1, 24-2903-7-3/1
    private static final Pattern CODE = Pattern.compile("(\\d{1,2}-\\d{3,4}-\\d{1,3}-\\d{1,2}/\\d{1,2})");
    // стоимостной токен: число с десятичной запятой или прочерк (ноль).
    // Пробел допускается ТОЛЬКО как разделитель тысяч (группы ровно по 3 цифры),
    // иначе «типа 6424 502,15» слилось бы в одно число. Прочерк — отдельный символ,
    // а не дефис внутри слова («приемно-контрольного», «чел-ч»): границы по буквам/цифрам.
    private static final Pattern COST = Pattern.compile(
            "(?<![\\p{L}\\d])(\\d{1,3}(?:[ \\u00A0]\\d{3})+,\\d{1,3}|\\d+,\\d{1,3}|[-—–])(?![\\p{L}\\d])");
    private static final Pattern UNIT = Pattern.compile("Измеритель\\s*:\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPOSITION = Pattern.compile(
            "Состав\\s+работ\\s*:\\s*(.+?)(?=Измеритель\\s*:|Наименование|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Заголовок (состав/измеритель) с позицией в сквозном тексте документа. */
    private record Marker(int offset, String value) {}

    public List<NormativeRate> parse(List<PageText> pages) {
        // сквозной текст всего документа + карта: смещение начала каждой страницы
        StringBuilder full = new StringBuilder();
        List<int[]> pageStarts = new ArrayList<>();   // [offset, pageNumber]
        for (PageText page : pages) {
            String text = page.text() == null ? "" : page.text();
            pageStarts.add(new int[]{full.length(), page.pageNumber()});
            full.append(text).append('\n');
        }
        String document = full.toString();

        // заголовки «Состав работ:» и «Измеритель:» по всему документу — привяжем к
        // расценкам по ближайшему заголовку выше (таблица может быть на другой странице)
        List<Marker> compositions = markers(document, COMPOSITION, 2000, true);
        List<Marker> units = markers(document, UNIT, 60, false);

        List<NormativeRate> rates = new ArrayList<>();
        for (int p = 0; p < pageStarts.size(); p++) {
            String text = pages.get(p).text();
            if (text == null || text.isBlank()) continue;
            int pageOffset = pageStarts.get(p)[0];
            int pageNumber = pageStarts.get(p)[1];
            parsePage(text, pageOffset, pageNumber, compositions, units, rates);
        }
        return rates;
    }

    private List<Marker> markers(String document, Pattern pattern, int maxLen, boolean asComposition) {
        List<Marker> list = new ArrayList<>();
        Matcher m = pattern.matcher(document);
        while (m.find()) {
            String value = m.group(1).replaceAll("[ \\t\\u00A0]+", " ")
                    .replaceAll("\\s*\\n\\s*", " ").strip();
            value = asComposition ? formatComposition(value) : sanitizeUnit(value);
            if (value != null && !value.isBlank()) {
                list.add(new Marker(m.start(), truncate(value, maxLen)));
            }
        }
        return list; // упорядочены по возрастанию offset (порядок обхода Matcher.find)
    }

    /** Каждый пункт состава работ («1. …», «2. …») — с новой строки. */
    private String formatComposition(String value) {
        // список пунктов идёт inline после извлечения текста: ставим перенос перед «N. »
        return value.replaceAll("\\s+(\\d{1,2})[.)]\\s+", "\n$1. ").strip();
    }

    /** Измеритель — короткая единица («шт.», «10 шт.»). Отсекаем прилипший мусор
     *  из соседних колонок таблицы («в том числе», «Шифр», «Прямые…»). */
    private String sanitizeUnit(String value) {
        String v = value.split("(?i)в\\s+том\\s+числе|Шифр|Прямые|Наименование")[0].strip();
        v = v.replaceAll("[;,]\\s*$", "").strip();
        // явный мусор из ведомостей расхода материалов
        if (v.length() > 40 || v.contains("(") ) return null;
        return v.isBlank() ? null : v;
    }

    private void parsePage(String text, int pageOffset, int pageNumber,
                           List<Marker> compositions, List<Marker> units,
                           List<NormativeRate> rates) {
        Matcher codeMatcher = CODE.matcher(text);
        // позиции шифров, чтобы ограничивать блок каждой расценки
        List<int[]> codeSpans = new ArrayList<>();
        while (codeMatcher.find()) {
            codeSpans.add(new int[]{codeMatcher.start(), codeMatcher.end()});
        }
        for (int i = 0; i < codeSpans.size(); i++) {
            int[] span = codeSpans.get(i);
            String code = text.substring(span[0], span[1]);
            int blockEnd = (i + 1 < codeSpans.size()) ? codeSpans.get(i + 1)[0] : text.length();
            String block = text.substring(span[1], blockEnd);

            List<String> costs = new ArrayList<>();
            List<Integer> costStarts = new ArrayList<>();
            Matcher cm = COST.matcher(block);
            while (cm.find()) {
                costs.add(cm.group(1));
                costStarts.add(cm.start());
            }
            // нужны хотя бы ЗП: если стоимостных значений нет — это ссылка на шифр в тексте, не строка расценки
            if (costs.size() < 2) continue;

            // наименование — всё до последних 6 стоимостных колонок. Прочерк-периодичность
            // «- полугодовое»/«- годовое» перед числами остаётся в наименовании, а не
            // трактуется как нулевая колонка (иначе теряется различие расценок).
            int tailStart = costStarts.get(Math.max(0, costs.size() - 6));
            String name = block.substring(0, tailStart).strip()
                    .replaceAll("\\s{2,}", " ");
            if (name.isBlank() || !looksLikeRateName(name)) continue;

            int absCodePos = pageOffset + span[0];
            NormativeRate rate = new NormativeRate();
            rate.setCode(code);
            rate.setName(name);
            rate.setPageNumber(pageNumber);
            rate.setUnit(lastBefore(units, absCodePos));
            rate.setWorkComposition(lastBefore(compositions, absCodePos));
            assignCosts(rate, costs);
            rates.add(rate);
        }
    }

    /**
     * Наименование настоящей расценки — работа, начинается с буквы («Техническое
     * обслуживание…», «Замена…»). Строки из ведомостей расхода материалов начинаются
     * с кода материала («21.1-20-1 Бязь», «21.1-4-7 Газ…») — их отсекаем.
     */
    private boolean looksLikeRateName(String name) {
        String s = name.replaceFirst("^[\\s«»\"'`\\-–—]+", "");
        return !s.isEmpty() && Character.isLetter(s.charAt(0));
    }

    /** Значение ближайшего заголовка, расположенного выше позиции расценки. */
    private String lastBefore(List<Marker> markers, int pos) {
        String value = null;
        for (Marker m : markers) {
            if (m.offset() < pos) value = m.value();
            else break; // список упорядочен — дальше только заголовки ниже расценки
        }
        return value;
    }

    /**
     * 6 значений в порядке: Прямые(всего), ЗП, ЭМ(всего), ЗПМ, МР, затраты труда.
     * Берём ПОСЛЕДНИЕ 6 (перед ними в наименовании тоже могут быть числа с запятой).
     */
    private void assignCosts(NormativeRate rate, List<String> costs) {
        List<String> tail = costs.size() > 6 ? costs.subList(costs.size() - 6, costs.size()) : costs;
        // выравниваем по правому краю: последний — затраты труда
        int n = tail.size();
        rate.setLaborHours(num(get(tail, n - 1)));      // затраты труда
        rate.setMaterialCost(num(get(tail, n - 2)));    // МР
        rate.setMachineLabor(num(get(tail, n - 3)));    // ЗПМ
        rate.setMachineCost(num(get(tail, n - 4)));     // ЭМ всего
        rate.setLaborCost(num(get(tail, n - 5)));       // ЗП
        // n-6 — Прямые затраты (производная величина), не храним
    }

    private String get(List<String> list, int idx) {
        return idx >= 0 && idx < list.size() ? list.get(idx) : null;
    }

    private BigDecimal num(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.equals("-") || s.equals("—") || s.equals("–")) return BigDecimal.ZERO;
        s = s.replace(" ", "").replace(" ", "").replace(',', '.');
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
