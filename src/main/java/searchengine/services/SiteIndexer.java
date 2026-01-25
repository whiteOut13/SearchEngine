package searchengine.services;

import java.io.IOException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.stream.Collectors;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import searchengine.config.SearchEngineProperties;
import searchengine.config.Site;
import searchengine.model.IndexEntity;
import searchengine.model.IndexingStatus;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repositories.IndexRepository;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.lemma.LemmaService;

@RequiredArgsConstructor
@Slf4j
@Service
public class SiteIndexer {

    private final SearchEngineProperties props;
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final LemmaService lemmaService;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;

    private volatile boolean running = true;
    private volatile ForkJoinPool forkJoinPool;

    public void index(Site configSite) {
        log.info("Запуск индексации");
        SiteEntity siteEntity = new SiteEntity();
        Optional<SiteEntity> sOptional = siteRepository.findByUrl(configSite.getUrl());

        if (sOptional.isPresent()) {
            siteEntity = sOptional.get();
            List<PageEntity> pages = pageRepository.findBySiteId(siteEntity.getId());
            if (!pages.isEmpty()) {
                List<Long> pageIds = pages.stream().map(PageEntity::getId).collect(Collectors.toList());
                indexRepository.deleteAllByPageIdsIn(pageIds);
                lemmaRepository.deleteAllBySiteId(siteEntity.getId());
            }

            pageRepository.deleteAllBySiteId(siteEntity.getId());

            siteEntity.setIndexingStatus(IndexingStatus.INDEXING);
            siteEntity.setLastError(null);
            siteEntity.setStatusTime(LocalDateTime.now());

            siteRepository.save(siteEntity);
        } else {
            siteEntity = new SiteEntity();
            siteEntity.setName(configSite.getName());
            siteEntity.setUrl(configSite.getUrl());
            siteEntity.setIndexingStatus(IndexingStatus.INDEXING);
            siteEntity.setLastError(null);
            siteEntity.setStatusTime(LocalDateTime.now());

            siteRepository.save(siteEntity);
        }

        Set<String> visited = ConcurrentHashMap.newKeySet();

        try {
            forkJoinPool = new ForkJoinPool();
            ParseHtml task = new ParseHtml(configSite.getUrl(), siteEntity, visited);
            forkJoinPool.invoke(task);

            if (running) {
                siteEntity.setIndexingStatus(IndexingStatus.INDEXED);
                log.info("Индексация сайта завершена: " + siteEntity.getName());
            } else {
                siteEntity.setIndexingStatus(IndexingStatus.FAILED);
                siteEntity.setLastError("Индексация остановлена пользователем");
            }

        } catch (Exception e) {
            log.error("Ошибка в ForkJoinPool для сайта: " + siteEntity.getUrl(), e);
            siteEntity.setIndexingStatus(IndexingStatus.FAILED);
            siteEntity.setLastError("Ошибка обхода: " + e.getMessage());
        } finally {
            siteEntity.setStatusTime(LocalDateTime.now());
            siteRepository.save(siteEntity);
            forkJoinPool.shutdownNow();
        }
    }

    public void stop() {
        this.running = false;
    }

    public void saveLemmasForPage(PageEntity page, Map<String, Integer> lemmaFrequencies) {
        try {
            Long siteId = page.getSite().getId();

            List<String> lemmas = new ArrayList<>(lemmaFrequencies.keySet());
            lemmas.sort(String::compareTo);

            for (String lemma : lemmas) {
                if (lemma == null) {
                    log.warn("Найдена null лемма для страницы: {}", page.getPath());
                    continue;
                }
                try {
                    lemmaRepository.upsertLemma(siteId, lemma);
                } catch (Exception upsertE) {
                    log.error("Ошибка при upsert леммы '{}' для страницы '{}': {}", lemma, page.getPath(),
                            upsertE.getMessage(), upsertE);

                    continue;
                }
            }

            for (Map.Entry<String, Integer> entry : lemmaFrequencies.entrySet()) {
                String lemma = entry.getKey();
                int rank = entry.getValue();

                if (lemma == null)
                    continue;

                try {
                    LemmaEntity lemmaEntity = lemmaRepository.findBySiteIdAndLemma(siteId, lemma)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Лемма не найдена после upsert: " + lemma + " для страницы " + page.getPath()));

                    if (!indexRepository.existsByLemmaIdAndPageId(lemmaEntity.getId(), page.getId())) {
                        IndexEntity index = new IndexEntity();
                        index.setLemma(lemmaEntity);
                        index.setPage(page);
                        index.setRank(rank);
                        indexRepository.save(index);
                    }
                } catch (Exception findOrCreateE) {
                    log.error("Ошибка при поиске/создании индекса для леммы '{}' и страницы '{}': {}", lemma,
                            page.getPath(), findOrCreateE.getMessage(), findOrCreateE);
                }
            }
        } catch (Throwable t) {
            log.error("Неожиданная ошибка в saveLemmasForPage для страницы: " + page.getPath(), t);
            throw t;
        }
    }

