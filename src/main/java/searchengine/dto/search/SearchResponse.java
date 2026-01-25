package searchengine.dto.search;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchResponse {
    private boolean result;
    private String error;

    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private int count;
    
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<SearchResultItem> data = new ArrayList<>();

    public SearchResponse(boolean result, String error) {
        this.result = result;
        this.error = error;
    }

    public SearchResponse() {

    }
}