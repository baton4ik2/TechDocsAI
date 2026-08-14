package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.document.DocumentChunk;
import ru.techdocs.processing.PageText;
import ru.techdocs.processing.TextChunker;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    @Test
    void shortPageBecomesSingleChunk() {
        List<DocumentChunk> chunks = chunker.chunk(1L, List.of(new PageText(1, "Короткий текст паспорта.")));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getContent()).isEqualTo("Короткий текст паспорта.");
        assertThat(chunks.getFirst().getDocumentId()).isEqualTo(1L);
        assertThat(chunks.getFirst().getPageFrom()).isEqualTo(1);
    }

    @Test
    void blankPagesAreSkipped() {
        List<DocumentChunk> chunks = chunker.chunk(1L, List.of(
                new PageText(1, "   "), new PageText(2, ""), new PageText(3, "Есть текст")));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getPageFrom()).isEqualTo(3);
    }

    @Test
    void longPageIsSplitWithOverlap() {
        String longText = "Предложение. ".repeat(400); // ~5200 символов
        List<DocumentChunk> chunks = chunker.chunk(1L, List.of(new PageText(7, longText)));
        assertThat(chunks.size()).isGreaterThan(1);
        // все фрагменты помнят исходную страницу
        assertThat(chunks).allSatisfy(c -> assertThat(c.getPageFrom()).isEqualTo(7));
        // каждый фрагмент не превышает разумный предел
        assertThat(chunks).allSatisfy(c -> assertThat(c.getContent().length()).isLessThanOrEqualTo(2100));
    }

    @Test
    void preservesPageNumbersAcrossPages() {
        List<DocumentChunk> chunks = chunker.chunk(2L, List.of(
                new PageText(1, "Первая страница"), new PageText(5, "Пятая страница")));
        assertThat(chunks).extracting(DocumentChunk::getPageFrom).containsExactly(1, 5);
    }
}
