package ru.techdocs.midio;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.techdocs.common.ModelMatching;
import ru.techdocs.uniqueequipment.UniqueEquipment;
import ru.techdocs.uniqueequipment.UniqueEquipmentRepository;
import ru.techdocs.uniqueequipment.UniqueEquipmentService;

import java.util.ArrayList;
import java.util.List;

/**
 * Сопоставление оборудования Midio с записями реестра.
 * <p>
 * Привязка делается один раз и запоминается по идентификатору Midio: дальше
 * синхронизация идёт по нему и переживает переименования на той стороне. Поиск
 * по тексту нужен только для первого знакомства.
 * <p>
 * Молча привязывать по похожести нельзя. «РМ-4К» одинаково близок к «РМ-1К» и к
 * «РМ-4», а расценки у них разные — на этом мы уже один раз обожглись в подборе
 * по эталону. Поэтому здесь ровно те же правила: сначала точный ключ, потом
 * похожесть модели с разведением по числам, а неоднозначное отдаётся инженеру.
 */
@Service
@RequiredArgsConstructor
public class MidioEquipmentMatcher {

    /** Оборудование на стороне Midio — то, что нужно узнать в нашем реестре. */
    public record ExternalEquipment(String externalId, String name, String model,
                                    String manufacturer, String system) {}

    public enum Kind {
        /** Уже привязано по идентификатору Midio. */
        LINKED,
        /** Совпал ключ реестра (наименование + модель + производитель [+ система]). */
        EXACT,
        /** Совпало по похожести модели выше порога и однозначно. */
        SIMILAR,
        /** Кандидатов несколько и они разные — решает инженер. */
        AMBIGUOUS,
        /** В реестре такого оборудования нет. */
        NONE
    }

    public record Match(ExternalEquipment source, UniqueEquipment target, Kind kind,
                        double score, List<UniqueEquipment> candidates) {

        public boolean isResolved() {
            return target != null && kind != Kind.AMBIGUOUS;
        }
    }

    private final UniqueEquipmentRepository repository;

    public List<Match> matchAll(List<ExternalEquipment> external) {
        List<UniqueEquipment> registry = repository.findAll();
        List<Match> result = new ArrayList<>();
        for (ExternalEquipment e : external) result.add(match(e, registry));
        return result;
    }

    public Match match(ExternalEquipment e, List<UniqueEquipment> registry) {
        // 1) уже привязано — по идентификатору, а не по тексту. Записей может
        //    быть несколько: одна карточка Midio ↔ две записи реестра (одна
        //    модель под разными названиями или в разных системах)
        if (e.externalId() != null && !e.externalId().isBlank()) {
            List<UniqueEquipment> linked = new ArrayList<>();
            for (UniqueEquipment ue : registry) {
                if (e.externalId().equals(ue.getMidioId())) linked.add(ue);
            }
            if (!linked.isEmpty()) {
                return new Match(e, linked.getFirst(), Kind.LINKED, 1.0, List.copyOf(linked));
            }
        }

        // 2) точный ключ реестра: сначала с системой, затем без неё — раздел на
        //    стороне Midio может называться иначе, и терять из-за этого связь глупо
        String withSystem = UniqueEquipmentService.normKey(e.name(), e.model(), e.manufacturer(), e.system());
        String withoutSystem = UniqueEquipmentService.equipKey(e.name(), e.model(), e.manufacturer());
        for (UniqueEquipment ue : registry) {
            if (withSystem.equals(ue.getNormKey())) return new Match(e, ue, Kind.EXACT, 1.0, List.of(ue));
        }
        List<UniqueEquipment> byEquipKey = registry.stream()
                .filter(ue -> withoutSystem.equals(ue.getEquipKey()))
                .toList();
        if (byEquipKey.size() == 1) {
            return new Match(e, byEquipKey.getFirst(), Kind.EXACT, 1.0, byEquipKey);
        }
        if (byEquipKey.size() > 1) {
            // одна модель в нескольких системах — какая имелась в виду, знает инженер
            return new Match(e, null, Kind.AMBIGUOUS, 1.0, byEquipKey);
        }

        // 3) похожесть модели
        return byModel(e, registry);
    }

    /**
     * Короче трёх знаков ключ модели ничего не различает («ИП» → ip), и похожесть
     * на нём выдаёт случайные совпадения. Три, а не четыре: «РМ-4» → pm4 — это
     * настоящее изделие, и отбрасывать его нельзя.
     */
    private static final int MIN_MODEL_KEY = 3;

    private Match byModel(ExternalEquipment e, List<UniqueEquipment> registry) {
        String key = ModelMatching.modelKey(e.model());
        if (key.length() < MIN_MODEL_KEY) return new Match(e, null, Kind.NONE, 0, List.of());

        List<UniqueEquipment> best = new ArrayList<>();
        double bestScore = 0;
        for (UniqueEquipment ue : registry) {
            String candidate = ModelMatching.modelKey(ue.getModel());
            if (candidate.length() < MIN_MODEL_KEY) continue;
            double score = ModelMatching.similarity(key, candidate);
            if (score > bestScore + 1e-9) {
                bestScore = score;
                best.clear();
                best.add(ue);
            } else if (Math.abs(score - bestScore) < 1e-9) {
                best.add(ue);
            }
        }
        if (best.isEmpty() || bestScore < ModelMatching.THRESHOLD) {
            return new Match(e, null, Kind.NONE, bestScore, List.of());
        }
        if (best.size() == 1) return new Match(e, best.getFirst(), Kind.SIMILAR, bestScore, best);

        // при равной похожести изделие различает номер в модели: РМ-1 и РМ-4 — разные приборы
        List<String> digits = ModelMatching.digits(key);
        List<UniqueEquipment> sameNumber = best.stream()
                .filter(ue -> ModelMatching.digits(ModelMatching.modelKey(ue.getModel())).equals(digits))
                .toList();
        if (sameNumber.size() == 1) {
            return new Match(e, sameNumber.getFirst(), Kind.SIMILAR, bestScore, sameNumber);
        }
        List<UniqueEquipment> candidates = sameNumber.isEmpty() ? best : sameNumber;
        return new Match(e, null, Kind.AMBIGUOUS, bestScore, candidates);
    }
}
