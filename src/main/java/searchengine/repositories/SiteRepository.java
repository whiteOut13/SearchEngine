package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import searchengine.model.IndexingStatus;
import searchengine.model.SiteEntity;

import javax.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<SiteEntity, Long> {
    Optional<SiteEntity> findByUrl(String url);

    void deleteByUrl(String url);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM SiteEntity w WHERE w.url = :url")
    Optional<SiteEntity> findByUrlWithLock(@Param("url") String url);

    boolean existsByIndexingStatus(IndexingStatus indexingStatus);

    List<SiteEntity> findByIndexingStatus(IndexingStatus status);
}
