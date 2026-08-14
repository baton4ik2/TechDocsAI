package ru.techdocs.document;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "document_pages")
@Getter
@Setter
@NoArgsConstructor
public class DocumentPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "page_number", nullable = false)
    private int pageNumber;

    @Column(columnDefinition = "text")
    private String text;

    @Column(name = "ocr_used", nullable = false)
    private boolean ocrUsed = false;
}
