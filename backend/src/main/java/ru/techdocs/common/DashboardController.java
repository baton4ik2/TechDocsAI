package ru.techdocs.common;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.techdocs.chat.Chat;
import ru.techdocs.chat.ChatRepository;
import ru.techdocs.document.Document;
import ru.techdocs.document.DocumentRepository;
import ru.techdocs.equipment.EquipmentRepository;
import ru.techdocs.object.FacilityRepository;
import ru.techdocs.object.FacilityStatsService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final FacilityRepository facilityRepository;
    private final DocumentRepository documentRepository;
    private final EquipmentRepository equipmentRepository;
    private final ChatRepository chatRepository;
    private final FacilityStatsService statsService;

    @GetMapping
    public Map<String, Object> dashboard() {
        long errorCount = documentRepository.findAll().stream()
                .filter(d -> Document.STATUS_ERROR.equals(d.getStatus())
                        || Document.STATUS_NEEDS_OCR.equals(d.getStatus()))
                .count();

        List<Document> recentDocuments = documentRepository.findTop10ByOrderByCreatedAtDesc();
        List<Chat> recentChats = chatRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(5)
                .toList();

        return Map.of(
                "facilityCount", facilityRepository.count(),
                "documentCount", documentRepository.count(),
                "equipmentCount", equipmentRepository.count(),
                "errorCount", errorCount,
                "recentFacilities", statsService.withStats(
                        facilityRepository.findAllByOrderByCreatedAtDesc().stream().limit(6).toList()),
                "recentDocuments", recentDocuments,
                "recentChats", recentChats
        );
    }
}
