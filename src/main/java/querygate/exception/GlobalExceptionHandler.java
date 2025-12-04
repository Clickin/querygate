package querygate.exception;

import querygate.config.EndpointConfig;
import querygate.config.EndpointConfigLoader;
import querygate.config.GatewayProperties;
import querygate.validation.ValidationException;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
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
 * Error detail exposure is controlled by gateway.error-handling configuration.
 *
 * For endpoints with response-format=RAW, errors are returned via HTTP headers
 * to prevent DTO mapping issues on the client side.
 */
@Produces
@Singleton
@Requires(classes = {Exception.class, ExceptionHandler.class})
public class GlobalExceptionHandler implements ExceptionHandler<Exception, HttpResponse<Map<String, Object>>> {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final GatewayProperties properties;
    private final EndpointConfigLoader endpointConfigLoader;

    public GlobalExceptionHandler(GatewayProperties properties, EndpointConfigLoader endpointConfigLoader) {
        this.properties = properties;
        this.endpointConfigLoader = endpointConfigLoader;
    }

    @Override
    public HttpResponse<Map<String, Object>> handle(HttpRequest request, Exception exception) {
        LOG.error("Request {} {} failed: {}",
                request.getMethod(), request.getPath(), exception.getMessage(), exception);

        // Check if this endpoint uses RAW response format
        boolean isRawFormat = isRawResponseFormat(request);

        if (isRawFormat) {
            // For RAW format, return error via HTTP headers to prevent DTO mapping issues
            return handleRawFormatError(request, exception);
        }

        // Standard wrapped error response
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("success", false);
        error.put("path", request.getPath());
        error.put("method", request.getMethodName());

        boolean exposeDetails = properties.errorHandling().exposeDetails();
        boolean exposeStackTrace = properties.errorHandling().exposeStackTrace();

        // Handle specific exception types
        if (exception instanceof ValidationException ve) {
            error.put("error", "Validation Error");
            if (exposeDetails) {
                error.put("message", ve.getMessage());
                error.put("details", ve.getErrors());
            } else {
                error.put("message", "Request validation failed");
            }
            addStackTraceIfEnabled(error, exception, exposeStackTrace);
            return HttpResponse.status(HttpStatus.BAD_REQUEST).body(error);
        }

        if (exception instanceof RequestBodyParseException pe) {
            error.put("error", "Request Body Parse Error");
            if (exposeDetails) {
                error.put("message", pe.getMessage());
                error.put("contentType", pe.getContentType());
            } else {
                error.put("message", "Invalid request format");
            }
            addStackTraceIfEnabled(error, exception, exposeStackTrace);
            return HttpResponse.status(HttpStatus.BAD_REQUEST).body(error);
        }

        if (exception instanceof EndpointNotFoundException enf) {
            error.put("error", "Endpoint Not Found");
            error.put("message", exposeDetails ? enf.getMessage() : "The requested endpoint does not exist");
            addStackTraceIfEnabled(error, exception, exposeStackTrace);
            return HttpResponse.status(HttpStatus.NOT_FOUND).body(error);
        }

        if (exception instanceof IllegalArgumentException) {
            error.put("error", "Bad Request");
            error.put("message", exposeDetails ? exception.getMessage() : "Invalid request parameters");
            addStackTraceIfEnabled(error, exception, exposeStackTrace);
            return HttpResponse.status(HttpStatus.BAD_REQUEST).body(error);
        }

        // SQL execution errors (from DynamicSqlService)
        if (exception instanceof SqlExecutionException sqe) {
            error.put("error", "Database Error");
            if (exposeDetails) {
                error.put("message", "SQL execution failed: " + sqe.getMessage());
                error.put("sqlId", sqe.getSqlId());
            } else {
                error.put("message", "A database error occurred");
            }
            // Always log SQL ID for server-side debugging
            LOG.error("SQL execution error for sqlId={}: {}", sqe.getSqlId(),
                    sqe.getCause() != null ? sqe.getCause().getMessage() : sqe.getMessage());
            addStackTraceIfEnabled(error, exception, exposeStackTrace);
            return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }

        // Other Database/SQL errors
        if (exception.getClass().getName().contains("SQL") ||
                exception.getClass().getName().contains("Persistence") ||
                exception.getClass().getName().contains("MyBatis")) {
            error.put("error", "Database Error");
            if (exposeDetails) {
                error.put("message", "Database operation failed: " + exception.getMessage());
            } else {
                error.put("message", "A database error occurred");
            }
            addStackTraceIfEnabled(error, exception, exposeStackTrace);
            return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }

        // Generic internal error
        error.put("error", "Internal Server Error");
        if (exposeDetails) {
            error.put("message", exception.getMessage());
        } else {
            error.put("message", "An unexpected error occurred");
        }
        addStackTraceIfEnabled(error, exception, exposeStackTrace);

        return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Adds stack trace to error response if enabled.
     */
    private void addStackTraceIfEnabled(Map<String, Object> error, Exception exception, boolean exposeStackTrace) {
        if (exposeStackTrace) {
            StackTraceElement[] stackTrace = exception.getStackTrace();
            if (stackTrace != null && stackTrace.length > 0) {
                // Include only the first few frames to avoid huge responses
                int maxFrames = Math.min(10, stackTrace.length);
                String[] frames = new String[maxFrames];
                for (int i = 0; i < maxFrames; i++) {
                    frames[i] = stackTrace[i].toString();
                }
                error.put("stackTrace", frames);
            }
        }
    }

    /**
     * Checks if the endpoint uses RAW response format.
     */
    private boolean isRawResponseFormat(HttpRequest request) {
        try {
            String path = request.getPath();
            String method = request.getMethod().name();
            EndpointConfig config = endpointConfigLoader.findMatchingEndpoint(method, path);

            if (config != null && config.responseFormat() != null) {
                return config.responseFormat() == EndpointConfig.ResponseFormat.RAW;
            }
        } catch (Exception e) {
            LOG.debug("Could not determine response format for {} {}, defaulting to wrapped",
                     request.getMethod(), request.getPath());
        }
        return false;
    }

    /**
     * Handles errors for RAW format endpoints by returning error info via HTTP headers.
     * Returns empty body to prevent DTO mapping issues on client side.
     */
    private HttpResponse<Map<String, Object>> handleRawFormatError(HttpRequest request, Exception exception) {
        HttpStatus status = determineHttpStatus(exception);
        boolean exposeDetails = properties.errorHandling().exposeDetails();

        MutableHttpResponse<Map<String, Object>> response = HttpResponse.status(status);

        // Add error information to headers
        response.header("X-Error-Type", getErrorType(exception));

        if (exposeDetails) {
            response.header("X-Error-Message", sanitizeHeaderValue(exception.getMessage()));

            // Add exception-specific headers
            if (exception instanceof ValidationException ve) {
                response.header("X-Error-Details", sanitizeHeaderValue(String.join("; ", ve.getErrors())));
            } else if (exception instanceof RequestBodyParseException pe) {
                response.header("X-Error-ContentType", pe.getContentType());
            } else if (exception instanceof SqlExecutionException sqe) {
                if (exposeDetails) {
                    response.header("X-Error-SqlId", sqe.getSqlId());
                }
            }
        } else {
            response.header("X-Error-Message", getGenericErrorMessage(exception));
        }

        // Return empty body - client should check HTTP status code
        return response.body(null);
    }

    /**
     * Determines HTTP status code based on exception type.
     */
    private HttpStatus determineHttpStatus(Exception exception) {
        if (exception instanceof ValidationException ||
            exception instanceof RequestBodyParseException ||
            exception instanceof IllegalArgumentException) {
            return HttpStatus.BAD_REQUEST;
        }
        if (exception instanceof EndpointNotFoundException) {
            return HttpStatus.NOT_FOUND;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * Gets error type name for header.
     */
    private String getErrorType(Exception exception) {
        if (exception instanceof ValidationException) {
            return "ValidationError";
        }
        if (exception instanceof RequestBodyParseException) {
            return "ParseError";
        }
        if (exception instanceof EndpointNotFoundException) {
            return "NotFound";
        }
        if (exception instanceof SqlExecutionException) {
            return "DatabaseError";
        }
        if (exception instanceof IllegalArgumentException) {
            return "BadRequest";
        }
        return "InternalError";
    }

    /**
     * Gets generic error message based on exception type.
     */
    private String getGenericErrorMessage(Exception exception) {
        if (exception instanceof ValidationException) {
            return "Request validation failed";
        }
        if (exception instanceof RequestBodyParseException) {
            return "Invalid request format";
        }
        if (exception instanceof EndpointNotFoundException) {
            return "The requested endpoint does not exist";
        }
        if (exception instanceof IllegalArgumentException) {
            return "Invalid request parameters";
        }
        return "An error occurred";
    }

    /**
     * Sanitizes header values to prevent header injection.
     */
    private String sanitizeHeaderValue(String value) {
        if (value == null) {
            return "";
        }
        // Remove newlines and control characters to prevent header injection
        return value.replaceAll("[\\r\\n\\x00-\\x1F\\x7F]", " ").trim();
    }
}
