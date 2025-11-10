package searchengine.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.PageEntity;

public interface PageRepository extends JpaRepository<PageEntity, Long> {
    void deleteBySiteId(Long siteId);
}
