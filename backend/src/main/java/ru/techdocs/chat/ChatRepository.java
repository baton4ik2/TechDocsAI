package ru.techdocs.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatRepository extends JpaRepository<Chat, Long> {
    List<Chat> findByUserIdOrderByCreatedAtDesc(Long userId);
}
