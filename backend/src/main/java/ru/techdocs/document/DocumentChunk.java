package ru.techdocs.document;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "document_chunks")
@Getter
@Setter
@NoArgsConstructor
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "page_from")
    private Integer pageFrom;

    @Column(name = "page_to")
    private Integer pageTo;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(columnDefinition = "text")
    private String metadata;
}
