package ru.techdocs.normative;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface NormativeRateRepository extends JpaRepository<NormativeRate, Long> {

    Optional<NormativeRate> findByCode(String code);

    /** Одна расценка по шифру (при дублях шифра берём первую по id). */
    Optional<NormativeRate> findFirstByCodeOrderById(String code);

    List<NormativeRate> findBySourcebookIdOrderByCode(Long sourcebookId);

    long countBySourcebookId(Long sourcebookId);

    @Modifying
    @Transactional
    void deleteBySourcebookId(Long sourcebookId);

    /**
     * Полнотекстовый поиск расценок по наименованию/составу работ.
     * OR-семантика: достаточно совпадения части слов, ранжирование по релевантности.
     */
    @Query(value = """
            SELECT r.* FROM normative_rates r,
                 (SELECT replace(plainto_tsquery('russian', :query)::text, ' & ', ' | ')::tsquery AS tsq) q
            WHERE r.search_tsv @@ q.tsq
            ORDER BY ts_rank(r.search_tsv, q.tsq, 1) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<NormativeRate> search(@Param("query") String query, @Param("limit") int limit);

    /** Поиск по шифру: полнотекст разбивает «22-2203-128-1/1» на числа-токены и
     *  выдаёт мусор, поэтому шифр ищем по префиксу кода. */
    @Query(value = "SELECT * FROM normative_rates WHERE code LIKE :pattern ORDER BY code LIMIT :limit",
            nativeQuery = true)
    List<NormativeRate> searchByCode(@Param("pattern") String pattern, @Param("limit") int limit);
}
