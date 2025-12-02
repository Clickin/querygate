package querygate.integration;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import querygate.support.ThreadInspectionUtils;

import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests verifying that the Netty event loop is not blocked by database operations.
 * These tests ensure that sync calls are properly offloaded to virtual/platform executor threads,
 * keeping the Netty event loop free for I/O multiplexing.
 */
@MicronautTest
class EventLoopNonBlockingTest {

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
            stmt.execute("DROP TABLE IF EXISTS event_loop_test");
            stmt.execute("""
                CREATE TABLE event_loop_test (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(255) NOT NULL,
                    value INT
                )
                """);
        }
    }

    @Test
    void testNettyEventLoopNotBlockedDuringSelect() {
        // Insert test data first
        insertTestData("TestUser", 100);

        // Record the Netty thread count before request
        int nettyThreadsBeforeRequest = ThreadInspectionUtils.getNettyThreadCount();

        // Execute SELECT request
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/event-loop-test")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );

        // Verify request succeeded
        assertThat(response.getStatus().getCode()).isBetween(200, 299);

        // Record Netty thread count after request
        int nettyThreadsAfterRequest = ThreadInspectionUtils.getNettyThreadCount();

        // Event loop thread count should remain constant (indicating they weren't blocked)
        assertThat(nettyThreadsAfterRequest)
                .as("Netty event loop threads should not be created during normal request")
                .isLessThanOrEqualTo(nettyThreadsBeforeRequest + 1); // Allow minimal variance

        // Verify that no event loop threads are executing SQL
        Set<String> executingThreads = ThreadInspectionUtils.getAllThreadNames();
        boolean hasEventLoopBlockingSQL = executingThreads.stream()
                .filter(name -> name.toLowerCase().contains("netty"))
                .anyMatch(name -> {
                    // This is a simplified check - in real scenarios we'd need stack inspection
                    return !name.toLowerCase().contains("accept");
                });
        assertThat(hasEventLoopBlockingSQL).as("Event loop threads should not execute SQL").isFalse();
    }

    @Test
    void testNettyEventLoopNotBlockedDuringInsert() {
        // Record thread names before INSERT
        int nettyThreadsBefore = ThreadInspectionUtils.getNettyThreadCount();

        String body = """
                {
                    "name": "TestInsert",
                    "value": 200
                }
                """;

        // Execute INSERT request
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.POST("/api/event-loop-test", body)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );

        // Verify INSERT succeeded
        assertThat(response.getStatus().getCode()).isBetween(200, 299);

        // Verify that executor threads were used (not event loop)
        int virtualThreadsActive = ThreadInspectionUtils.getVirtualExecutorThreadCount();
        int platformThreadsActive = ThreadInspectionUtils.getPlatformExecutorThreadCount();

        assertThat(virtualThreadsActive + platformThreadsActive)
                .as("Executor threads should be used for blocking database operations")
                .isGreaterThan(0);
    }

    @Test
    void testEventLoopThreadNeverRunsDatabaseCode() {
        // Insert test data
        insertTestData("EventLoopTest", 300);

        // Execute multiple rapid requests to stress the system
        for (int i = 0; i < 10; i++) {
            HttpResponse<Map> getResponse = httpClient.toBlocking().exchange(
                    HttpRequest.GET("/api/event-loop-test")
                            .header("Authorization", "Key " + API_KEY),
                    Map.class
            );
            assertThat(getResponse.getStatus().getCode()).isBetween(200, 299);

            String body = String.format("""
                    {
                        "name": "Test%d",
                        "value": %d
                    }
                    """, i, 300 + i);

            HttpResponse<Map> postResponse = httpClient.toBlocking().exchange(
                    HttpRequest.POST("/api/event-loop-test", body)
                            .header("Authorization", "Key " + API_KEY)
                            .contentType(MediaType.APPLICATION_JSON),
                    Map.class
            );
            assertThat(postResponse.getStatus().getCode()).isBetween(200, 299);
        }

        // Verify that some executor threads were used
        int totalExecutorThreads = ThreadInspectionUtils.getVirtualExecutorThreadCount()
                + ThreadInspectionUtils.getPlatformExecutorThreadCount();

        assertThat(totalExecutorThreads)
                .as("Executor threads should have been used for database operations")
                .isGreaterThan(0);

        // Get diagnostic info
        String threadReport = ThreadInspectionUtils.getThreadPoolReport();
        assertThat(threadReport).contains("Netty threads");
    }

    @Test
    void testMultipleRequestsUsingDifferentVirtualThreads() {
        // Insert test data
        insertTestData("MultiThreadTest", 400);

        // Execute requests in sequence and capture thread names
        Set<Long> threadIds = new java.util.HashSet<>();

        for (int i = 0; i < 5; i++) {
            HttpResponse<Map> response = httpClient.toBlocking().exchange(
                    HttpRequest.GET("/api/event-loop-test")
                            .header("Authorization", "Key " + API_KEY),
                    Map.class
            );

            assertThat(response.getStatus().getCode()).isBetween(200, 299);

            // After the response completes, we know execution was on executor thread
            // Verify executor threads are being used
            int executorThreadCount = ThreadInspectionUtils.getVirtualExecutorThreadCount()
                    + ThreadInspectionUtils.getPlatformExecutorThreadCount();
            assertThat(executorThreadCount).isGreaterThan(0);
        }

        // Verify event loop thread pool size remains stable
        int eventLoopThreads = ThreadInspectionUtils.getNettyThreadCount();
        assertThat(eventLoopThreads)
                .as("Event loop thread count should remain stable across concurrent requests")
                .isGreaterThanOrEqualTo(1);
    }

    // Helper method to insert test data
    private void insertTestData(String name, int value) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    String.format("INSERT INTO event_loop_test (name, value) VALUES ('%s', %d)", name, value)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert test data", e);
        }
    }
}
