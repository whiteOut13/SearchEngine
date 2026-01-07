package searchengine.dto.search;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SearchResponse {
    private boolean result;
    private String error;
    private int count;
    private List<SearchResultItem> data = new ArrayList<>();;
}