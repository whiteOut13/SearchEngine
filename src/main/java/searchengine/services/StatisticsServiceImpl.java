package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.IndexingStatus;
import searchengine.model.SiteEntity;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;

    @Override
    public StatisticsResponse getStatistics() {
        List<SiteEntity> sites = siteRepository.findAll();
        boolean indexing = sites.stream().anyMatch(s -> s.getIndexingStatus() == IndexingStatus.INDEXING);

        TotalStatistics total = new TotalStatistics();
        total.setSites(sites.size());
        total.setIndexing(indexing);
        total.setPages(pageRepository.count());
        total.setLemmas(lemmaRepository.count());

        List<DetailedStatisticsItem> detailed = sites.stream().map(site -> {
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setUrl(site.getUrl());
            item.setName(site.getName());
            item.setStatus(site.getIndexingStatus().name());
            item.setStatusTime(site.getStatusTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            if (site.getLastError() != null && !site.getLastError().isEmpty()) {
                item.setError(site.getLastError());
            }
            item.setPages(pageRepository.countBySiteId(site.getId()));
            item.setLemmas(lemmaRepository.countBySiteId(site.getId()));
            return item;
        }).collect(Collectors.toList());

        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);

        StatisticsResponse response = new StatisticsResponse();
        response.setResult(true);
        response.setStatistics(data);
        return response;
    }
}