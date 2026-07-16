package ru.techdocs.search;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Полнотекстовый поиск по фрагментам документов (PostgreSQL tsvector, russian).
 * Ищет только по актуальным документам.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private final EntityManager entityManager;

    public record SearchHit(Long chunkId, Long documentId, String documentName,
                            String originalFilename, Integer pageFrom, Integer pageTo,
                            String content, float rank) {}

    @SuppressWarnings("unchecked")
    public List<SearchHit> search(String query, Long facilityId, Long systemId,
                                  Long documentId, int limit) {
        String sql = """
                SELECT c.id, c.document_id, d.name, d.original_filename,
                       c.page_from, c.page_to, c.content,
                       ts_rank(c.content_tsv, q.tsq) AS rank
                FROM document_chunks c
                JOIN documents d ON d.id = c.document_id,
                     -- OR-семантика: достаточно совпадения части слов, ранжирование по релевантности
                     (SELECT replace(plainto_tsquery('russian', :query)::text, ' & ', ' | ')::tsquery AS tsq) q
                WHERE c.content_tsv @@ q.tsq
                  AND d.status = 'READY'
                  AND d.actuality_status = 'ACTUAL'
                  AND (CAST(:facilityId AS bigint) IS NULL OR d.facility_id = CAST(:facilityId AS bigint))
                  AND (CAST(:systemId AS bigint) IS NULL OR d.engineering_system_id = CAST(:systemId AS bigint))
                  AND (CAST(:documentId AS bigint) IS NULL OR d.id = CAST(:documentId AS bigint))
                ORDER BY rank DESC
                LIMIT :limit
                """;

        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter("query", query)
                .setParameter("facilityId", facilityId)
                .setParameter("systemId", systemId)
                .setParameter("documentId", documentId)
                .setParameter("limit", limit)
                .getResultList();

        List<SearchHit> hits = new ArrayList<>();
        for (Object[] row : rows) {
            hits.add(new SearchHit(
                    ((Number) row[0]).longValue(),
                    ((Number) row[1]).longValue(),
                    (String) row[2],
                    (String) row[3],
                    row[4] == null ? null : ((Number) row[4]).intValue(),
                    row[5] == null ? null : ((Number) row[5]).intValue(),
                    (String) row[6],
                    ((Number) row[7]).floatValue()
            ));
        }
        return hits;
    }
}
