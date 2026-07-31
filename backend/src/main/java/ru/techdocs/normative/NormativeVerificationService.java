package ru.techdocs.normative;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Самопроверка каталога расценок. Цены в смету попадают из распознанного PDF, и
 * ошибка распознавания стоит денег: в аудите ЗП по «удалению пыли с дымовой камеры»
 * оказалась 311,86 вместо 306,52. Здесь лежат вручную сверенные по PDF значения —
 * сервис сравнивает их с каталогом и показывает расхождения.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NormativeVerificationService {

    private static final String RESOURCE = "normative/verified-rates.csv";

    private final NormativeRateRepository rateRepository;

    /** Расхождение по одному показателю расценки (или отсутствие расценки в каталоге). */
    public record Discrepancy(String code, String field, String expected, String actual) {}

    public record Report(int checked, int missing, List<Discrepancy> discrepancies) {}

    public Report verify() {
        List<Discrepancy> found = new ArrayList<>();
        int checked = 0, missing = 0;
        for (Map.Entry<String, Map<String, BigDecimal>> e : reference().entrySet()) {
            String code = e.getKey();
            NormativeRate rate = rateRepository.findFirstByCodeOrderById(code).orElse(null);
            if (rate == null) {
                missing++;
                found.add(new Discrepancy(code, "расценка", "есть в сверенном списке", "нет в каталоге"));
                continue;
            }
            checked++;
            Map<String, BigDecimal> expected = e.getValue();
            compare(found, code, "ЗП", expected.get("zp"), rate.getLaborCost());
            compare(found, code, "ЭМ", expected.get("em"), rate.getMachineCost());
            compare(found, code, "ЗПМ", expected.get("zpm"), rate.getMachineLabor());
            compare(found, code, "МР", expected.get("mr"), rate.getMaterialCost());
            compare(found, code, "затраты труда", expected.get("hours"), rate.getLaborHours());
        }
        return new Report(checked, missing, found);
    }

    private void compare(List<Discrepancy> out, String code, String field,
                         BigDecimal expected, BigDecimal actual) {
        // прочерк в сборнике и ноль в каталоге — одно и то же, расхождением не считаем
        if (zeroish(expected) && zeroish(actual)) return;
        if (expected != null && actual != null && expected.compareTo(actual) == 0) return;
        out.add(new Discrepancy(code, field, text(expected), text(actual)));
    }

    private boolean zeroish(BigDecimal v) {
        return v == null || v.signum() == 0;
    }

    private String text(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    /** Сверенные значения из ресурса: шифр → показатель → значение. */
    private Map<String, Map<String, BigDecimal>> reference() {
        Map<String, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split(";", -1);
                if (parts.length < 7) continue;
                Map<String, BigDecimal> values = new LinkedHashMap<>();
                values.put("zp", number(parts[2]));
                values.put("em", number(parts[3]));
                values.put("zpm", number(parts[4]));
                values.put("mr", number(parts[5]));
                values.put("hours", number(parts[6]));
                result.put(parts[0].strip(), values);
            }
        } catch (Exception e) {
            log.warn("Не удалось прочитать список сверенных расценок: {}", e.getMessage());
        }
        return result;
    }

    private BigDecimal number(String raw) {
        String v = raw == null ? "" : raw.strip().replace(',', '.');
        if (v.isEmpty()) return null;
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
