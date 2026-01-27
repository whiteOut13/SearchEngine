package searchengine.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import searchengine.model.LemmaEntity;

public interface LemmaRepository extends JpaRepository<LemmaEntity, Long> {
    @Modifying
    // @Transactional
    void deleteAllBySiteId(Long siteId);

    List<LemmaEntity> findBySiteId(Long id);

    Optional<LemmaEntity> findBySiteIdAndLemma(Long id, String lemmaStr);

    long countBySiteId(Long id);

    List<LemmaEntity> findByLemmaInAndSiteIdIn(List<String> lemmas, List<Long> siteIds);

    @Modifying
    // @Transactional
    @Query(value = """
            INSERT INTO lemma (site_id, lemma, frequency)
            VALUES (:siteId, :lemma, 1)
            ON CONFLICT (site_id, lemma) DO UPDATE SET frequency = lemma.frequency + 1
            """, nativeQuery = true)
    void upsertLemma(@Param("siteId") Long siteId, @Param("lemma") String lemma);
}
