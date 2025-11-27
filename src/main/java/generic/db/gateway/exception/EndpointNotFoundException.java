package generic.db.gateway.exception;

/**
 * Exception thrown when no endpoint configuration is found for a request.
 */
public class EndpointNotFoundException extends RuntimeException {

    private final String method;
    private final String path;

    public EndpointNotFoundException(String method, String path) {
        super(String.format("No endpoint configuration found for %s %s", method, path));
        this.method = method;
        this.path = path;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }
}
