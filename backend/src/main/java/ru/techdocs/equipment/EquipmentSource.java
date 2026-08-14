package ru.techdocs.equipment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "equipment_sources")
@Getter
@Setter
@NoArgsConstructor
public class EquipmentSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "source_text", columnDefinition = "text")
    private String sourceText;
}
