package ru.techdocs.estimate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.techdocs.common.Periodicity;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.pkm.PkmOperation;
import ru.techdocs.pkm.PkmOperationRepository;
import ru.techdocs.uniqueequipment.PlannedWork;
import ru.techdocs.uniqueequipment.PlannedWorkRepository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Определяет плановые операции и периодичность обслуживания оборудования по
 * приоритету источников:
 * <ol>
 *   <li><b>Midio</b> — регламент, заведённый командой в Midio. Старше паспорта:
 *       это принятое инженерное решение, а не извлечение ИИ из текста.</li>
 *   <li><b>Паспорт</b> — плановые работы уникального оборудования (осмотр, ТО,
 *       контроль функционирования …). Приоритетны перед ПКМ: если у оборудования
 *       есть паспортные работы — черновик сметы идёт по ним.</li>
 *   <li><b>ПКМ</b> — регламент по типу системы (основная операция ТО).</li>
 *   <li><b>Наименование расценки</b> — периодичность из названия («- ежемесячное»),
 *       используется как запасной вариант в {@link #perYearFromRate}.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class EquipmentMaintenanceResolver {

    public static final String SOURCE_MIDIO = "MIDIO";
    public static final String SOURCE_PASSPORT = "PASSPORT";
    public static final String SOURCE_PKM = "PKM";
    public static final String SOURCE_RATE = "RATE_NAME";

    private final PkmOperationRepository pkmRepository;
    private final PlannedWorkRepository plannedWorkRepository;

    /** Плановая операция: имя, тип, периодичность, источник, пояснение. */
    public record Planned(String operationName, String workType, String periodicityText,
                          BigDecimal perYear, String source, String note) {}

    /**
     * Плановые операции для оборудования (по приоритету Midio → паспорт → ПКМ).
     * Пустой список — источников нет, вызывающий формирует одну общую операцию ТО
     * и берёт периодичность из наименования расценки.
     */
    public List<Planned> resolveOperations(Equipment equipment, String systemType) {
        // 1) плановые работы уникального оборудования
        if (equipment.getUniqueEquipmentId() != null) {
            List<PlannedWork> works = relevantWorks(equipment.getUniqueEquipmentId());
            if (!works.isEmpty()) {
                return works.stream().map(w -> new Planned(
                        w.getName(), w.getWorkType(), w.getPeriodicity(), w.getPeriodicityPerYear(),
                        PlannedWork.SOURCE_MIDIO.equals(w.getSource()) ? SOURCE_MIDIO : SOURCE_PASSPORT,
                        workNote(w)
                )).toList();
            }
        }
        // 2) ПКМ: основная операция ТО системы
        if (systemType != null && !systemType.isBlank()) {
            Planned pkm = resolveFromPkm(systemType);
            if (pkm != null) return List.of(pkm);
        }
        return List.of();
    }

    /**
     * Регламент из Midio вытесняет паспортный: там его вела команда — это принятое
     * инженерное решение, а не извлечённое ИИ из текста. Работы, заведённые вручную,
     * остаются в любом случае: их добавил инженер осознанно, и молча терять их нельзя.
     */
    private List<PlannedWork> relevantWorks(Long uniqueEquipmentId) {
        List<PlannedWork> works = plannedWorkRepository.findByUniqueEquipmentIdOrderByPosition(uniqueEquipmentId);
        boolean hasMidio = works.stream().anyMatch(w -> PlannedWork.SOURCE_MIDIO.equals(w.getSource()));
        if (!hasMidio) return works;
        return works.stream()
                .filter(w -> !PlannedWork.SOURCE_PASSPORT.equals(w.getSource()))
                .toList();
    }

    /**
     * Обоснование периодичности для строки сметы. Указываем страницу паспорта —
     * это то, что проверяет инженер и что защищает смету при разборе. Работу с
     * неподтверждённой цитатой помечаем прямо здесь: она попадёт в смету, но
     * будет видно, что первоисточник не сверен.
     */
    private String workNote(PlannedWork w) {
        StringBuilder sb = new StringBuilder(w.getSourceLabel() == null ? "Паспорт" : w.getSourceLabel());
        if (w.getWorkType() != null && !w.getWorkType().isBlank()) {
            sb.append(" (").append(w.getWorkType()).append(')');
        }
        if (Boolean.FALSE.equals(w.getQuoteVerified())) {
            sb.append(" — ⚠ цитата не подтверждена, проверьте паспорт");
        }
        return sb.toString();
    }

    private Planned resolveFromPkm(String systemType) {
        List<PkmOperation> ops = pkmRepository.findBySystemTypeOrderByPosition(systemType);
        if (ops.isEmpty()) return null;
        PkmOperation to = ops.stream()
                .filter(o -> o.getOperationName() != null
                        && o.getOperationName().toLowerCase().contains("обслуживан"))
                .findFirst()
                .orElse(ops.get(0));
        return new Planned(to.getOperationName(), "ТО", to.getPeriodicity(), to.getPeriodicityPerYear(),
                SOURCE_PKM, "ПКМ: " + to.getOperationName());
    }

    /** Периодичность из наименования расценки («- ежемесячное»). */
    public BigDecimal perYearFromRate(String rateName) {
        return Periodicity.perYear(rateName);
    }

    /** Человекочитаемая периодичность из числа выполнений в год. */
    public String label(BigDecimal perYear) {
        if (perYear == null) return null;
        return switch (perYear.intValue()) {
            case 365 -> "Ежедневно";
            case 52 -> "Еженедельно";
            case 12 -> "Ежемесячно";
            case 4 -> "Ежеквартально";
            case 2 -> "Два раза в год";
            case 1 -> "Раз в год";
            default -> perYear.stripTrailingZeros().toPlainString() + " раз(а) в год";
        };
    }
}
