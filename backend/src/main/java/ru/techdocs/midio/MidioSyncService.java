package ru.techdocs.midio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.techdocs.common.BadRequestException;
import ru.techdocs.common.Periodicity;
import ru.techdocs.midio.MidioClient.ExternalWork;
import ru.techdocs.midio.MidioEquipmentMatcher.ExternalEquipment;
import ru.techdocs.midio.MidioEquipmentMatcher.Kind;
import ru.techdocs.midio.MidioEquipmentMatcher.Match;
import ru.techdocs.uniqueequipment.PlannedWork;
import ru.techdocs.uniqueequipment.PlannedWorkRepository;
import ru.techdocs.uniqueequipment.UniqueEquipment;
import ru.techdocs.uniqueequipment.UniqueEquipmentRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Синхронизация регламентов из Midio в реестр оборудования.
 * <p>
 * Регламенты там ведёт команда — это принятое инженерное решение, поэтому они
 * старше извлечённых ИИ из паспорта. Разбор паспорта остаётся для оборудования,
 * которого в Midio нет.
 * <p>
 * Что не переносится молча: оборудование, узнанное неоднозначно. Такие позиции
 * возвращаются списком с кандидатами — инженер подтверждает связь, и следующая
 * синхронизация идёт уже по идентификатору.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MidioSyncService {

    /** Позиция, которую не удалось привязать однозначно. */
    public record Pending(String externalId, String name, String model, String manufacturer,
                          String reason, List<Candidate> candidates) {}

    public record Candidate(Long uniqueEquipmentId, String name, String model, String manufacturer) {}

    public record SyncResult(int linkedEquipment, int importedWorks, int skippedWorks,
                             List<Pending> pending, List<Pending> unknown) {}

    private final MidioClient client;
    private final MidioEquipmentMatcher matcher;
    private final UniqueEquipmentRepository equipmentRepository;
    private final PlannedWorkRepository plannedWorkRepository;

    @Transactional
    public SyncResult sync() {
        if (!client.isConfigured()) {
            throw new BadRequestException("Интеграция с Midio не настроена: укажите адрес и токен доступа.");
        }
        List<ExternalEquipment> external = client.equipment();
        List<ExternalWork> works = client.works();
        Map<String, List<ExternalWork>> worksByEquipment = new LinkedHashMap<>();
        for (ExternalWork w : works) {
            if (w.equipmentExternalId() == null) continue;
            worksByEquipment.computeIfAbsent(w.equipmentExternalId(), k -> new ArrayList<>()).add(w);
        }

        // работы, ссылающиеся на оборудование вне полученного списка карточек, —
        // признак несовпадения идентификаторов между методами; молча пропадать не должны
        java.util.Set<String> knownIds = new java.util.HashSet<>();
        for (ExternalEquipment e : external) knownIds.add(e.externalId());
        long strayWorks = worksByEquipment.entrySet().stream()
                .filter(en -> !knownIds.contains(en.getKey()))
                .mapToLong(en -> en.getValue().size())
                .sum();
        if (strayWorks > 0) {
            String sampleWorkKey = worksByEquipment.keySet().stream()
                    .filter(k -> !knownIds.contains(k)).findFirst().orElse("?");
            log.warn("Midio: {} работ ссылаются на оборудование вне списка карточек "
                    + "(пример ссылки из работы: «{}», пример id карточки: «{}»)",
                    strayWorks, sampleWorkKey, knownIds.stream().findFirst().orElse("?"));
        }

        List<Match> matches = matcher.matchAll(external);
        List<Pending> pending = new ArrayList<>();
        List<Pending> unknown = new ArrayList<>();
        int linked = 0;
        int imported = 0;
        int skipped = 0;

        for (Match m : matches) {
            ExternalEquipment e = m.source();
            if (!m.isResolved()) {
                Pending p = pending(m);
                if (m.kind() == Kind.NONE) unknown.add(p); else pending.add(p);
                skipped += worksByEquipment.getOrDefault(e.externalId(), List.of()).size();
                continue;
            }
            UniqueEquipment target = m.target();
            link(target, e.externalId());
            linked++;
            imported += importWorks(target.getId(), worksByEquipment.getOrDefault(e.externalId(), List.of()));
        }

        log.info("Синхронизация с Midio: привязано {} позиций, перенесено {} работ, "
                + "на подтверждении {}, не найдено в реестре {}",
                linked, imported, pending.size(), unknown.size());
        return new SyncResult(linked, imported, skipped, pending, unknown);
    }

    /**
     * Ручное подтверждение связи для неоднозначной позиции. После него
     * синхронизация идёт по идентификатору и больше не гадает.
     */
    @Transactional
    public void link(Long uniqueEquipmentId, String midioId) {
        UniqueEquipment ue = equipmentRepository.findById(uniqueEquipmentId)
                .orElseThrow(() -> new BadRequestException("Оборудование не найдено в реестре"));
        // тот же идентификатор на другой записи означал бы две связи на одно
        // оборудование Midio — снимаем прежнюю, иначе работы задвоятся
        equipmentRepository.findAll().stream()
                .filter(other -> !other.getId().equals(uniqueEquipmentId) && midioId.equals(other.getMidioId()))
                .forEach(other -> {
                    other.setMidioId(null);
                    equipmentRepository.save(other);
                });
        link(ue, midioId);
    }

    private void link(UniqueEquipment ue, String midioId) {
        ue.setMidioId(midioId);
        ue.setMidioSyncedAt(Instant.now());
        equipmentRepository.save(ue);
    }

    /**
     * Переносит работы Midio, заменяя ранее перенесённые. Паспортные и ручные не
     * трогаем: паспортные вытесняются приоритетом при расчёте сметы, а ручные
     * инженер завёл сам.
     */
    private int importWorks(Long uniqueEquipmentId, List<ExternalWork> works) {
        plannedWorkRepository.deleteByUniqueEquipmentIdAndSource(uniqueEquipmentId, PlannedWork.SOURCE_MIDIO);
        if (works.isEmpty()) return 0;

        List<PlannedWork> saved = new ArrayList<>();
        int position = 1;
        for (ExternalWork w : works) {
            if (w.name() == null || w.name().isBlank()) continue;
            PlannedWork pw = new PlannedWork();
            pw.setUniqueEquipmentId(uniqueEquipmentId);
            pw.setPosition(position++);
            pw.setName(w.name());
            pw.setWorkType(w.workType());
            pw.setWorkComposition(w.composition());
            pw.setPeriodicity(w.periodicity());
            // число берём как есть: обратный разбор текста терял бы нетиповые
            // периодичности вроде «раз в 2 года»
            pw.setPeriodicityPerYear(w.perYear() != null ? w.perYear() : Periodicity.perYear(w.periodicity()));
            pw.setMandatory(w.mandatory());
            pw.setSource(PlannedWork.SOURCE_MIDIO);
            pw.setExternalId(w.externalId());
            saved.add(pw);
        }
        plannedWorkRepository.saveAll(saved);
        return saved.size();
    }

    private Pending pending(Match m) {
        ExternalEquipment e = m.source();
        String reason = switch (m.kind()) {
            case AMBIGUOUS -> "Подходит несколько записей реестра — выберите нужную.";
            case NONE -> "В реестре нет такого оборудования. Синхронизируйте реестр с объектами.";
            default -> "Требуется подтверждение.";
        };
        List<Candidate> candidates = m.candidates().stream()
                .map(c -> new Candidate(c.getId(), c.getName(), c.getModel(), c.getManufacturer()))
                .toList();
        return new Pending(e.externalId(), e.name(), e.model(), e.manufacturer(), reason, candidates);
    }
}
