package searchengine.dto.Indexing;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;


@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IndexingResponse {
    private boolean result;
    private String error;

    public IndexingResponse(boolean result, String error) {
        this.result = result;
        this.error = error;
    }

    public IndexingResponse(boolean result) {
        this.result = result;
    }

    public IndexingResponse(String error) {
        this.error = error;
    }
}
