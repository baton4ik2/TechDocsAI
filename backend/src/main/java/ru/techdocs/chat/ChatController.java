package ru.techdocs.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.techdocs.auth.User;
import ru.techdocs.common.NotFoundException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatController {

    private final ChatRepository chatRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatService chatService;

    public record CreateChatRequest(Long facilityId, Long engineeringSystemId, Long documentId, String title) {}

    public record MessageDto(Long id, String role, String content, Instant createdAt,
                             List<ChatService.SourceDto> sources) {}

    @PostMapping
    public Chat create(@AuthenticationPrincipal User user, @RequestBody CreateChatRequest request) {
        Chat chat = new Chat();
        chat.setUserId(user.getId());
        chat.setFacilityId(request.facilityId());
        chat.setEngineeringSystemId(request.engineeringSystemId());
        chat.setDocumentId(request.documentId());
        chat.setTitle(request.title() != null ? request.title() : "Новый диалог");
        return chatRepository.save(chat);
    }

    @GetMapping
    public List<Chat> list(@AuthenticationPrincipal User user) {
        return chatRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @GetMapping("/{id}/messages")
    public List<MessageDto> messages(@PathVariable Long id) {
        chatRepository.findById(id).orElseThrow(() -> new NotFoundException("Диалог не найден"));
        return messageRepository.findByChatIdOrderByCreatedAt(id).stream()
                .map(m -> new MessageDto(m.getId(), m.getRole(), m.getContent(), m.getCreatedAt(),
                        "assistant".equals(m.getRole()) ? chatService.sourcesFor(m.getId()) : List.of()))
                .toList();
    }

    public record AskRequest(@NotBlank String question) {}

    @PostMapping("/{id}/messages")
    public MessageDto ask(@PathVariable Long id, @Valid @RequestBody AskRequest request) {
        Chat chat = chatRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Диалог не найден"));

        if ((chat.getTitle() == null || "Новый диалог".equals(chat.getTitle()))) {
            String title = request.question().length() > 80
                    ? request.question().substring(0, 80) + "…" : request.question();
            chat.setTitle(title);
            chatRepository.save(chat);
        }

        ChatService.AnswerResult result = chatService.ask(chat, request.question());
        return new MessageDto(result.message().getId(), result.message().getRole(),
                result.message().getContent(), result.message().getCreatedAt(), result.sources());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        chatRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
