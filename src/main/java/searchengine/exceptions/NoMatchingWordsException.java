package searchengine.exceptions;

public class NoMatchingWordsException extends RuntimeException {
    public NoMatchingWordsException(String message) {
        super(message);
    }
}
