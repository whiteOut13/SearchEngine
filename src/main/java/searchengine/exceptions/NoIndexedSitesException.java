package searchengine.exceptions;

public class NoIndexedSitesException extends RuntimeException{
    public NoIndexedSitesException (String message) {
        super(message);
    }
}
