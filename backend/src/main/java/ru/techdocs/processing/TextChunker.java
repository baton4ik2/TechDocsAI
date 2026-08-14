package ru.techdocs.processing;

import org.springframework.stereotype.Service;
import ru.techdocs.document.DocumentChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Разбиение текста страниц на фрагменты для поиска.
 * Размер ~2000 символов с перекрытием 250 (примерно 500-700 токенов).
 */
@Service
public class TextChunker {

    private static final int CHUNK_SIZE = 2000;
    private static final int OVERLAP = 250;

    public List<DocumentChunk> chunk(Long documentId, List<PageText> pages) {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (PageText page : pages) {
            String text = page.text();
            if (text == null || text.isBlank()) continue;

            if (text.length() <= CHUNK_SIZE) {
                chunks.add(createChunk(documentId, page.pageNumber(), text));
                continue;
            }
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + CHUNK_SIZE, text.length());
                // стараемся резать по границе строки или предложения
                if (end < text.length()) {
                    int newline = text.lastIndexOf('\n', end);
                    int dot = text.lastIndexOf(". ", end);
                    int cut = Math.max(newline, dot);
                    if (cut > start + CHUNK_SIZE / 2) {
                        end = cut + 1;
                    }
                }
                chunks.add(createChunk(documentId, page.pageNumber(), text.substring(start, end).strip()));
                if (end >= text.length()) break;
                start = Math.max(end - OVERLAP, start + 1);
            }
        }
        return chunks;
    }

    private DocumentChunk createChunk(Long documentId, int page, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocumentId(documentId);
        chunk.setPageFrom(page);
        chunk.setPageTo(page);
        chunk.setContent(content);
        return chunk;
    }
}
