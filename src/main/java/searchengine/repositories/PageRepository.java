package searchengine.repositories;

import java.util.List;
import java.util.Optional;

import javax.transaction.Transactional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import searchengine.model.PageEntity;

public interface PageRepository extends JpaRepository<PageEntity, Long> {
    @Modifying
    @Transactional
    void deleteAllBySiteId(Long siteId);

    @Query("SELECT p FROM PageEntity p WHERE p.site.id = :siteId")
    List<PageEntity> findBySiteId(@Param("siteId") Long siteId);

    Optional<PageEntity> findByPath(String path);

    long countBySiteId(Long siteId);
    
    long countBySiteIdIn(List<Long> siteIds);
}