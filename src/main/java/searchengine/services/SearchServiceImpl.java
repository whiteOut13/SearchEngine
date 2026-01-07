package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import searchengine.dto.search.SearchResponse;
import searchengine.dto.search.SearchResultItem;
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

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SearchServiceImpl implements SearchService {

    private final LemmaService lemmaService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;

    private static final int MAX_LEMMA_FREQUENCY_PERCENT = 70; // порог популярности леммы

    @Override
    public SearchResponse search(String query, String siteUrl, int offset, int limit) {
        SearchResponse response = new SearchResponse();

        if (query == null || query.trim().isEmpty()) {
            response.setResult(false);
            response.setError("Задан пустой поисковый запрос");
            return response;
        }

        // Проверка: есть ли хотя бы один проиндексированный сайт?
        boolean hasIndexed = siteRepository.existsByIndexingStatus(IndexingStatus.INDEXED);
        if (!hasIndexed) {
            response.setResult(false);
            response.setError("Нет проиндексированных сайтов");
            return response;
        }

        // Получаем леммы запроса
        Map<String, Integer> queryLemmasMap = lemmaService.getLemmas(query);
        if (queryLemmasMap.isEmpty()) {
            response.setResult(false);
            response.setError("Не найдено подходящих слов для поиска");
            return response;
        }

        List<String> queryLemmas = new ArrayList<>(queryLemmasMap.keySet());

        // Определяем, по каким сайтам искать
        List<SiteEntity> sitesToSearch;
        if (siteUrl != null && !siteUrl.isEmpty()) {
            String normalizedUrl = normalizeUrl(siteUrl);
            SiteEntity site = siteRepository.findByUrl(normalizedUrl)
                    .orElse(null);
            if (site == null || site.getIndexingStatus() != IndexingStatus.INDEXED) {
                response.setResult(false);
                response.setError("Сайт не проиндексирован или не найден");
                return response;
            }
            sitesToSearch = List.of(site);
        } else {
            sitesToSearch = siteRepository.findByIndexingStatus(IndexingStatus.INDEXED);
        }

        List<Long> siteIds = sitesToSearch.stream().map(SiteEntity::getId).collect(Collectors.toList());

        // Фильтруем леммы по частоте
        List<LemmaEntity> validLemmas = lemmaRepository.findByLemmaInAndSiteIdIn(queryLemmas, siteIds);
        if (validLemmas.isEmpty()) {
            return createEmptyResponse();
        }

        // Считаем общий порог популярности
        long totalPages = pageRepository.countBySiteIdIn(siteIds);
        int maxFreq = (int) (totalPages * (MAX_LEMMA_FREQUENCY_PERCENT / 100.0));

        // Фильтруем и сортируем по частоте (от редких к частым)
        List<LemmaEntity> filteredLemmas = validLemmas.stream()
                .filter(l -> l.getFrequency() <= maxFreq)
                .sorted(Comparator.comparingInt(LemmaEntity::getFrequency))
                .collect(Collectors.toList());

        if (filteredLemmas.isEmpty()) {
            return createEmptyResponse();
        }

        // Поиск страниц по первой (самой редкой) лемме
        List<Long> pageIds = indexRepository.findPageIdsByLemmaId(filteredLemmas.get(0).getId());

        // Пересечение с остальными леммами
        for (int i = 1; i < filteredLemmas.size(); i++) {
            List<Long> nextPageIds = indexRepository.findPageIdsByLemmaId(filteredLemmas.get(i).getId());
            pageIds.retainAll(nextPageIds);
            if (pageIds.isEmpty()) break;
        }

        if (pageIds.isEmpty()) {
            return createEmptyResponse();
        }

        // Получаем страницы
        List<PageEntity> pages = pageRepository.findAllById(pageIds);
        if (pages.isEmpty()) {
            return createEmptyResponse();
        }

        // Группируем rank по страницам
        Map<Long, Float> pageRelevance = new HashMap<>();
        for (Long pageId : pageIds) {
            List<IndexEntity> indexes = indexRepository.findByPageIdAndLemmaIdIn(pageId,
                    filteredLemmas.stream().map(LemmaEntity::getId).collect(Collectors.toList()));
            double totalRank = indexes.stream().mapToDouble(IndexEntity::getRank).sum();
            pageRelevance.put(pageId, (float) totalRank);
        }

        // Нормализация релевантности
        float maxRelevance = Collections.max(pageRelevance.values());
        Map<Long, Float> normalizedRelevance = new HashMap<>();
        for (Map.Entry<Long, Float> entry : pageRelevance.entrySet()) {
            normalizedRelevance.put(entry.getKey(), entry.getValue() / maxRelevance);
        }

        // Формируем результат
        List<SearchResultItem> items = new ArrayList<>();
        for (PageEntity page : pages) {
            Float rel = normalizedRelevance.get(page.getId());
            if (rel == null) continue;

            String title = extractTitle(page.getContent());
            String snippet = extractSnippet(page.getContent(), queryLemmasMap.keySet());

            SearchResultItem item = new SearchResultItem();
            item.setSite(page.getSite().getUrl());
            item.setSiteName(page.getSite().getName());
            item.setUri(page.getPath().replaceFirst("^https?://[^/]+", ""));
            item.setTitle(title);
            item.setSnippet(snippet);
            item.setRelevance(rel);
            items.add(item);
        }

        // Сортировка по релевантности (убывание)
        items.sort((a, b) -> Float.compare(b.getRelevance(), a.getRelevance()));


        int totalCount = items.size();
        List<SearchResultItem> paginated = items.stream()
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());

        response.setResult(true);
        response.setCount(totalCount);
        response.setData(paginated);
        return response;
    }

    private String normalizeUrl(String url) {
        if (!url.endsWith("/")) url += "/";
        return url;
    }

    private SearchResponse createEmptyResponse() {
        SearchResponse r = new SearchResponse();
        r.setResult(true);
        r.setCount(0);
        r.setData(Collections.emptyList());
        return r;
    }

    private String extractTitle(String html) {
        try {
            Document doc = Jsoup.parse(html);
            String title = doc.title();
            return title.isEmpty() ? "Без заголовка" : title;
        } catch (Exception e) {
            return "Без заголовка";
        }
    }

    private String extractSnippet(String html, Set<String> lemmas) {
        try {
            String text = Jsoup.parse(html).text();
            if (text.isEmpty()) return "...";

            // Находим первое вхождение любого слова из запроса (не леммы!)
            String lowerText = text.toLowerCase();
            int startPos = -1;
            for (String lemma : lemmas) {
                int pos = lowerText.indexOf(lemma.toLowerCase());
                if (pos != -1 && (startPos == -1 || pos < startPos)) {
                    startPos = pos;
                }
            }

            if (startPos == -1) {
                startPos = 0;
            }

            int snippetStart = Math.max(0, startPos - 50);
            int snippetEnd = Math.min(text.length(), startPos + 150);
            String snippet = text.substring(snippetStart, snippetEnd);


            for (String lemma : lemmas) {
                snippet = snippet.replaceAll(
                        "(?i)(" + java.util.regex.Pattern.quote(lemma) + ")",
                        "<b>$1</b>"
                );
            }

            return snippet.trim();
        } catch (Exception e) {
            return "Текст недоступен";
        }
    }
}