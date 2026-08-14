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
                          String reason, int workCount, List<WorkPreview> works,
                          List<Candidate> candidates) {}

    /** Работа Midio в отчёте — чтобы инженер видел, ЧТО привязывает, не заходя в Midio. */
    public record WorkPreview(String name, String periodicity, Boolean mandatory) {}

    public record Candidate(Long uniqueEquipmentId, String name, String model, String manufacturer) {}

    public record SyncResult(int linkedEquipment, int importedWorks, int skippedWorks,
                             List<Pending> pending, List<Pending> unknown) {}

    private final MidioClient client;
    private final MidioEquipmentMatcher matcher;
    private final UniqueEquipmentRepository equipmentRepository;
    private final PlannedWorkRepository plannedWorkRepository;
    private final MidioSyncReportRepository reportRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

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
        // у изделия есть свой план (на одно оборудование) — он и описывает его
        // обслуживание; работы зонных планов при этом лишние, иначе задвоятся
        for (Map.Entry<String, List<ExternalWork>> en : worksByEquipment.entrySet()) {
            List<ExternalWork> list = en.getValue();
            if (list.stream().anyMatch(ExternalWork::dedicatedPlan)) {
                en.setValue(list.stream().filter(ExternalWork::dedicatedPlan).toList());
            }
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

        // подписи изделий Midio — для происхождения работы («откуда она пришла»)
        Map<String, String> equipmentLabels = new LinkedHashMap<>();
        for (ExternalEquipment e : external) {
            equipmentLabels.put(e.externalId(),
                    java.util.stream.Stream.of(e.name(), e.model())
                            .filter(java.util.Objects::nonNull).reduce((a, b) -> a + " " + b).orElse(e.externalId()));
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
                int workCount = worksByEquipment.getOrDefault(e.externalId(), List.of()).size();
                skipped += workCount;
                // позиции без работ в отчёт не попадают: подтверждать их незачем —
                // переносить нечего, а тысячи пустых строк топят настоящие
                if (workCount > 0) {
                    Pending p = pending(m, worksByEquipment.get(e.externalId()));
                    if (m.kind() == Kind.NONE) unknown.add(p); else pending.add(p);
                }
                continue;
            }
            // одна карточка Midio может быть привязана к нескольким записям реестра —
            // работы получает каждая
            List<UniqueEquipment> targets = m.kind() == Kind.LINKED ? m.candidates() : List.of(m.target());
            for (UniqueEquipment target : targets) {
                link(target, e.externalId());
                linked++;
                imported += importWorks(target.getId(),
                        worksByEquipment.getOrDefault(e.externalId(), List.of()),
                        equipmentLabels.get(e.externalId()));
            }
        }

        log.info("Синхронизация с Midio: привязано {} позиций, перенесено {} работ, "
                + "на подтверждении {}, не найдено в реестре {}",
                linked, imported, pending.size(), unknown.size());
        SyncResult result = new SyncResult(linked, imported, skipped, pending, unknown);
        saveReport(result);
        return result;
    }

    /** Последний отчёт синхронизации — чтобы уход со страницы его не терял. */
    public SyncResult lastReport() {
        return reportRepository.findTopByOrderByIdDesc()
                .map(r -> {
                    try {
                        return objectMapper.readValue(r.getReport(), SyncResult.class);
                    } catch (Exception e) {
                        log.warn("Сохранённый отчёт Midio не читается: {}", e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    private void saveReport(SyncResult result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            reportRepository.deleteAll();
            MidioSyncReport report = new MidioSyncReport();
            report.setReport(json);
            reportRepository.save(report);
        } catch (Exception e) {
            // отчёт вспомогательный: его потеря не должна валить синхронизацию
            log.warn("Не удалось сохранить отчёт синхронизации Midio: {}", e.getMessage());
        }
    }

    /**
     * Ручное подтверждение связи. Выбор — полный список записей реестра для этой
     * карточки Midio: одна карточка законно соответствует нескольким записям
     * (одна модель под разными названиями или системами), а с записей вне списка
     * связь снимается — иначе работы задвоились бы у случайно оставшихся.
     */
    @Transactional
    public void link(List<Long> uniqueEquipmentIds, String midioId) {
        if (uniqueEquipmentIds == null || uniqueEquipmentIds.isEmpty()) {
            throw new BadRequestException("Выберите хотя бы одну запись реестра.");
        }
        List<UniqueEquipment> chosen = new ArrayList<>();
        for (Long id : uniqueEquipmentIds) {
            chosen.add(equipmentRepository.findById(id)
                    .orElseThrow(() -> new BadRequestException("Оборудование не найдено в реестре")));
        }
        equipmentRepository.findAll().stream()
                .filter(other -> !uniqueEquipmentIds.contains(other.getId())
                        && midioId.equals(other.getMidioId()))
                .forEach(other -> {
                    other.setMidioId(null);
                    equipmentRepository.save(other);
                });
        for (UniqueEquipment ue : chosen) link(ue, midioId);
        removeFromReport(midioId);
    }

    /** Подтверждённая позиция вычёркивается из сохранённого отчёта. */
    private void removeFromReport(String midioId) {
        SyncResult saved = lastReport();
        if (saved == null) return;
        saveReport(new SyncResult(saved.linkedEquipment(), saved.importedWorks(), saved.skippedWorks(),
                saved.pending().stream().filter(p -> !midioId.equals(p.externalId())).toList(),
                saved.unknown()));
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
    private int importWorks(Long uniqueEquipmentId, List<ExternalWork> works, String midioEquipmentLabel) {
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
            pw.setSourceNote(sourceNote(w, midioEquipmentLabel));
            saved.add(pw);
        }
        plannedWorkRepository.saveAll(saved);
        return saved.size();
    }

    /** Происхождение работы: план Midio и изделие, к которому она там относилась. */
    private String sourceNote(ExternalWork w, String midioEquipmentLabel) {
        StringBuilder sb = new StringBuilder();
        if (w.planName() != null && !w.planName().isBlank()) {
            sb.append("План Midio: «").append(w.planName().strip()).append('»');
        }
        if (midioEquipmentLabel != null && !midioEquipmentLabel.isBlank()) {
            if (!sb.isEmpty()) sb.append(" · ");
            sb.append("изделие Midio: ").append(midioEquipmentLabel);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private Pending pending(Match m, List<ExternalWork> works) {
        ExternalEquipment e = m.source();
        String reason = switch (m.kind()) {
            case AMBIGUOUS -> "Подходит несколько записей реестра — выберите нужную.";
            case NONE -> "В реестре нет такого оборудования. Синхронизируйте реестр с объектами.";
            default -> "Требуется подтверждение.";
        };
        List<Candidate> candidates = m.candidates().stream()
                .map(c -> new Candidate(c.getId(), c.getName(), c.getModel(), c.getManufacturer()))
                .toList();
        List<WorkPreview> previews = works.stream()
                .map(w -> new WorkPreview(w.name(), w.periodicity(), w.mandatory()))
                .toList();
        return new Pending(e.externalId(), e.name(), e.model(), e.manufacturer(), reason,
                works.size(), previews, candidates);
    }
}
