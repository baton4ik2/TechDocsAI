package ru.techdocs.chat;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "answer_sources")
@Getter
@Setter
@NoArgsConstructor
public class AnswerSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_message_id", nullable = false)
    private Long chatMessageId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "chunk_id")
    private Long chunkId;

    @Column(name = "relevance_score")
    private Float relevanceScore;
}
