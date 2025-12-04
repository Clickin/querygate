package querygate.service;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for InputMergerService.
 * Tests parameter merging from path variables, query parameters, and request body.
 */
@MicronautTest
class InputMergerServiceTest {

    @Inject
    InputMergerService inputMergerService;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void testWhenMergingPathVariablesThenIncludesInResult() {
        // Arrange
        HttpRequest<?> request = createMockRequest();
        Map<String, String> pathVars = Map.of("id", "123");

        // Act
        Map<String, Object> result = inputMergerService.mergeInputs(request, pathVars, null);

        // Assert
        assertThat(result)
                .containsEntry("id", "123");
    }

    @Test
    void testWhenMergingQueryParametersThenIncludesInResult() {
        // Arrange
        HttpRequest<?> request = createRequestWithQueryParams("name=Alice&status=active");
        Map<String, String> pathVars = Map.of();

        // Act
        Map<String, Object> result = inputMergerService.mergeInputs(request, pathVars, null);

        // Assert
        assertThat(result)
                .containsEntry("name", "Alice")
                .containsEntry("status", "active");
    }

    @Test
    void testWhenMergingJsonBodyThenIncludesInResult() {
        // Arrange
        HttpRequest<?> request = createRequestWithJsonBody();
        Map<String, String> pathVars = Map.of();
        String body = "{\"email\":\"alice@example.com\",\"age\":30}";

        // Act
        Map<String, Object> result = inputMergerService.mergeInputs(request, pathVars, body);

        // Assert
        assertThat(result)
                .containsEntry("email", "alice@example.com")
                .containsEntry("age", 30);
    }

    @Test
    void testWhenBodyParametersOverrideQueryParametersThenBodyWins() {
        // Arrange
        HttpRequest<?> request = createRequestWithJsonBodyAndQueryParams("status=inactive");
        Map<String, String> pathVars = Map.of();
        String body = "{\"status\":\"active\"}";

        // Act
        Map<String, Object> result = inputMergerService.mergeInputs(request, pathVars, body);

        // Assert
        assertThat(result)
                .containsEntry("status", "active");
    }

    @Test
    void testWhenQueryParametersOverridePathVariablesThenQueryWins() {
        // Arrange
        HttpRequest<?> request = createRequestWithQueryParams("id=456");
        Map<String, String> pathVars = Map.of("id", "123");

        // Act
        Map<String, Object> result = inputMergerService.mergeInputs(request, pathVars, null);

        // Assert
        assertThat(result)
                .containsEntry("id", "456");
    }

    @Test
    void testWhenMergingAllSourcesThenAppliesPriorityCorrectly() {
        // Arrange - path vars: id=1, query: name=Bob&id=2, body: id=3&email=bob@example.com
        HttpRequest<?> request = createRequestWithJsonBodyAndQueryParams("name=Bob&id=2");
        Map<String, String> pathVars = Map.of("id", "1");
        String body = "{\"id\":3,\"email\":\"bob@example.com\"}";

        // Act
        Map<String, Object> result = inputMergerService.mergeInputs(request, pathVars, body);

        // Assert - body > query > path variables
        assertThat(result)
                .containsEntry("id", 3)  // from body (highest priority)
                .containsEntry("name", "Bob")  // from query
                .containsEntry("email", "bob@example.com");  // from body
    }

    @Test
    void testWhenBodyIsBlankThenIgnoresBody() {
        // Arrange
        HttpRequest<?> request = createRequestWithJsonBody();
        Map<String, String> pathVars = Map.of("id", "123");

        // Act
        Map<String, Object> result = inputMergerService.mergeInputs(request, pathVars, "   ");

        // Assert
        assertThat(result)
                .containsEntry("id", "123")
                .doesNotContainKey("email");
    }

    @Test
    void testWhenBodyIsNullThenIgnoresBody() {
        // Arrange
        HttpRequest<?> request = createMockRequest();
        Map<String, String> pathVars = Map.of("id", "123");

        // Act
        Map<String, Object> result = inputMergerService.mergeInputs(request, pathVars, null);

        // Assert
        assertThat(result)
                .containsEntry("id", "123");
    }

    @Test
    void testWhenPathVariablesAreNullThenHandlesGracefully() {
        // Arrange
        HttpRequest<?> request = createRequestWithQueryParams("name=Charlie");

        // Act
        Map<String, Object> result = inputMergerService.mergeInputs(request, null, null);

        // Assert
        assertThat(result)
                .containsEntry("name", "Charlie");
    }

    @Test
    void testWhenMultipleQueryParameterValuesForSameKeyThenReturnsAsList() {
        // Arrange
        HttpRequest<?> request = createRequestWithMultiValueParams("tag", new String[]{"java", "testing"});
        Map<String, String> pathVars = Map.of();

        // Act
        Map<String, Object> result = inputMergerService.mergeInputs(request, pathVars, null);

        // Assert
        assertThat(result)
                .containsKey("tag");
        assertThat(result.get("tag"))
                .isInstanceOf(List.class)
                .isEqualTo(List.of("java", "testing"));
    }

    @Test
    void testWhenInvalidJsonBodyThenThrowsParseException() {
        // Arrange
        HttpRequest<?> request = createRequestWithJsonBody();
        Map<String, String> pathVars = Map.of("id", "123");
        String invalidBody = "{invalid json}";

        // Act & Assert
        assertThatThrownBy(() -> inputMergerService.mergeInputs(request, pathVars, invalidBody))
                .isInstanceOf(querygate.exception.RequestBodyParseException.class)
                .hasMessageContaining("Invalid JSON format");
    }

    // Helper methods to create mock requests

    private HttpRequest<?> createMockRequest() {
        return HttpRequest.GET("/api/test");
    }

    private HttpRequest<?> createRequestWithQueryParams(String queryString) {
        return HttpRequest.GET("/api/test?" + queryString);
    }

    private HttpRequest<?> createRequestWithJsonBody() {
        return HttpRequest.POST("/api/test", "")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private HttpRequest<?> createRequestWithJsonBodyAndQueryParams(String queryString) {
        return HttpRequest.POST("/api/test?" + queryString, "")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private HttpRequest<?> createRequestWithMultiValueParams(String key, String[] values) {
        StringBuilder queryString = new StringBuilder();
        for (String value : values) {
            if (queryString.length() > 0) {
                queryString.append("&");
            }
            queryString.append(key).append("=").append(value);
        }
        return HttpRequest.GET("/api/test?" + queryString);
    }
}
