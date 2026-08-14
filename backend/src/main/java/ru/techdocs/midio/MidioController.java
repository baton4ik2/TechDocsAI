package ru.techdocs.midio;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Интеграция с Midio: перенос готовых регламентов обслуживания в реестр. */
@RestController
@RequestMapping("/api/midio")
@RequiredArgsConstructor
public class MidioController {

    private final MidioSyncService syncService;
    private final MidioClient client;

    public record Status(boolean configured) {}

    /** Настроена ли интеграция — по этому признаку интерфейс показывает кнопку. */
    @GetMapping("/status")
    public Status status() {
        return new Status(client.isConfigured());
    }

    @PostMapping("/sync")
    public MidioSyncService.SyncResult sync() {
        return syncService.sync();
    }

    /**
     * Последний сохранённый отчёт синхронизации. 204 — синхронизаций ещё не было.
     * Нужен, чтобы уход со страницы не терял список ручных подтверждений.
     */
    @GetMapping("/last-sync")
    public ResponseEntity<MidioSyncService.SyncResult> lastSync() {
        MidioSyncService.SyncResult saved = syncService.lastReport();
        return saved == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(saved);
    }

    /** uniqueEquipmentIds — полный список записей для этой карточки Midio. */
    public record LinkRequest(Long uniqueEquipmentId, java.util.List<Long> uniqueEquipmentIds,
                              String midioId) {}

    /** Подтверждение связи; карточка Midio может соответствовать нескольким записям. */
    @PostMapping("/link")
    public ResponseEntity<Void> link(@RequestBody LinkRequest request) {
        java.util.List<Long> ids = request.uniqueEquipmentIds() != null && !request.uniqueEquipmentIds().isEmpty()
                ? request.uniqueEquipmentIds()
                : (request.uniqueEquipmentId() == null ? java.util.List.of()
                    : java.util.List.of(request.uniqueEquipmentId()));
        syncService.link(ids, request.midioId());
        return ResponseEntity.noContent().build();
    }
}
