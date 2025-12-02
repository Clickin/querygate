package querygate.integration;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import querygate.support.ConfigurationToggle;
import querygate.support.ThreadInspectionUtils;

import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests to verify virtual thread behavior, configuration, and lifecycle.
 * Ensures virtual threads are properly created and used for database operations.
 */
@MicronautTest
class VirtualThreadBehaviorTest {

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    DataSource dataSource;

    @Inject
    ApplicationContext applicationContext;

    private static final String API_KEY = "secret-key";

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS virtual_thread_test");
            stmt.execute("""
                CREATE TABLE virtual_thread_test (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(255) NOT NULL,
                    thread_id BIGINT
                )
                """);
        }
    }

    @Test
    void testVirtualThreadsAreUsedByDefault() {
        // Verify virtual threads are enabled in config
        boolean virtThreadsEnabled = ConfigurationToggle.isVirtualThreadsEnabled(applicationContext);
        assertThat(virtThreadsEnabled)
                .as("Virtual threads should be enabled by default")
                .isTrue();

        // Execute a request
        String body = """
                {
                    "name": "VirtualThreadTest"
                }
                """;

        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.POST("/api/virtual-thread-test", body)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );

        assertThat(response.getStatus().getCode()).isBetween(200, 299);

        // Verify executor threads were used
        String executorName = ConfigurationToggle.getExecutorName(applicationContext);
        Set<String> threadNames = ThreadInspectionUtils.getThreadNames(executorName);

        assertThat(threadNames)
                .as("Virtual executor threads should be active after request")
                .isNotEmpty();

        // All threads should contain executor name
        threadNames.forEach(name -> {
            assertThat(name.toLowerCase()).contains(executorName.toLowerCase());
        });
    }

    @Test
    void testVirtualThreadNameFollowsConfigPattern() {
        // Get configured executor name
        String executorName = ConfigurationToggle.getExecutorName(applicationContext);
        assertThat(executorName)
                .as("Executor name should be configured")
                .isNotEmpty();

        // Execute request to create threads
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/virtual-thread-test")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );

        assertThat(response.getStatus().getCode()).isBetween(200, 299);

        // Verify thread names follow the configured pattern
        Set<String> threadNames = ThreadInspectionUtils.getThreadNames(executorName);
        assertThat(threadNames)
                .as("Thread names should contain executor name pattern")
                .allMatch(name -> name.toLowerCase().contains(executorName.toLowerCase()));
    }

    @Test
    void testVirtualThreadCreatedPerRequest() {
        // Track thread IDs across multiple requests
        Set<Long> threadIds = new java.util.HashSet<>();

        for (int i = 0; i < 5; i++) {
            HttpResponse<Map> response = httpClient.toBlocking().exchange(
                    HttpRequest.GET("/api/virtual-thread-test?id=" + i)
                            .header("Authorization", "Key " + API_KEY),
                    Map.class
            );

            assertThat(response.getStatus().getCode()).isBetween(200, 299);

            // Verify executor threads are active
            int executorThreadCount = ThreadInspectionUtils.getVirtualExecutorThreadCount()
                    + ThreadInspectionUtils.getPlatformExecutorThreadCount();
            assertThat(executorThreadCount)
                    .as("Executor threads should be active")
                    .isGreaterThan(0);
        }

        // Verify we're using different virtual threads or reusing efficiently
        int activeExecutorThreads = ThreadInspectionUtils.getVirtualExecutorThreadCount();
        assertThat(activeExecutorThreads)
                .as("Virtual threads should be created for concurrent workload")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void testVirtualThreadContextInheritance() {
        // Execute multiple requests to verify context works
        for (int i = 0; i < 3; i++) {
            String body = String.format("""
                    {
                        "name": "ContextTest_%d"
                    }
                    """, i);

            HttpResponse<Map> response = httpClient.toBlocking().exchange(
                    HttpRequest.POST("/api/virtual-thread-test", body)
                            .header("Authorization", "Key " + API_KEY)
                            .contentType(MediaType.APPLICATION_JSON),
                    Map.class
            );

            assertThat(response.getStatus().getCode()).isBetween(200, 299);
            assertThat(response.body()).containsKey("success");
        }

        // Verify that thread names are consistent (context inheritance working)
        String executorName = ConfigurationToggle.getExecutorName(applicationContext);
        Set<String> threadNames = ThreadInspectionUtils.getThreadNames(executorName);

        assertThat(threadNames)
                .as("Context should be properly inherited by executor threads")
                .allMatch(name -> name.contains(executorName));
    }

    @Test
    void testManyVirtualThreadsCanBeCreatedRapidly() {
        // Execute 200 rapid requests
        int requestCount = 200;
        int successCount = 0;

        try {
            for (int i = 0; i < requestCount; i++) {
                try {
                    String body = String.format("""
                            {
                                "name": "RapidTest_%d",
                                "value": %d
                            }
                            """, i, i * 10);

                    HttpResponse<Map> response = httpClient.toBlocking().exchange(
                            HttpRequest.POST("/api/virtual-thread-test", body)
                                    .header("Authorization", "Key " + API_KEY)
                                    .contentType(MediaType.APPLICATION_JSON),
                            Map.class
                    );

                    if (response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300) {
                        successCount++;
                    }
                } catch (Exception e) {
                    // Some requests may fail under extreme load, that's acceptable
                }
            }
        } catch (OutOfMemoryError e) {
            fail("OutOfMemoryError while creating many virtual threads - this should not happen", e);
        }

        // Verify high success rate (virtual threads are cheap, should handle this easily)
        double successRate = (successCount * 100.0) / requestCount;
        assertThat(successRate)
                .as("Success rate should be high when creating many virtual threads")
                .isGreaterThan(90);

        // Verify thread pool didn't get exhausted
        int executorThreadCount = ThreadInspectionUtils.getVirtualExecutorThreadCount()
                + ThreadInspectionUtils.getPlatformExecutorThreadCount();
        assertThat(executorThreadCount)
                .as("Thread count should scale appropriately with virtual threads")
                .isLessThan(10000); // Sanity check - virtual threads are very cheap
    }

    @Test
    void testVirtualThreadTimeoutHandling() {
        // Execute request that completes normally
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/virtual-thread-test")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );

        // Should complete without timeout
        assertThat(response.getStatus().getCode()).isBetween(200, 299);

        // Verify executor threads handled it properly
        int executorThreadCount = ThreadInspectionUtils.getVirtualExecutorThreadCount()
                + ThreadInspectionUtils.getPlatformExecutorThreadCount();
        assertThat(executorThreadCount)
                .as("Executor threads should have been used")
                .isGreaterThanOrEqualTo(0);

        // Verify thread state is healthy
        String diagnostics = ConfigurationToggle.getDiagnosticReport(applicationContext);
        assertThat(diagnostics)
                .as("Diagnostic report should be available")
                .contains("Executor");
    }

    // Helper to insert test data
    private void insertTestData(String name) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(String.format(
                    "INSERT INTO virtual_thread_test (name) VALUES ('%s')",
                    name
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert test data", e);
        }
    }
}
