package ru.techdocs.pkm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PkmOperationRepository extends JpaRepository<PkmOperation, Long> {

    List<PkmOperation> findByPkmIdOrderByPosition(Long pkmId);

    List<PkmOperation> findBySystemTypeOrderByPosition(String systemType);

    long countByPkmId(Long pkmId);

    @Modifying
    @Transactional
    void deleteByPkmId(Long pkmId);
}
