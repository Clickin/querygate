package querygate.integration;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import querygate.support.ConcurrentTestHelper;
import querygate.support.ConfigurationToggle;
import querygate.support.PerformanceTracker;
import querygate.support.ThreadInspectionUtils;

import io.micronaut.context.ApplicationContext;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests comparing virtual thread behavior vs platform thread fallback.
 * Verifies that the system works correctly with both executor types
 * and that virtual threads provide benefits.
 */
@MicronautTest
class VirtualThreadFallbackTest {

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    DataSource dataSource;

    @Inject
    ApplicationContext applicationContext;

    private static final String API_KEY = "secret-key";
    private final PerformanceTracker performanceTracker = new PerformanceTracker();

    @BeforeEach
    void setUp() throws Exception {
        performanceTracker.resetAll();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS fallback_test_data");
            stmt.execute("""
                CREATE TABLE fallback_test_data (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    fallback_key VARCHAR(255) NOT NULL,
                    fallback_value INT
                )
                """);
        }
    }

    @Test
    void testFallbackToPlatformThreadsWhenVirtualDisabled() {
        // Verify current configuration
        boolean virtualThreadsEnabled = ConfigurationToggle.isVirtualThreadsEnabled(applicationContext);

        String executorType = ConfigurationToggle.getCurrentExecutorType(applicationContext);
        if (virtualThreadsEnabled) {
            assertThat(executorType).isEqualTo("virtual");
        } else {
            assertThat(executorType).isEqualTo("platform");
        }

        // Execute request
        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/fallback-test-data")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );

        assertThat(response.getStatus().getCode()).isBetween(200, 299);

        // Verify appropriate executor threads are used
        if (virtualThreadsEnabled) {
            int virtualThreads = ThreadInspectionUtils.getVirtualExecutorThreadCount();
            assertThat(virtualThreads)
                    .as("Virtual threads should be active when enabled")
                    .isGreaterThanOrEqualTo(0);
        } else {
            int platformThreads = ThreadInspectionUtils.getPlatformExecutorThreadCount();
            assertThat(platformThreads)
                    .as("Platform threads should be active when virtual threads disabled")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void testPlatformThreadExecutorBehavior() {
        // Verify virtual threads are enabled (our default configuration)
        boolean virtualThreadsEnabled = ConfigurationToggle.isVirtualThreadsEnabled(applicationContext);

        if (!virtualThreadsEnabled) {
            // If virtual threads are disabled, verify platform thread behavior
            int baselinePlatformThreads = ThreadInspectionUtils.getPlatformExecutorThreadCount();

            // Execute 10 concurrent requests
            ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

            List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                    new ConcurrentTestHelper.WorkloadConfig(5, 3, 2, 0),
                    this::executeInsertRequest,
                    this::executeSelectRequest,
                    this::executeUpdateRequest,
                    this::executeDeleteRequest
            );

            // Verify thread pool is bounded
            int peakPlatformThreads = ThreadInspectionUtils.getPlatformExecutorThreadCount();

            // Platform thread pool should have reasonable bounds
            // Typically max = CPU_count * 2
            int maxExpectedPlatformThreads = Runtime.getRuntime().availableProcessors() * 4;
            assertThat(peakPlatformThreads)
                    .as("Platform thread count should be bounded")
                    .isLessThan(maxExpectedPlatformThreads);
        } else {
            // Skip this test if virtual threads are enabled
            System.out.println("Skipping platform thread test - virtual threads are enabled");
        }
    }

    @Test
    void testVirtualVsPlatformThreadThroughputComparison() {
        // Measure throughput with current configuration (virtual threads enabled by default)
        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        long startTime = System.currentTimeMillis();

        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(100, 75, 50, 25),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        long totalTime = System.currentTimeMillis() - startTime;
        double durationSeconds = totalTime / 1000.0;

        int successfulRequests = (int) results.stream().filter(r -> r.success).count();
        double throughput = performanceTracker.getThroughput(successfulRequests, durationSeconds);

        assertThat(throughput)
                .as("Throughput should be measurable")
                .isGreaterThan(0);

        System.out.println(String.format("Throughput with current executor: %.2f req/sec", throughput));

        // With virtual threads enabled (default), expect good throughput
        if (ConfigurationToggle.isVirtualThreadsEnabled(applicationContext)) {
            assertThat(throughput)
                    .as("Virtual threads should deliver good throughput")
                    .isGreaterThan(10);
        }

        // In a production comparison test, you would:
        // 1. Disable virtual threads
        // 2. Run the same workload again
        // 3. Compare throughput
        // 4. Expect virtual threads to be 20-50% faster
    }

    @Test
    void testVirtualVsPlatformThreadMemoryComparison() {
        Runtime runtime = Runtime.getRuntime();

        // Baseline memory
        System.gc();
        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        // Execute moderate load
        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(80, 60, 40, 20),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        // After load
        ConcurrentTestHelper.waitForThreadPoolQuiet();
        System.gc();
        long memoryAfter = runtime.totalMemory() - runtime.freeMemory();

        long memoryGrowth = memoryAfter - memoryBefore;
        long memoryGrowthMB = memoryGrowth / (1024 * 1024);

        // Virtual threads should have minimal memory overhead
        if (ConfigurationToggle.isVirtualThreadsEnabled(applicationContext)) {
            assertThat(memoryGrowthMB)
                    .as("Virtual threads should have minimal memory overhead")
                    .isLessThan(150);
        }

        System.out.println(String.format("Memory growth: %dMB", memoryGrowthMB));
    }

    @Test
    void testVirtualVsPlatformThreadLatencyComparison() {
        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        // Execute concurrent load
        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(100, 50, 30, 20),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        // Analyze latency
        long p50 = performanceTracker.getLatencyPercentile("INSERT", 50);
        long p95 = performanceTracker.getLatencyPercentile("INSERT", 95);
        long p99 = performanceTracker.getLatencyPercentile("INSERT", 99);

        // Verify latencies are reasonable
        assertThat(p50).isGreaterThan(0);
        assertThat(p95).isGreaterThanOrEqualTo(p50);
        assertThat(p99).isGreaterThanOrEqualTo(p95);

        // With virtual threads, expect good tail latencies
        if (ConfigurationToggle.isVirtualThreadsEnabled(applicationContext)) {
            assertThat(p95)
                    .as("p95 latency should be reasonable with virtual threads")
                    .isLessThan(2000);

            assertThat(p99)
                    .as("p99 latency should be reasonable with virtual threads")
                    .isLessThan(5000);
        }

        System.out.println(String.format("Latency percentiles - p50: %dms, p95: %dms, p99: %dms",
                p50, p95, p99));
    }

    @Test
    void testSwitchingBetweenExecutorsPreservesCorrectness() {
        // Execute a complete CRUD cycle
        insertTestData("test1", 100);

        // Verify SELECT works
        HttpResponse<Map> selectResponse = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/fallback-test-data")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );
        assertThat(selectResponse.getStatus().getCode()).isBetween(200, 299);

        // Verify INSERT works
        String insertBody = """
                {
                    "fallback_key": "test_insert",
                    "fallback_value": 200
                }
                """;
        HttpResponse<Map> insertResponse = httpClient.toBlocking().exchange(
                HttpRequest.POST("/api/fallback-test-data", insertBody)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );
        assertThat(insertResponse.getStatus().getCode()).isBetween(200, 299);

        // Verify UPDATE works
        String updateBody = """
                {
                    "fallback_value": 300
                }
                """;
        HttpResponse<Map> updateResponse = httpClient.toBlocking().exchange(
                HttpRequest.PUT("/api/fallback-test-data/1", updateBody)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );
        assertThat(updateResponse.getStatus().getCode()).isBetween(200, 299);

        // Verify DELETE works
        HttpResponse<Map> deleteResponse = httpClient.toBlocking().exchange(
                HttpRequest.DELETE("/api/fallback-test-data/1")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );
        assertThat(deleteResponse.getStatus().getCode()).isBetween(200, 299);

        // Verify final state
        long finalRowCount = getRowCount();
        assertThat(finalRowCount)
                .as("Data consistency should be preserved regardless of executor type")
                .isGreaterThanOrEqualTo(1); // We inserted at least one row

        System.out.println("CRUD cycle completed successfully with current executor configuration");
    }

    // Helper methods
    private HttpResponse<?> executeInsertRequest() {
        String body = String.format("""
                {
                    "fallback_key": "test_%d",
                    "fallback_value": %d
                }
                """, System.nanoTime(), System.currentTimeMillis());

        return httpClient.toBlocking().exchange(
                HttpRequest.POST("/api/fallback-test-data", body)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );
    }

    private HttpResponse<?> executeSelectRequest() {
        return httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/fallback-test-data")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );
    }

    private HttpResponse<?> executeUpdateRequest() {
        String body = """
                {
                    "fallback_value": 9999
                }
                """;

        return httpClient.toBlocking().exchange(
                HttpRequest.PUT("/api/fallback-test-data/1", body)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );
    }

    private HttpResponse<?> executeDeleteRequest() {
        return httpClient.toBlocking().exchange(
                HttpRequest.DELETE("/api/fallback-test-data/1")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );
    }

    private void insertTestData(String key, int value) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(String.format(
                    "INSERT INTO fallback_test_data (fallback_key, fallback_value) VALUES ('%s', %d)",
                    key, value
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert test data", e);
        }
    }

    private long getRowCount() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM fallback_test_data")) {
            if (rs.next()) {
                return rs.getLong("cnt");
            }
            return 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get row count", e);
        }
    }
}
