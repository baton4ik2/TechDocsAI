package ru.techdocs.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnswerSourceRepository extends JpaRepository<AnswerSource, Long> {
    List<AnswerSource> findByChatMessageId(Long chatMessageId);
}
