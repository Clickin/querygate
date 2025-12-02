package querygate.integration;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end integration tests for QueryGate.
 * Tests complete request flows from HTTP request through database to response.
 */
@MicronautTest
class EndToEndGatewayTest {

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    DataSource dataSource;

    private static final String API_KEY = "secret-key";

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS e2e_test_users");
            stmt.execute("""
                CREATE TABLE e2e_test_users (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(255) NOT NULL,
                    email VARCHAR(255) NOT NULL
                )
                """);
        }
    }

    @Test
    void testCompleteCreateReadUpdateDeleteCycle() {
        // 1. CREATE - Insert a user
        String createBody = """
                {
                    "name": "Alice",
                    "email": "alice@example.com"
                }
                """;
        HttpResponse<Map> createResponse = httpClient.toBlocking().exchange(
                HttpRequest.POST("/api/users", createBody)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );
        assertThat((Object) createResponse.getStatus()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.body())
                .containsEntry("sqlType", "INSERT")
                .containsEntry("success", true);

        // 2. READ - Query the inserted user
        HttpResponse<Map> readResponse = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/users?name=Alice")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );
        assertThat((Object) readResponse.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(readResponse.body())
                .containsEntry("sqlType", "SELECT")
                .containsEntry("success", true);

        // 3. UPDATE - Modify the user
        String updateBody = """
                {
                    "name": "Alice",
                    "email": "alice.new@example.com"
                }
                """;
        HttpResponse<Map> updateResponse = httpClient.toBlocking().exchange(
                HttpRequest.PUT("/api/users", updateBody)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );
        assertThat((Object) updateResponse.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.body())
                .containsEntry("sqlType", "UPDATE");

        // 4. DELETE - Remove the user
        HttpResponse<Map> deleteResponse = httpClient.toBlocking().exchange(
                HttpRequest.DELETE("/api/users?name=Alice")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );
        assertThat((Object) deleteResponse.getStatus()).isEqualTo(HttpStatus.OK);

        // 5. VERIFY - Confirm deletion
        HttpResponse<Map> verifyResponse = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/users?name=Alice")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );
        Map dataList = (Map) verifyResponse.body().get("data");
        if (dataList != null) {
            assertThat((List) dataList.getOrDefault("data", List.of())).isEmpty();
        }
    }

    @Test
    void testMultipleInsertsThenQueryAll() {
        // Insert multiple users
        for (int i = 1; i <= 3; i++) {
            String body = String.format("""
                    {
                        "name": "User%d",
                        "email": "user%d@example.com"
                    }
                    """, i, i);
            httpClient.toBlocking().exchange(
                    HttpRequest.POST("/api/users", body)
                            .header("Authorization", "Key " + API_KEY)
                            .contentType(MediaType.APPLICATION_JSON),
                    Map.class
            );
        }

        // Query all users
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/users")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );

        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(response.body())
                .containsEntry("success", true)
                .containsEntry("sqlType", "SELECT");
    }

    @Test
    void testSelectWithParameterFiltering() {
        // Setup - insert test data
        insertUser("Bob", "bob@example.com");
        insertUser("Charlie", "charlie@example.com");

        // Act - query by name
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/users?name=Bob")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(response.body()).containsEntry("success", true);
    }

    @Test
    void testHealthCheckEndpoint() {
        // Act
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/health")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(response.body())
                .containsEntry("success", true)
                .containsKey("sqlType");
    }

    @Test
    void testJsonAndXmlContentNegotiation() {
        insertUser("Dana", "dana@example.com");

        // Test JSON response
        HttpResponse<String> jsonResponse = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/users?name=Dana")
                        .header("Authorization", "Key " + API_KEY)
                        .accept(MediaType.APPLICATION_JSON),
                String.class
        );
        assertThat(jsonResponse.body()).startsWith("{");

        // Test XML response
        HttpResponse<String> xmlResponse = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/users?name=Dana")
                        .header("Authorization", "Key " + API_KEY)
                        .accept(MediaType.APPLICATION_XML),
                String.class
        );
        assertThat(xmlResponse.body()).startsWith("<?xml");
    }

    @Test
    void testQueryParameterMerging() {
        // Insert test data
        insertUser("Eve", "eve@example.com");

        // Act - pass parameters via query string
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/users?name=Eve&email=eve@example.com")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(response.body()).containsEntry("success", true);
    }

    @Test
    void testPostWithJsonBodyParameters() {
        // Act
        String body = """
                {
                    "name": "Frank",
                    "email": "frank@example.com"
                }
                """;
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.POST("/api/users", body)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.body())
                .containsEntry("sqlType", "INSERT")
                .containsEntry("success", true);
    }

    @Test
    void testErrorHandlingForInvalidEndpoint() {
        // Act & Assert
        assertThatThrownBy(() -> httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/nonexistent-path")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        ))
                .isInstanceOf(Exception.class);
    }

    @Test
    void testErrorHandlingForMissingAuthentication() {
        // Act & Assert - no Authorization header
        assertThatThrownBy(() -> httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/health"),
                Map.class
        ))
                .isInstanceOf(Exception.class);
    }

    @Test
    void testInsertStatusCodeCreated() {
        // Act
        String body = """
                {
                    "name": "Grace",
                    "email": "grace@example.com"
                }
                """;
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.POST("/api/users", body)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void testDeleteWithAffectedRowsReturnsOk() {
        // Setup
        insertUser("Henry", "henry@example.com");

        // Act
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.DELETE("/api/users?name=Henry")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testUpdateWithAffectedRowsReturnsOk() {
        // Setup
        insertUser("Ivy", "ivy@old.com");

        // Act
        String body = """
                {
                    "name": "Ivy",
                    "email": "ivy@new.com"
                }
                """;
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.PUT("/api/users", body)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );

        // Assert
        assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testResponseIncludesMetadata() {
        // Act
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/health")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );

        // Assert
        assertThat(response.body())
                .containsKeys("success", "sqlType", "method", "path");
    }

    // Helper methods

    private void insertUser(String name, String email) {
        String body = String.format("""
                {
                    "name": "%s",
                    "email": "%s"
                }
                """, name, email);
        try {
            httpClient.toBlocking().exchange(
                    HttpRequest.POST("/api/users", body)
                            .header("Authorization", "Key " + API_KEY)
                            .contentType(MediaType.APPLICATION_JSON),
                    Map.class
            );
        } catch (Exception e) {
            // Ignore errors during setup
        }
    }
}
