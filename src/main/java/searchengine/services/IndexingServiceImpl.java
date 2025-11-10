package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.Indexing.IndexingResponse;
import searchengine.model.IndexingStatus;
import searchengine.model.SiteEntity;
import searchengine.repositories.SiteRepository;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingServiceImpl implements IndexingService {

    private final SitesList sitesList;
    private final SiteIndexer siteIndexer;
    private final SiteRepository siteRepository;
    private ExecutorService executorService;
    private volatile boolean isIndexing = false;

    @Override
    public IndexingResponse startIndexing() {

        synchronized (this) {
            if (isIndexing) {
                return new IndexingResponse(false, "Индексация уже запущена");
            }
            isIndexing = true;
        }

        executorService = Executors.newFixedThreadPool(sitesList.getSites().size());
        for (Site site : sitesList.getSites()) {
            executorService.submit(() -> siteIndexer.index(site));
        }
        executorService.shutdown();

        return new IndexingResponse(true, null);
    }

    @Override
    public IndexingResponse stopIndexing() {
        synchronized (this) {
            if (!isIndexing) {
                return new IndexingResponse(false, "Индексация не запущена");
            }
            siteIndexer.stop();
            log.info("Остановка индексации");
            isIndexing = false;

            for (Site site : sitesList.getSites()) {
                Optional<SiteEntity> siteOpt = siteRepository.findByUrl(site.getUrl());
                if (siteOpt.isEmpty()) continue;
                SiteEntity siteEntity = siteOpt.get();
                if (siteEntity.getIndexingStatus() == IndexingStatus.INDEXING) {
                    siteEntity.setIndexingStatus(IndexingStatus.FAILED);
                    siteRepository.save(siteEntity);
                }
            }
            executorService.shutdownNow();
            log.info("Код работает? ");
            return new IndexingResponse(true);

        }
    }

}
