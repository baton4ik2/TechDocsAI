package ru.techdocs.estimate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * История выгрузок сметы. Каждая выгрузка — новая версия (v1 → v1_1 → v1_2):
 * номер попадает в имя файла, а весь список печатается на листе «Данные для расчета».
 */
@Service
@RequiredArgsConstructor
public class EstimateExportHistoryService {

    private final EstimateExportRepository repository;

    /** Регистрирует новую выгрузку и возвращает всю историю (последняя — текущая). */
    @Transactional
    public List<EstimateXlsxExporter.Version> register(Long estimateId) {
        List<EstimateExport> existing = repository.findByEstimateIdOrderByVersion(estimateId);
        EstimateExport current = new EstimateExport();
        current.setEstimateId(estimateId);
        current.setVersion(existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getVersion() + 1);
        current.setExportedAt(Instant.now());
        repository.save(current);
        return repository.findByEstimateIdOrderByVersion(estimateId).stream()
                .map(e -> new EstimateXlsxExporter.Version(e.getVersion(), e.getExportedAt()))
                .toList();
    }
}
