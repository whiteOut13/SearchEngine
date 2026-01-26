package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import searchengine.config.SearchEngineProperties;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.Indexing.IndexingResponse;
import searchengine.exceptions.IndexingAlreadyStartedException;
import searchengine.exceptions.IndexingNotStartedException;
import searchengine.exceptions.PageOutsideSitesException;
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

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.transaction.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingServiceImpl implements IndexingService {

    private final SitesList sitesList;
    private final SiteIndexer siteIndexer;
    private final LemmaService lemmaService;
    private final SearchEngineProperties props;

    private final PageRepository pageRepository;
    private final IndexRepository indexRepository;
    private final LemmaRepository lemmaRepository;
    private final SiteRepository siteRepository;

    private ExecutorService executorService;
    private volatile boolean isIndexing = false;

    @Override
    public IndexingResponse startIndexing() {

        synchronized (this) {
            if (isIndexing) {
                throw new IndexingAlreadyStartedException("Индексация уже запущена");
            }
            isIndexing = true;
        }

        int numberOfCores = Runtime.getRuntime().availableProcessors();
        executorService = Executors.newFixedThreadPool(numberOfCores);

        for (Site site : sitesList.getSites()) {
            executorService.submit(() -> siteIndexer.index(site));
        }
        executorService.shutdown();

        return new IndexingResponse(true);
    }

    @Override
    public IndexingResponse stopIndexing() {
        synchronized (this) {
            if (!isIndexing) {
                log.info("Индексация не запущена");
                throw new IndexingNotStartedException("Индексация не запущена");
            }

            siteIndexer.stop();
            log.info("Остановка индексации");
            isIndexing = false;

            executorService.shutdown();
            return new IndexingResponse(true);
        }
    }

    @Override
    @Transactional
    public IndexingResponse indexSinglePage(String url) {
        log.info("Запуск переиндексации: " + url);

        if (url == null || url.isBlank()) {
            return new IndexingResponse(false, "URL не может быть пустым");
        }

        // Проверка: URL должен принадлежать одному из сайтов
        Site allowedConfigSite = sitesList.getSites().stream()
                .filter(s -> url.startsWith(s.getUrl()))
                .findFirst()
                .orElse(null);

        if (allowedConfigSite == null) {
            throw new PageOutsideSitesException(
                "Данная страница находится за пределами сайтов, указанных в конфигурационном файле"
            );
        }

        try {
            Connection.Response response = Jsoup.connect(url)
                    .userAgent(props.getUserAgent())
                    .referrer(props.getReferrer().trim())
                    .timeout(10000)
                    .followRedirects(true)
                    .execute();

            if (response.statusCode() >= 400) {
                return new IndexingResponse(false, "Страница недоступна (HTTP " + response.statusCode() + ")");
            }

            Document doc = response.parse();

            String siteUrl = allowedConfigSite.getUrl();
            SiteEntity site = siteRepository.findByUrl(siteUrl)
                    .orElseGet(() -> {
                        SiteEntity s = new SiteEntity();
                        s.setUrl(siteUrl);
                        s.setName(allowedConfigSite.getName());
                        s.setIndexingStatus(IndexingStatus.INDEXED);
                        s.setStatusTime(LocalDateTime.now());
                        return siteRepository.save(s);
                    });

            Optional<PageEntity> existingPage = pageRepository.findByPath(url);
            if (existingPage.isPresent()) {
                deletePageAndItsLemmas(existingPage.get());
            }

            PageEntity page = new PageEntity();
            page.setSite(site);
            page.setPath(url);
            page.setCode(response.statusCode());
            page.setContent(doc.html());
            page = pageRepository.save(page);

            String text = lemmaService.extractText(doc.html());
            Map<String, Integer> lemmas = lemmaService.getLemmas(text);
            saveLemmasForPage(page, lemmas);

            log.info("Страница переиндексирована: " + url);
            return new IndexingResponse(true, null);

        } catch (IOException e) {
            return new IndexingResponse(false, "Не удалось загрузить страницу: " + e.getMessage());
        }
    }

    @Transactional
    public void deletePageAndItsLemmas(PageEntity page) {
        // Удаляем связи в index
        indexRepository.deleteByPageId(page.getId());

        // Уменьшаем frequency для лемм
        List<LemmaEntity> lemmas = lemmaRepository.findBySiteId(page.getSite().getId());
        for (LemmaEntity lemma : lemmas) {
            long count = indexRepository.countByLemmaId(lemma.getId());
            if (count == 0) {
                lemmaRepository.delete(lemma);
            } else {
                lemma.setFrequency((int) count);
                lemmaRepository.save(lemma);
            }
        }

        // Удаляем саму страницу
        pageRepository.delete(page);
    }

    public void saveLemmasForPage(PageEntity page, Map<String, Integer> lemmaFrequencies) {
        Long siteId = page.getSite().getId();

        // 1. Обновляем frequency для всех лемм
        List<String> lemmas = new ArrayList<>(lemmaFrequencies.keySet());
        lemmas.sort(String::compareTo);

        for (String lemma : lemmas) {
            lemmaRepository.upsertLemma(siteId, lemma);
        }

        // 2. Получаем леммы и создаём связи
        for (Map.Entry<String, Integer> entry : lemmaFrequencies.entrySet()) {
            String lemma = entry.getKey();
            int rank = entry.getValue();

            LemmaEntity lemmaEntity = lemmaRepository.findBySiteIdAndLemma(siteId, lemma)
                    .orElseThrow(() -> new IllegalStateException("Лемма не найдена после upsert: " + lemma));

            if (!indexRepository.existsByLemmaIdAndPageId(lemmaEntity.getId(), page.getId())) {
                IndexEntity index = new IndexEntity();
                index.setLemma(lemmaEntity);
                index.setPage(page);
                index.setRank(rank);
                indexRepository.save(index);
            }
        }
    }
}
