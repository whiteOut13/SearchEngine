package searchengine.services;

import searchengine.dto.Indexing.IndexingResponse;

public interface IndexingService {
    IndexingResponse startIndexing();
    IndexingResponse stopIndexing();
    IndexingResponse indexSinglePage(String url);
}
