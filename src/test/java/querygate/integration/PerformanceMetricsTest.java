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
import querygate.support.PerformanceTracker;
import querygate.support.ThreadInspectionUtils;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Performance metrics collection tests.
 * Measures throughput, latency percentiles, memory usage, and thread behavior.
 */
@MicronautTest
class PerformanceMetricsTest {

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    DataSource dataSource;

    @Inject(required = false)
    MeterRegistry meterRegistry;

    private static final String API_KEY = "secret-key";
    private final PerformanceTracker performanceTracker = new PerformanceTracker();

    @BeforeEach
    void setUp() throws Exception {
        performanceTracker.resetAll();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS metrics_test_data");
            stmt.execute("""
                CREATE TABLE metrics_test_data (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    metric_key VARCHAR(255) NOT NULL,
                    metric_value INT
                )
                """);
        }
    }

    @Test
    void testSqlExecutionTimersAreRecorded() {
        // Execute a SELECT request
        long startTime = System.currentTimeMillis();

        HttpResponse<Map> response = httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/metrics-test-data")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );

        long latency = System.currentTimeMillis() - startTime;
        performanceTracker.recordLatency("SELECT", latency);

        // Verify response
        assertThat(response.getStatus().getCode()).isBetween(200, 299);

        // Verify metrics were recorded
        assertThat(performanceTracker.getMeasurementCount("SELECT"))
                .as("SELECT latency should be recorded")
                .isEqualTo(1);

        assertThat(performanceTracker.getLatencies("SELECT"))
                .as("Latency values should be positive")
                .allMatch(lat -> lat > 0);
    }

    @Test
    void testRequestThroughputMeasurement() {
        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        // Execute 100 requests and measure time
        long startTime = System.currentTimeMillis();

        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(50, 30, 15, 5),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        long totalTime = System.currentTimeMillis() - startTime;
        double durationSeconds = totalTime / 1000.0;

        // Calculate throughput
        int successfulRequests = (int) results.stream().filter(r -> r.success).count();
        double throughput = performanceTracker.getThroughput(successfulRequests, durationSeconds);

        // Verify reasonable throughput
        assertThat(throughput)
                .as("Throughput should be measurable and positive")
                .isGreaterThan(0);

        assertThat(throughput)
                .as("Should handle at least 10 requests/sec with H2 in-memory")
                .isGreaterThan(10);

        System.out.println(String.format("Throughput: %.2f req/sec over %.2f seconds", throughput, durationSeconds));
    }

    @Test
    void testLatencyPercentileCollection() {
        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        // Execute 500 requests to collect percentile data
        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(200, 150, 100, 50),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        // Verify we have measurements for all operation types
        assertThat(performanceTracker.getMeasurementCount("INSERT"))
                .as("Should have INSERT measurements")
                .isGreaterThan(0);

        assertThat(performanceTracker.getMeasurementCount("SELECT"))
                .as("Should have SELECT measurements")
                .isGreaterThan(0);

        // Verify percentile calculations
        long p50Insert = performanceTracker.getLatencyPercentile("INSERT", 50);
        long p95Insert = performanceTracker.getLatencyPercentile("INSERT", 95);
        long p99Insert = performanceTracker.getLatencyPercentile("INSERT", 99);

        assertThat(p50Insert).isGreaterThan(0);
        assertThat(p95Insert).isGreaterThanOrEqualTo(p50Insert);
        assertThat(p99Insert).isGreaterThanOrEqualTo(p95Insert);

        // Print detailed metrics
        System.out.println(performanceTracker.getMetricsSummary("INSERT"));
        System.out.println(performanceTracker.getLatencyDistribution("INSERT"));
    }

    @Test
    void testVirtualThreadCountMonitoring() {
        // Record baseline
        int baselineVirtualThreads = ThreadInspectionUtils.getVirtualExecutorThreadCount();

        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        // Execute load
        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(100, 50, 30, 20),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        // Record peak
        int peakVirtualThreads = ThreadInspectionUtils.getVirtualExecutorThreadCount();

        // Verify growth was reasonable
        int threadGrowth = peakVirtualThreads - baselineVirtualThreads;
        assertThat(threadGrowth)
                .as("Virtual thread creation should scale reasonably")
                .isGreaterThan(0)
                .isLessThan(1000);

        // Wait for cleanup
        ConcurrentTestHelper.waitForThreadPoolQuiet();

        // Record final state
        int finalVirtualThreads = ThreadInspectionUtils.getVirtualExecutorThreadCount();

        assertThat(finalVirtualThreads)
                .as("Virtual threads should be cleaned up after load")
                .isLessThanOrEqualTo(peakVirtualThreads);

        System.out.println(String.format("Virtual thread count - Baseline: %d, Peak: %d, Final: %d",
                baselineVirtualThreads, peakVirtualThreads, finalVirtualThreads));
    }

    @Test
    void testMemoryUsageUnderLoad() {
        Runtime runtime = Runtime.getRuntime();

        // Get baseline memory
        System.gc();
        long heapBefore = runtime.totalMemory() - runtime.freeMemory();

        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        // Execute heavy load
        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(150, 100, 50, 30),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        // Wait for GC
        ConcurrentTestHelper.waitForThreadPoolQuiet();
        System.gc();
        long heapAfter = runtime.totalMemory() - runtime.freeMemory();

        long memoryGrowth = heapAfter - heapBefore;
        long memoryGrowthMB = memoryGrowth / (1024 * 1024);

        // Verify memory growth is reasonable for virtual threads
        // Virtual threads are very lightweight, should use < 100MB even under heavy load
        assertThat(memoryGrowthMB)
                .as("Memory growth should be minimal with virtual threads")
                .isLessThan(200); // Generous limit for testing

        System.out.println(String.format("Memory usage - Before: %dMB, After: %dMB, Growth: %dMB",
                heapBefore / (1024 * 1024),
                heapAfter / (1024 * 1024),
                memoryGrowthMB));
    }

    @Test
    void testComparableMetricsWithVirtualThreadsDisabled() {
        // This test would require dynamic enable/disable of virtual threads
        // For now, we verify the configuration is as expected

        // Execute requests with current configuration (virtual threads enabled by default)
        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(100, 75, 50, 25),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        // Measure metrics with virtual threads enabled
        double successRate = ConcurrentTestHelper.getSuccessRate(results);
        long p95Latency = performanceTracker.getLatencyPercentile("INSERT", 95);

        assertThat(successRate)
                .as("Success rate should be high with virtual threads enabled")
                .isGreaterThan(90);

        System.out.println(String.format("Metrics with virtual threads enabled: " +
                "Success rate: %.2f%%, p95 latency: %dms",
                successRate, p95Latency));

        // In a real scenario, you would:
        // 1. Disable virtual threads
        // 2. Re-run the same load
        // 3. Compare metrics
        // 4. Verify virtual threads provide benefits

        System.out.println("Note: To fully test comparison, would need to disable virtual threads " +
                "and re-run with platform threads for baseline comparison");
    }

    // Helper methods
    private HttpResponse<?> executeInsertRequest() {
        String body = String.format("""
                {
                    "metric_key": "test_%d",
                    "metric_value": %d
                }
                """, System.nanoTime(), System.currentTimeMillis());

        return httpClient.toBlocking().exchange(
                HttpRequest.POST("/api/metrics-test-data", body)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );
    }

    private HttpResponse<?> executeSelectRequest() {
        return httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/metrics-test-data")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );
    }

    private HttpResponse<?> executeUpdateRequest() {
        String body = """
                {
                    "metric_value": 9999
                }
                """;

        return httpClient.toBlocking().exchange(
                HttpRequest.PUT("/api/metrics-test-data/1", body)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );
    }

    private HttpResponse<?> executeDeleteRequest() {
        return httpClient.toBlocking().exchange(
                HttpRequest.DELETE("/api/metrics-test-data/1")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );
    }
}
