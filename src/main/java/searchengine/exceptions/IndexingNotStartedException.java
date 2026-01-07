package searchengine.exceptions;

public class IndexingNotStartedException extends RuntimeException {
  public IndexingNotStartedException(String message) {
    super(message);
  }
}
