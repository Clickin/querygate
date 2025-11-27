package querygate.exception;

import querygate.validation.ValidationException;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler for the gateway.
 * Converts exceptions to appropriate HTTP responses with structured error bodies.
 */
@Produces
@Singleton
@Requires(classes = {Exception.class, ExceptionHandler.class})
public class GlobalExceptionHandler implements ExceptionHandler<Exception, HttpResponse<Map<String, Object>>> {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    public HttpResponse<Map<String, Object>> handle(HttpRequest request, Exception exception) {
        LOG.error("Request {} {} failed: {}",
                request.getMethod(), request.getPath(), exception.getMessage(), exception);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("success", false);
        error.put("path", request.getPath());
        error.put("method", request.getMethodName());

        // Handle specific exception types
        if (exception instanceof ValidationException ve) {
            error.put("error", "Validation Error");
            error.put("message", ve.getMessage());
            error.put("details", ve.getErrors());
            return HttpResponse.status(HttpStatus.BAD_REQUEST).body(error);
        }

        if (exception instanceof EndpointNotFoundException enf) {
            error.put("error", "Endpoint Not Found");
            error.put("message", enf.getMessage());
            return HttpResponse.status(HttpStatus.NOT_FOUND).body(error);
        }

        if (exception instanceof IllegalArgumentException) {
            error.put("error", "Bad Request");
            error.put("message", exception.getMessage());
            return HttpResponse.status(HttpStatus.BAD_REQUEST).body(error);
        }

        // SQL execution errors (from DynamicSqlService)
        if (exception instanceof SqlExecutionException sqe) {
            error.put("error", "Database Error");
            error.put("message", "A database error occurred");
            // Log SQL ID for correlation, but don't expose to client
            LOG.error("SQL execution error for sqlId={}: {}", sqe.getSqlId(),
                    sqe.getCause() != null ? sqe.getCause().getMessage() : sqe.getMessage());
            return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }

        // Other Database/SQL errors
        if (exception.getClass().getName().contains("SQL") ||
                exception.getClass().getName().contains("Persistence") ||
                exception.getClass().getName().contains("MyBatis")) {
            error.put("error", "Database Error");
            error.put("message", "A database error occurred");
            return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }

        // Generic internal error
        error.put("error", "Internal Server Error");
        error.put("message", "An unexpected error occurred");
        if (LOG.isDebugEnabled()) {
            error.put("debug", exception.getMessage());
        }

        return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
