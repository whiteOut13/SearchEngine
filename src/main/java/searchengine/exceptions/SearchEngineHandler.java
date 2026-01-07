package searchengine.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import searchengine.dto.Indexing.IndexingResponse;

@ControllerAdvice
public class SearchEngineHandler {

    @ExceptionHandler(IndexingAlreadyStartedException.class)
    public ResponseEntity<IndexingResponse> handlerIndexingAlreadyStarted(IndexingAlreadyStartedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new IndexingResponse(false, e.getMessage()));
    }

    @ExceptionHandler(IndexingNotStartedException.class)
    public ResponseEntity<IndexingResponse> handlerIndexingNotStarted(IndexingNotStartedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new IndexingResponse(false, e.getMessage()));
    }

    @ExceptionHandler(EmptyUrlException.class)
    public ResponseEntity<IndexingResponse> handlerEmptyUrl(EmptyUrlException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new IndexingResponse(false, e.getMessage()));
    }
}
