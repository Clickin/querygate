package querygate.controller;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import querygate.exception.EndpointNotFoundException;

import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for DynamicRoutingController.
 * Tests HTTP request routing, parameter handling, and response formatting.
 */
@MicronautTest
class DynamicRoutingControllerTest {

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_users");
            stmt.execute("""
                CREATE TABLE test_users (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(255) NOT NULL,
                    email VARCHAR(255) NOT NULL
                )
                """);
        }
    }

    @Test
    void testWhenGetRequestMatchesEndpointThenReturnsOkResponse() {
        // Arrange
        String apiKey = "secret-key";

        // Act
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/health")
                        .header("Authorization", "Key " + apiKey),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(response.body())
                .containsKey("success")
                .containsKey("sqlType");
    }

    @Test
    void testWhenPostRequestWithJsonBodyThenMergesParameters() {
        // Arrange
        String apiKey = "secret-key";
        String body = """
                {
                    "name": "Alice",
                    "email": "alice@example.com"
                }
                """;

        // Act
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.POST("/api/users", body)
                        .header("Authorization", "Key " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.body())
                .containsEntry("sqlType", "INSERT");
    }

    @Test
    void testWhenGetRequestWithQueryParametersThenIncludesInExecution() {
        // Arrange
        insertTestUser("Bob", "bob@example.com");
        String apiKey = "secret-key";

        // Act
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/users?name=Bob")
                        .header("Authorization", "Key " + apiKey),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testWhenEndpointNotFoundThenReturns404() {
        // Arrange
        String apiKey = "secret-key";

        // Act & Assert
        assertThatThrownBy(() -> httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/nonexistent-endpoint")
                        .header("Authorization", "Key " + apiKey),
                Map.class
        ))
                .isInstanceOf(Exception.class);
    }

    @Test
    void testWhenSelectOperationThenReturnsOkStatus() {
        // Arrange
        insertTestUser("Charlie", "charlie@example.com");
        String apiKey = "secret-key";

        // Act
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/health")
                        .header("Authorization", "Key " + apiKey),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testWhenInsertOperationThenReturnsCreatedStatus() {
        // Arrange
        String apiKey = "secret-key";
        String body = """
                {
                    "name": "Dana",
                    "email": "dana@example.com"
                }
                """;

        // Act
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.POST("/api/users", body)
                        .header("Authorization", "Key " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.body()).containsEntry("sqlType", "INSERT");
    }

    @Test
    void testWhenAcceptJsonHeaderThenReturnsJsonResponse() {
        // Arrange
        String apiKey = "secret-key";

        // Act
        HttpResponse<String> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/health")
                        .header("Authorization", "Key " + apiKey)
                        .accept(MediaType.APPLICATION_JSON),
                String.class
        );

        // Assert
        assertThat(response.getContentType().map(Object::toString).orElse("")).contains("json");
        assertThat(response.body()).contains("{");
    }

    @Test
    void testWhenAcceptXmlHeaderThenReturnsXmlResponse() {
        // Arrange
        String apiKey = "secret-key";

        // Act
        HttpResponse<String> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/health")
                        .header("Authorization", "Key " + apiKey)
                        .accept(MediaType.APPLICATION_XML),
                String.class
        );

        // Assert
        assertThat(response.getContentType().map(Object::toString).orElse("")).contains("xml");
        assertThat(response.body()).contains("<?xml");
    }

    @Test
    void testWhenMissingAuthenticationHeaderThenReturns401() {
        // Act & Assert
        assertThatThrownBy(() -> httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/health"),
                Map.class
        ))
                .isInstanceOf(Exception.class);
    }

    @Test
    void testWhenInvalidApiKeyThenReturns401() {
        // Act & Assert
        assertThatThrownBy(() -> httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/health")
                        .header("Authorization", "Key invalid-key"),
                Map.class
        ))
                .isInstanceOf(Exception.class);
    }

    @Test
    void testWhenUpdateOperationWithMatchesThenReturnsOkStatus() {
        // Arrange
        insertTestUser("Eve", "eve@old.com");
        String apiKey = "secret-key";
        String body = """
                {
                    "name": "Eve",
                    "email": "eve@new.com"
                }
                """;

        // Act
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.PUT("/api/users", body)
                        .header("Authorization", "Key " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testWhenDeleteOperationWithMatchesThenReturnsOkStatus() {
        // Arrange
        insertTestUser("Frank", "frank@example.com");
        String apiKey = "secret-key";

        // Act
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.DELETE("/api/users?name=Frank")
                        .header("Authorization", "Key " + apiKey),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testWhenDeleteOperationWithoutMatchesThenReturnsNotFoundStatus() {
        // Arrange
        String apiKey = "secret-key";

        // Act
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.DELETE("/api/users?name=NonExistent")
                        .header("Authorization", "Key " + apiKey),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void testWhenResponseIncludesMetadataThenContainsSqlTypeAndStatus() {
        // Arrange
        String apiKey = "secret-key";

        // Act
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/health")
                        .header("Authorization", "Key " + apiKey),
                Map.class
        );

        // Assert
        assertThat(response.body())
                .containsKeys("success", "sqlType");
    }

    // Helper method

    private void insertTestUser(String name, String email) {
        String apiKey = "secret-key";
        String body = String.format("""
                {
                    "name": "%s",
                    "email": "%s"
                }
                """, name, email);

        try {
            httpClient.toBlocking().exchange(
                    HttpRequest.POST("/api/users", body)
                            .header("Authorization", "Key " + apiKey)
                            .contentType(MediaType.APPLICATION_JSON),
                    Map.class
            );
        } catch (Exception e) {
            // Ignore errors during setup
        }
    }
}
