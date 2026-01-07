package searchengine.exceptions;

public class EmptyUrlException  extends RuntimeException {
    public EmptyUrlException(String message) {
        super(message);
    }
}
