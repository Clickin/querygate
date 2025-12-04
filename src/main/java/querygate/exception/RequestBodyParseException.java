package querygate.exception;

/**
 * Exception thrown when request body parsing fails.
 * Results in HTTP 400 Bad Request response.
 */
public class RequestBodyParseException extends RuntimeException {

    private final String contentType;

    public RequestBodyParseException(String message, String contentType, Throwable cause) {
        super(message, cause);
        this.contentType = contentType;
    }

    public String getContentType() {
        return contentType;
    }
}
