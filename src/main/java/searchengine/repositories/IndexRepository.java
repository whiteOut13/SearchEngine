package searchengine.repositories;

import java.util.List;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import searchengine.model.IndexEntity;

public interface IndexRepository extends JpaRepository<IndexEntity, Long> {
    void deleteByPageId(Long pageId);

    long countByLemmaId(Long lemmaId);

    boolean existsByLemmaIdAndPageId(Long lemmaId, Long pageId);

    @Query("SELECT ie.page.id FROM IndexEntity ie WHERE ie.lemma.id = :lemmaId")
    List<Long> findPageIdsByLemmaId(@Param("lemmaId") Long lemmaId);

    List<IndexEntity> findByPageIdAndLemmaIdIn(Long pageId, List<Long> lemmaIds);

    @Modifying
    // @Transactional
    void deleteAllByPageId(Long pageId);

    @Modifying
    // @Transactional
    @Query("DELETE FROM IndexEntity ie WHERE ie.page.id IN :pageIds")
    void deleteAllByPageIdsIn(@Param("pageIds") List<Long> pageIds);
}