    @RequiredArgsConstructor
    private class ParseHtml extends RecursiveAction {
        private final String url;
        private final SiteEntity siteEntity;
        private final Set<String> visited;

        @Override
        protected void compute() {
            try {
                if (!running || !url.startsWith(siteEntity.getUrl()) || url.endsWith(".webp")) {
                    return;
                }

                log.info("Обработка URL: {}", url);

                long delay = props.getDelayMinMs() +
                        (long) (Math.random() * (props.getDelayMaxMs() - props.getDelayMinMs()));

                // Оборачиваем Thread.sleep в try-catch
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    log.warn("Sleep interrupted for URL: {}", url, e);
                    Thread.currentThread().interrupt();
                    return; // Прерываем выполнение задачи, если сон был прерван
                }

                Connection.Response response = Jsoup.connect(url)
                        .userAgent(props.getUserAgent())
                        .referrer(props.getReferrer().trim())
                        .timeout(10000)
                        .followRedirects(true)
                        .execute();

                String contentType = response.contentType();

                if (contentType != null) {
                    String lowerContentType = contentType.toLowerCase();
                    if (!lowerContentType.startsWith("text/") &&
                            !lowerContentType.contains("xml") &&
                            !lowerContentType.contains("html") &&
                            !lowerContentType.contains("javascript") &&
                            !lowerContentType.contains("json")) {
                        log.warn("Пропущен не текстовый контент (MIME: {}): {}", contentType, url);
                        return;
                    }
                }

                Document doc = response.parse();
                int statusCode = response.statusCode();

                PageEntity pageEntity = new PageEntity();
                pageEntity.setSite(siteEntity);
                pageEntity.setCode(statusCode);
                pageEntity.setContent(doc.html());
                pageEntity.setPath(url);

                pageRepository.save(pageEntity);

                // Лемматизация и сохранение
                String text = lemmaService.extractText(doc.html());
                Map<String, Integer> lemmas = lemmaService.getLemmas(text);
                saveLemmasForPage(pageEntity, lemmas);

                Elements links = doc.select("a[href]");
                List<ParseHtml> subtasks = new ArrayList<>();

                for (Element link : links) {
                    String href = link.absUrl("href");
                    if (href.isEmpty() || href.contains("#") || !href.startsWith(siteEntity.getUrl())
                            || visited.contains(href))
                        continue;

                    visited.add(href);
                    subtasks.add(new ParseHtml(href, siteEntity, visited));
                }

                List<ForkJoinTask<Void>> forkedTasks = new ArrayList<>();

                for (ParseHtml subtask : subtasks) {
                    subtask.fork();
                    forkedTasks.add(subtask);
                }

                for (ForkJoinTask<Void> forkedTask : forkedTasks) {
                    try {
                        forkedTask.join();
                    } catch (RuntimeException e) {
                        log.warn("Подзадача завершена с ошибкой: {}", e.getMessage(), e);
                    }
                }

            } catch (IOException e) {
                log.warn("Не удалось обработать страницу: " + url, e);
            } catch (Throwable t) {
                log.error("Непредвиденная ошибка при обработке задачи для URL: " + this.url, t);
            }
        }
    }

}
