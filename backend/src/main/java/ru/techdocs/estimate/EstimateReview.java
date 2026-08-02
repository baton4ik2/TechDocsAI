package ru.techdocs.estimate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Сохранённый результат проверки сметы. Прогон стоит денег и времени, поэтому
 * замечания живут рядом со сметой, а не в браузере: обновление страницы их не
 * теряет. Одна запись на смету — новый прогон заменяет предыдущий.
 */
@Entity
@Table(name = "estimate_reviews")
@Getter
@Setter
@NoArgsConstructor
public class EstimateReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "estimate_id", nullable = false, unique = true)
    private Long estimateId;

    /** Какой моделью получен результат — чтобы сравнивать проверки между собой. */
    @Column(columnDefinition = "text")
    private String model;

    /** Замечания в JSON — форма ответа модели меняется вместе с промптом. */
    @Column(columnDefinition = "text")
    private String findings;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
