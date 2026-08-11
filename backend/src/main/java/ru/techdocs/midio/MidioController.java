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

    public record LinkRequest(Long uniqueEquipmentId, String midioId) {}

    /** Подтверждение связи для позиции, которую не удалось узнать однозначно. */
    @PostMapping("/link")
    public ResponseEntity<Void> link(@RequestBody LinkRequest request) {
        syncService.link(request.uniqueEquipmentId(), request.midioId());
        return ResponseEntity.noContent().build();
    }
}
