package searchengine.exceptions;


public class PageOutsideSitesException extends RuntimeException {
    public PageOutsideSitesException(String message) {
        super(message);
    }
}