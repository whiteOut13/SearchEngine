package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.SearchEngineProperties;
import searchengine.config.Site;
import searchengine.model.IndexingStatus;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

@Service
@RequiredArgsConstructor
@Slf4j
public class SiteIndexer {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final SearchEngineProperties props;

    private volatile boolean running = true;
    private volatile ForkJoinPool forkJoinPool;



    protected void stop() {
        this.running = false;
    }

    private ForkJoinPool getForkJoinPool() {
        if (forkJoinPool == null) {
            synchronized (this) {
                if (forkJoinPool == null) {
                    forkJoinPool = new ForkJoinPool();
                }
            }
        }
        return forkJoinPool;
    }
    @Transactional
    public void index(Site configSite) {
        String baseUrl = normalizeUrl(configSite.getUrl());
        SiteEntity siteEntity = null;

        try {
            Optional<SiteEntity> existingOpt = siteRepository.findByUrl(baseUrl);
            if (existingOpt.isPresent()) {
                siteEntity = existingOpt.get();
                if (siteEntity.getIndexingStatus() == IndexingStatus.INDEXING) {
                    log.warn("Сайт {} уже индексируется. Пропуск.", baseUrl);
                    return;
                }
                pageRepository.deleteBySiteId(siteEntity.getId());
                siteEntity.setIndexingStatus(IndexingStatus.INDEXING);
                siteEntity.setLastError(null);
                siteEntity.setStatusTime(LocalDateTime.now());
                siteRepository.save(siteEntity);
            } else {
                siteEntity = new SiteEntity();
                siteEntity.setName(configSite.getName());
                siteEntity.setUrl(baseUrl);
                siteEntity.setIndexingStatus(IndexingStatus.INDEXING);
                siteEntity.setLastError(null);
                siteEntity.setStatusTime(LocalDateTime.now());
                siteRepository.save(siteEntity);
            }

            Set<String> visited = ConcurrentHashMap.newKeySet();
            ForkJoinPool pool = getForkJoinPool();

            try {
                RecursiveIndexTask task = new RecursiveIndexTask(siteEntity, baseUrl, visited);
                pool.invoke(task);

                if (running) {
                    siteEntity.setIndexingStatus(IndexingStatus.INDEXED);
                } else {
                    siteEntity.setIndexingStatus(IndexingStatus.FAILED);
                    siteEntity.setLastError("Индексация остановлена пользователем");
                }
            } catch (Exception e) {
                log.error("Ошибка в ForkJoinPool для сайта: " + baseUrl, e);
                siteEntity.setIndexingStatus(IndexingStatus.FAILED);
                siteEntity.setLastError("Ошибка обхода: " + e.getMessage());
            } finally {
                siteEntity.setStatusTime(LocalDateTime.now());
                siteRepository.save(siteEntity);
            }

        } catch (Exception e) {
            log.error("Критическая ошибка при индексации сайта: " + baseUrl, e);
            if (siteEntity != null) {
                updateSiteStatusOnError(siteEntity, "Критическая ошибка: " + e.getMessage());
            }
        }
    }

    private void updateSiteStatusOnError(SiteEntity siteEntity, String errorMessage) {
        if (siteEntity.getId() != null) {
            Optional<SiteEntity> existing = siteRepository.findById(siteEntity.getId());
            if (existing.isPresent()) {
                SiteEntity toUpdate = existing.get();
                toUpdate.setIndexingStatus(IndexingStatus.FAILED);
                toUpdate.setLastError(errorMessage);
                toUpdate.setStatusTime(LocalDateTime.now());
                siteRepository.save(toUpdate);
            } else {
                log.warn("SiteEntity с ID {} не найден для обновления статуса ошибки.", siteEntity.getId());
            }
        }
    }

    private String normalizeUrl(String url) {
        if (!url.endsWith("/")) {
            url += "/";
        }
        return url;
    }

    @RequiredArgsConstructor
    private class RecursiveIndexTask extends RecursiveAction {
        private final SiteEntity siteEntity;
        private final String url;
        private final Set<String> visited;

        @Override
        protected void compute() {
            if (!running || !url.startsWith(siteEntity.getUrl()) || !visited.add(url)) {
                return;
            }

            try {
                long delay = props.getDelayMinMs() +
                        (long) (Math.random() * (props.getDelayMaxMs() - props.getDelayMinMs()));
                Thread.sleep(delay);

                org.jsoup.Connection.Response response = org.jsoup.Jsoup.connect(url)
                        .userAgent(props.getUserAgent())
                        .referrer(props.getReferrer().trim())
                        .timeout(10000)
                        .followRedirects(true)
                        .execute();

                String contentType = response.contentType();
                if (contentType != null && !contentType.startsWith("text/") && !contentType.endsWith("xml")) {
                    log.warn("Пропущен не текстовый контент (MIME: {}): {}", contentType, url);
                    return;
                }

                org.jsoup.nodes.Document doc = response.parse();
                int statusCode = response.statusCode();

                PageEntity pageEntity = new searchengine.model.PageEntity();
                pageEntity.setSite(siteEntity);
                pageEntity.setCode(statusCode);
                pageEntity.setContent(doc.html());
                pageEntity.setPath(url);
                pageRepository.save(pageEntity);
                log.info("Добавление страницы для сайта: {} URL: {}", siteEntity.getName(), url);

                Elements links = doc.select("a[href]");
                List<RecursiveIndexTask> subtasks = new java.util.ArrayList<>();

                for (org.jsoup.nodes.Element link : links) {
                    String href = link.absUrl("href");
                    if (href.isEmpty() || href.contains("#") || !href.startsWith(siteEntity.getUrl())) continue;
                    if (!visited.contains(href)) {
                        subtasks.add(new RecursiveIndexTask(siteEntity, href, visited));
                    }
                }
                invokeAll(subtasks);

            } catch (UnsupportedMimeTypeException e) {
                log.warn("Не удалось обработать страницу (неподдерживаемый MIME-тип): {}", url, e);
            } catch (java.io.IOException e) {
                log.warn("Не удалось обработать страницу: " + url, e);
            } catch (InterruptedException e) {
                log.warn("Задача прервана при обработке страницы: " + url, e);
                Thread.currentThread().interrupt();
            }
        }
    }
}