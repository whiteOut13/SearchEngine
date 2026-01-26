package searchengine.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import lombok.extern.slf4j.Slf4j;
import searchengine.dto.Indexing.IndexingResponse;
import searchengine.dto.search.SearchResponse;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler
    public ResponseEntity<IndexingResponse> catchIndexingAlreadyStartedException(IndexingAlreadyStartedException e) {
        return new ResponseEntity<>(new IndexingResponse(false, e.getMessage()), HttpStatus.BAD_REQUEST);
    }
    
    @ExceptionHandler
    public ResponseEntity<IndexingResponse> catchIndexingNotStartedException(IndexingNotStartedException e) {
        return new ResponseEntity<>(new IndexingResponse(false, e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler
    public ResponseEntity<IndexingResponse> catchPageOutsideSitesException(PageOutsideSitesException e) {
        return new ResponseEntity<>(new IndexingResponse(false, e.getMessage()), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler
    public ResponseEntity<SearchResponse> catchEmptyUrlException(EmptyUrlException e) {
        return new ResponseEntity<>(new SearchResponse(false, e.getMessage()), HttpStatus.BAD_REQUEST);
    }

    public ResponseEntity<SearchResponse> catchNoIndexedSitesException(NoIndexedSitesException e) {
        return new ResponseEntity<>(new SearchResponse(false, e.getMessage()), HttpStatus.NOT_FOUND);
    }

    public ResponseEntity<SearchResponse> catchNoMatchingWordsException(NoMatchingWordsException e) {
        return new ResponseEntity<>(new SearchResponse(false, e.getMessage()), HttpStatus.BAD_REQUEST);
    }
}
