package searchengine.services;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    private final EntityManager entityManager;

    private volatile boolean running = true;

    // Подкласс для результата парсинга одной страницы
    private static class ParseResult {
        private final List<PageEntity> pages;
        private final List<RawIndexData> rawIndexes;

        public ParseResult(List<PageEntity> pages, List<RawIndexData> rawIndexes) {
            this.pages = pages;
            this.rawIndexes = rawIndexes;
        }

        public List<PageEntity> getPages() {
            return pages;
        }

        public List<RawIndexData> getRawIndexes() {
            return rawIndexes;
        }
    }

    // Подкласс для временного хранения связи индекса
    private static class RawIndexData {
        private final PageEntity page;
        private final String lemmaText;
        private final int rank;

        public RawIndexData(PageEntity page, String lemmaText, int rank) {
            this.page = page;
            this.lemmaText = lemmaText;
            this.rank = rank;
        }

        public PageEntity getPage() {
            return page;
        }

        public String getLemmaText() {
            return lemmaText;
        }

        public int getRank() {
            return rank;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateSiteStatus(Long siteId, IndexingStatus status, String errorMessage) {
        Optional<SiteEntity> siteOpt = siteRepository.findById(siteId);
        if (siteOpt.isPresent()) {
            SiteEntity site = siteOpt.get();
            site.setIndexingStatus(status);
            site.setLastError(errorMessage);
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);
            entityManager.flush();
            entityManager.clear(); 
            log.info("Статус сайта обновлён в отдельной транзакции: ID={}, Status={}", siteId, status);
        }
    }

    @Transactional
    public void index(Site configSite) {
        log.info("Запуск индексации: {}", configSite.getUrl());

        this.running = true;

        SiteEntity siteEntity = new SiteEntity();
        Optional<SiteEntity> sOptional = siteRepository.findByUrl(configSite.getUrl());

        if (sOptional.isPresent()) {
            log.info("Сайт уже существует, обновляется сайт.");
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
            log.info("Статус INDEXING установлен и сохранён (UPDATE) для существующего сайта: {}", siteEntity.getId());
        } else {
            log.info("Сайт новый, создаём...");
            siteEntity = new SiteEntity();
            siteEntity.setName(configSite.getName());
            siteEntity.setUrl(configSite.getUrl());
            siteEntity.setIndexingStatus(IndexingStatus.INDEXING);
            siteEntity.setLastError(null);
            siteEntity.setStatusTime(LocalDateTime.now());
            siteRepository.save(siteEntity);
            log.info("Статус INDEXING установлен и сохранён (INSERT) для нового сайта: {}", siteEntity.getId());
        }

        updateSiteStatus(siteEntity.getId(), IndexingStatus.INDEXING, null);

        Set<String> visited = ConcurrentHashMap.newKeySet();

        ForkJoinPool localPool = new ForkJoinPool();
        try {
            ParseHtml task = new ParseHtml(configSite.getUrl(), siteEntity, visited);
            List<ParseResult> results = localPool.invoke(new InvokeAllTask(List.of(task)));

            if (running) {
                List<PageEntity> allPages = new ArrayList<>();
                List<RawIndexData> allRawIndexes = new ArrayList<>();
                for (ParseResult result : results) {
                    allPages.addAll(result.getPages());
                    allRawIndexes.addAll(result.getRawIndexes());
                }

                List<PageEntity> savedPages = pageRepository.saveAll(allPages);

                Map<Long, PageEntity> pageIdToEntityMap = savedPages.stream()
                        .collect(Collectors.toMap(PageEntity::getId, p -> p));

                saveLemmasAndIndexes(siteEntity.getId(), allRawIndexes, pageIdToEntityMap);

                updateSiteStatus(siteEntity.getId(), IndexingStatus.INDEXED, null);
                log.info("Индексация сайта завершена: " + siteEntity.getName());
            } else {
                updateSiteStatus(siteEntity.getId(), IndexingStatus.FAILED, "Индексация остановлена пользователем");
            }

        } catch (Exception e) {
            log.error("Ошибка в ForkJoinPool для сайта: " + siteEntity.getUrl(), e);
            updateSiteStatus(siteEntity.getId(), IndexingStatus.FAILED, "Ошибка обхода: " + e.getMessage());
        } finally {
            localPool.shutdown();
        }
    }

    public void stop() {
        this.running = false;
    }

    @Transactional
    public void saveLemmasAndIndexes(Long siteId, List<RawIndexData> rawIndexes,
            Map<Long, PageEntity> pageIdToEntityMap) {
        log.info("Начало сохранения лемм и индексов");

        // 1. Извлекаем уникальные леммы из rawIndexes
        Set<String> uniqueLemmas = rawIndexes.stream()
                .map(RawIndexData::getLemmaText)
                .collect(Collectors.toSet());

        // 2. Upsert всех уникальных лемм
        for (String lemmaText : uniqueLemmas) {
            lemmaRepository.upsertLemma(siteId, lemmaText);
        }

        // 3. Находим все обновлённые/созданные леммы
        List<LemmaEntity> allLemmas = lemmaRepository.findBySiteId(siteId);

        // 4. Создаём мапу текст леммы -> объект LemmaEntity
        Map<String, LemmaEntity> lemmaTextToEntityMap = allLemmas.stream()
                .collect(Collectors.toMap(LemmaEntity::getLemma, l -> l));

        // 5. Создаём и сохраняем IndexEntity
        List<IndexEntity> indexesToSave = new ArrayList<>();
        for (RawIndexData rawData : rawIndexes) {
            LemmaEntity lemmaEntity = lemmaTextToEntityMap.get(rawData.getLemmaText());
            PageEntity pageEntity = pageIdToEntityMap.get(rawData.getPage().getId());

            if (lemmaEntity != null && pageEntity != null) {
                if (!indexRepository.existsByLemmaIdAndPageId(lemmaEntity.getId(), pageEntity.getId())) {
                    IndexEntity index = new IndexEntity();
                    index.setLemma(lemmaEntity);
                    index.setPage(pageEntity);
                    index.setRank(rawData.getRank());
                    indexesToSave.add(index);
                }
            } else {
                log.warn("Не найдена лемма '{}' или страница '{}' для создания индекса.", rawData.getLemmaText(),
                        rawData.getPage().getPath());
            }
        }

        indexRepository.saveAll(indexesToSave);
        log.info("Сохранение лемм и индексов завершено");
    }

    // Вспомогательная задача для выполнения нескольких задач параллельно
    private static class InvokeAllTask extends RecursiveTask<List<ParseResult>> {
        private final List<ParseHtml> tasks;

        public InvokeAllTask(List<ParseHtml> tasks) {
            this.tasks = tasks;
        }

        @Override
        protected List<ParseResult> compute() {
            if (tasks.size() <= 1) {
                ParseHtml task = tasks.get(0);
                task.fork();
                return List.of(task.join());
            } else {
                int mid = tasks.size() / 2;
                List<ParseHtml> leftTasks = tasks.subList(0, mid);
                List<ParseHtml> rightTasks = tasks.subList(mid, tasks.size());

                InvokeAllTask leftTask = new InvokeAllTask(leftTasks);
                InvokeAllTask rightTask = new InvokeAllTask(rightTasks);

                rightTask.fork();
                List<ParseResult> leftResults = leftTask.compute();
                List<ParseResult> rightResults = rightTask.join();

                List<ParseResult> combinedResults = new ArrayList<>();
                combinedResults.addAll(leftResults);
                combinedResults.addAll(rightResults);
                return combinedResults;
            }
        }
    }

    @RequiredArgsConstructor
    private class ParseHtml extends RecursiveTask<ParseResult> {
        private final String url;
        private final SiteEntity siteEntity;
        private final Set<String> visited;

        @Override
        protected ParseResult compute() {
            try {
                if (!running || !url.startsWith(siteEntity.getUrl())) {
                    return new ParseResult(new ArrayList<>(), new ArrayList<>());
                }

                log.info("Обработка URL: {}", url);

                long delay = props.getDelayMinMs() +
                        (long) (Math.random() * (props.getDelayMaxMs() - props.getDelayMinMs()));
                Thread.sleep(delay);

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
                        return new ParseResult(new ArrayList<>(), new ArrayList<>());
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

                String text = lemmaService.extractText(doc.html());
                Map<String, Integer> lemmas = lemmaService.getLemmas(text);

                List<PageEntity> pages = List.of(pageEntity);
                List<RawIndexData> rawIndexes = lemmas.entrySet().stream()
                        .map(entry -> new RawIndexData(pageEntity, entry.getKey(), entry.getValue()))
                        .collect(Collectors.toList());

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

                invokeAll(subtasks);
                List<ParseResult> subResults = subtasks.stream()
                        .map(ParseHtml::join)
                        .collect(Collectors.toList());

                List<PageEntity> allPages = new ArrayList<>(pages);
                List<RawIndexData> allRawIndexes = new ArrayList<>(rawIndexes);

                for (ParseResult subResult : subResults) {
                    allPages.addAll(subResult.getPages());
                    allRawIndexes.addAll(subResult.getRawIndexes());
                }

                return new ParseResult(allPages, allRawIndexes);

            } catch (org.jsoup.UnsupportedMimeTypeException e) {
                log.warn("Пропущен URL с неподдерживаемым MIME-типом ({}): {}", e.getMimeType(), url);
                return new ParseResult(new ArrayList<>(), new ArrayList<>());
            } catch (InterruptedException e) {
                log.warn("Задача прервана при обработке страницы: " + url, e);
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (IOException e) {
                log.warn("Не удалось обработать страницу: " + url, e);
                return new ParseResult(new ArrayList<>(), new ArrayList<>());
            } catch (Throwable t) {
                log.error("Непредвиденная ошибка при обработке задачи для URL: " + this.url, t);
                return new ParseResult(new ArrayList<>(), new ArrayList<>());
            }
        }
    }
}