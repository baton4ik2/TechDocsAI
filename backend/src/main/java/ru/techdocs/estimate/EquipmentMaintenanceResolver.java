package ru.techdocs.estimate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.techdocs.common.Periodicity;
import ru.techdocs.equipment.Equipment;
import ru.techdocs.pkm.PkmOperation;
import ru.techdocs.pkm.PkmOperationRepository;

import java.math.BigDecimal;
import java.util.List;

/**
 * Определяет периодичность обслуживания оборудования по приоритету источников:
 * <ol>
 *   <li><b>Паспорт оборудования</b> (приоритетный) — появится с реестром уникального
 *       оборудования: паспорт привязывается к оборудованию и переиспользуется на
 *       всех объектах. Если у оборудования есть паспорт с ТО — берём его, а не ПКМ.</li>
 *   <li><b>ПКМ</b> — регламент обязательных работ по типу системы.</li>
 *   <li><b>Наименование расценки</b> — периодичность в названии («- ежемесячное»).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class EquipmentMaintenanceResolver {

    public static final String SOURCE_PASSPORT = "PASSPORT";
    public static final String SOURCE_PKM = "PKM";
    public static final String SOURCE_RATE = "RATE_NAME";
    public static final String SOURCE_NONE = "NONE";

    private final PkmOperationRepository pkmRepository;

    /** Периодичность: текст, раз/год, источник и пояснение (для колонки «обоснование»). */
    public record Planned(String periodicityText, BigDecimal perYear, String source, String note) {}

    public Planned resolve(Equipment equipment, String systemType, String rateName) {
        Planned fromPassport = resolveFromPassport(equipment);
        if (fromPassport != null) return fromPassport;

        if (systemType != null && !systemType.isBlank()) {
            Planned fromPkm = resolveFromPkm(systemType);
            if (fromPkm != null) return fromPkm;
        }

        BigDecimal perYear = Periodicity.perYear(rateName);
        if (perYear != null) {
            return new Planned(label(perYear), perYear, SOURCE_RATE, "периодичность из наименования расценки");
        }
        return new Planned(null, null, SOURCE_NONE, "периодичность не определена — укажите вручную");
    }

    /**
     * Паспорт оборудования (приоритетнее ПКМ). Пока не реализовано — появится вместе
     * с реестром уникального оборудования (Фаза 2): паспорт привязывается к
     * оборудованию, ТО и периодичность берутся из него.
     */
    private Planned resolveFromPassport(Equipment equipment) {
        // TODO(Фаза 2): if (equipment has passport with ТО) return из паспорта, source=PASSPORT
        return null;
    }

    private Planned resolveFromPkm(String systemType) {
        List<PkmOperation> ops = pkmRepository.findBySystemTypeOrderByPosition(systemType);
        if (ops.isEmpty()) return null;
        // основная операция ТО системы (иначе — первая в регламенте)
        PkmOperation to = ops.stream()
                .filter(o -> o.getOperationName() != null
                        && o.getOperationName().toLowerCase().contains("обслуживан"))
                .findFirst()
                .orElse(ops.get(0));
        return new Planned(to.getPeriodicity(), to.getPeriodicityPerYear(),
                SOURCE_PKM, "ПКМ: " + to.getOperationName());
    }

    /** Человекочитаемая периодичность из числа выполнений в год. */
    private String label(BigDecimal perYear) {
        if (perYear == null) return null;
        int py = perYear.intValue();
        return switch (py) {
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
