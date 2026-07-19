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
    private static final Pattern UNIT = Pattern.compile("Измеритель:\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPOSITION = Pattern.compile(
            "Состав работ:\\s*(.+?)(?=Измеритель:|Шифр|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public List<NormativeRate> parse(List<PageText> pages) {
        List<NormativeRate> rates = new ArrayList<>();
        for (PageText page : pages) {
            String text = page.text();
            if (text == null || text.isBlank()) continue;
            parsePage(text, page.pageNumber(), rates);
        }
        return rates;
    }

    private void parsePage(String text, int pageNumber, List<NormativeRate> rates) {
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
            Matcher cm = COST.matcher(block);
            int firstCostStart = -1;
            while (cm.find()) {
                if (firstCostStart < 0) firstCostStart = cm.start();
                costs.add(cm.group(1));
            }
            // нужны хотя бы ЗП: если стоимостных значений нет — это ссылка на шифр в тексте, не строка расценки
            if (costs.size() < 2) continue;

            String name = block.substring(0, firstCostStart).strip()
                    .replaceAll("\\s{2,}", " ");
            if (name.isBlank()) continue;

            NormativeRate rate = new NormativeRate();
            rate.setCode(code);
            rate.setName(name);
            rate.setPageNumber(pageNumber);
            rate.setUnit(findUnitBefore(text, span[0]));
            rate.setWorkComposition(findCompositionBefore(text, span[0]));
            assignCosts(rate, costs);
            rates.add(rate);
        }
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
        s = s.replace(" ", "").replace(" ", "").replace(',', '.');
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String findUnitBefore(String text, int codeStart) {
        String before = text.substring(0, codeStart);
        Matcher m = UNIT.matcher(before);
        String unit = null;
        while (m.find()) unit = m.group(1).strip();
        return unit == null || unit.isBlank() ? null : truncate(unit, 120);
    }

    private String findCompositionBefore(String text, int codeStart) {
        String before = text.substring(0, codeStart);
        Matcher m = COMPOSITION.matcher(before);
        String comp = null;
        while (m.find()) comp = m.group(1).strip().replaceAll("\\s{2,}", " ");
        return comp == null || comp.isBlank() ? null : truncate(comp, 2000);
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
