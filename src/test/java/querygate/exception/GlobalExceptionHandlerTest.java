package querygate.exception;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;
import querygate.validation.ValidationException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for GlobalExceptionHandler.
 * Tests exception handling and HTTP response generation.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler globalExceptionHandler;

    GlobalExceptionHandlerTest() {
        this.globalExceptionHandler = new GlobalExceptionHandler();
    }

    @Test
    void testWhenEndpointNotFoundThenReturns404() {
        // Arrange
        HttpRequest<?> request = HttpRequest.GET("/api/nonexistent");
        Exception exception = new EndpointNotFoundException("GET", "/api/nonexistent");

        // Act
        HttpResponse<Map<String, Object>> response = globalExceptionHandler.handle(request, exception);

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.body())
                .containsEntry("success", false)
                .containsEntry("error", "Endpoint Not Found")
                .containsKey("message");
    }

    @Test
    void testWhenSqlExecutionErrorThenReturns500() {
        // Arrange
        HttpRequest<?> request = HttpRequest.POST("/api/users", "");
        Exception exception = new SqlExecutionException(
                "UserMapper.insert",
                "SQL execution failed",
                new RuntimeException("Duplicate key")
        );

        // Act
        HttpResponse<Map<String, Object>> response = globalExceptionHandler.handle(request, exception);

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.body())
                .containsEntry("success", false)
                .containsEntry("error", "Database Error")
                .containsEntry("message", "A database error occurred");
    }

    @Test
    void testWhenValidationErrorThenReturns400() {
        // Arrange
        HttpRequest<?> request = HttpRequest.POST("/api/users", "");
        List<String> errors = List.of("Email is required", "Name must not be empty");
        Exception exception = new ValidationException("Validation failed", errors);

        // Act
        HttpResponse<Map<String, Object>> response = globalExceptionHandler.handle(request, exception);

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.body())
                .containsEntry("success", false)
                .containsEntry("error", "Validation Error")
                .containsKey("details");
    }

    @Test
    void testWhenIllegalArgumentExceptionThenReturns400() {
        // Arrange
        HttpRequest<?> request = HttpRequest.POST("/api/users", "");
        Exception exception = new IllegalArgumentException("Invalid parameter value");

        // Act
        HttpResponse<Map<String, Object>> response = globalExceptionHandler.handle(request, exception);

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.body())
                .containsEntry("success", false)
                .containsEntry("error", "Bad Request")
                .containsEntry("message", "Invalid parameter value");
    }

    @Test
    void testWhenGenericExceptionThenReturns500() {
        // Arrange
        HttpRequest<?> request = HttpRequest.GET("/api/test");
        Exception exception = new RuntimeException("Unexpected error");

        // Act
        HttpResponse<Map<String, Object>> response = globalExceptionHandler.handle(request, exception);

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.body())
                .containsEntry("success", false)
                .containsEntry("error", "Internal Server Error");
    }

    @Test
    void testWhenErrorThenIncludesRequestPath() {
        // Arrange
        HttpRequest<?> request = HttpRequest.GET("/api/users/123");
        Exception exception = new EndpointNotFoundException("GET", "/api/users/123");

        // Act
        HttpResponse<Map<String, Object>> response = globalExceptionHandler.handle(request, exception);

        // Assert
        assertThat(response.body())
                .containsEntry("path", "/api/users/123");
    }

    @Test
    void testWhenErrorThenIncludesRequestMethod() {
        // Arrange
        HttpRequest<?> request = HttpRequest.POST("/api/users", "");
        Exception exception = new EndpointNotFoundException("POST", "/api/users");

        // Act
        HttpResponse<Map<String, Object>> response = globalExceptionHandler.handle(request, exception);

        // Assert
        assertThat(response.body())
                .containsEntry("method", "POST");
    }

    @Test
    void testWhenSqlExceptionThenDoesNotExposeSqlDetails() {
        // Arrange
        HttpRequest<?> request = HttpRequest.POST("/api/users", "");
        Exception exception = new SqlExecutionException(
                "UserMapper.insert",
                "SQL execution failed",
                new RuntimeException("PRIMARY KEY constraint failed on id=123")
        );

        // Act
        HttpResponse<Map<String, Object>> response = globalExceptionHandler.handle(request, exception);

        // Assert
        assertThat(response.body())
                .containsEntry("message", "A database error occurred")
                .doesNotContainValue("PRIMARY KEY");
    }

    @Test
    void testWhenValidationErrorThenIncludesErrorDetails() {
        // Arrange
        HttpRequest<?> request = HttpRequest.POST("/api/users", "");
        List<String> errors = List.of("Email must be valid", "Age must be positive");
        Exception exception = new ValidationException("Validation failed", errors);

        // Act
        HttpResponse<Map<String, Object>> response = globalExceptionHandler.handle(request, exception);

        // Assert
        assertThat(response.body())
                .containsKey("details");
    }

    @Test
    void testWhenEndpointNotFoundThenIncludesMethodAndPath() {
        // Arrange
        HttpRequest<?> request = HttpRequest.DELETE("/api/items/456");
        Exception exception = new EndpointNotFoundException("DELETE", "/api/items/456");

        // Act
        HttpResponse<Map<String, Object>> response = globalExceptionHandler.handle(request, exception);

        // Assert
        assertThat(response.body())
                .containsEntry("path", "/api/items/456")
                .containsEntry("method", "DELETE");
    }

    @Test
    void testWhenMultipleSqlErrorsThenAllReturn500() {
        // Arrange
        HttpRequest<?> request = HttpRequest.POST("/api/users", "");

        // Act & Assert - test multiple SQL error scenarios
        Exception[] sqlErrors = {
                new SqlExecutionException("mapper1", "Error 1"),
                new SqlExecutionException("mapper2", "Error 2"),
                new RuntimeException("java.sql.SQLException: Connection failed")
        };

        for (Exception exception : sqlErrors) {
            HttpResponse<Map<String, Object>> response = globalExceptionHandler.handle(request, exception);
            assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
