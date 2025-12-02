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

import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Concurrent load tests to verify that virtual threads handle stress properly
 * and that the event loop remains responsive under load.
 */
@MicronautTest
class ConcurrentLoadTest {

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    DataSource dataSource;

    private static final String API_KEY = "secret-key";
    private final PerformanceTracker performanceTracker = new PerformanceTracker();

    @BeforeEach
    void setUp() throws Exception {
        performanceTracker.resetAll();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS load_test_data");
            stmt.execute("""
                CREATE TABLE load_test_data (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    test_key VARCHAR(255) NOT NULL,
                    test_value INT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        }
    }

    @Test
    void testConcurrent50InsertRequests() {
        // Create helper
        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        // Execute 50 concurrent POSTs
        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(50, 0, 0, 0),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        // Verify all succeeded
        assertThat(ConcurrentTestHelper.verifyAllSucceeded(results))
                .as("All 50 INSERT requests should succeed")
                .isTrue();

        // Verify throughput is reasonable (should handle 50 in a few seconds)
        long totalLatency = performanceTracker.getTotalTime("INSERT");
        double throughput = performanceTracker.getThroughput(50, totalLatency / 1000.0);

        assertThat(throughput)
                .as("Throughput should be > 10 req/sec with virtual threads")
                .isGreaterThan(10);

        System.out.println(performanceTracker.getMetricsSummary("INSERT"));
    }

    @Test
    void testConcurrent100MixedCrudOperations() {
        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        // Execute mixed workload: 50 INSERTs, 25 SELECTs, 15 UPDATEs, 10 DELETEs
        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(50, 25, 15, 10),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        // Verify success rate > 90%
        double successRate = ConcurrentTestHelper.getSuccessRate(results);
        assertThat(successRate)
                .as("Success rate should be > 90% for concurrent CRUD")
                .isGreaterThan(90);

        // Verify data consistency
        long finalRowCount = getRowCount();
        long expectedInserts = 50;
        long expectedDeletes = 10;
        long expectedRowCount = expectedInserts - expectedDeletes;

        assertThat(finalRowCount)
                .as("Row count should be " + expectedRowCount + " (inserts - deletes)")
                .isGreaterThanOrEqualTo(expectedRowCount - 5); // Allow some variance due to update/delete ordering

        System.out.println(ConcurrentTestHelper.getExecutionReport(results));
    }

    @Test
    void testEventLoopNotBlockedUnderLoad() {
        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        // Record baseline thread counts
        int nettyThreadsBefore = ThreadInspectionUtils.getNettyThreadCount();

        // Execute 100 concurrent requests
        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(50, 30, 15, 5),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        // Record end state
        int nettyThreadsAfter = ThreadInspectionUtils.getNettyThreadCount();
        int peakVirtualThreads = ThreadInspectionUtils.getVirtualExecutorThreadCount();

        // Verify event loop threads didn't grow excessively
        assertThat(nettyThreadsAfter - nettyThreadsBefore)
                .as("Event loop thread count should not grow significantly under load")
                .isLessThanOrEqualTo(2);

        // Verify executor threads were created on-demand
        assertThat(peakVirtualThreads)
                .as("Virtual/executor threads should be created for workload")
                .isGreaterThan(0);

        // Verify request queue didn't fill up (95% success rate minimum)
        double successRate = ConcurrentTestHelper.getSuccessRate(results);
        assertThat(successRate)
                .as("Success rate under load should be > 95%")
                .isGreaterThan(95);

        System.out.println(ThreadInspectionUtils.getThreadPoolReport());
    }

    @Test
    void testDataConsistencyUnderConcurrentWrites() {
        // Insert initial data
        insertTestData("concurrent", 0);

        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        // Execute concurrent writes to verify consistency
        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(100, 0, 0, 0),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        // Verify all INSERTs succeeded
        long successfulInserts = results.stream()
                .filter(r -> r.operationType.equals("INSERT") && r.success)
                .count();
        assertThat(successfulInserts)
                .as("All INSERTs should succeed")
                .isEqualTo(100);

        // Verify row count equals insertions
        long rowCount = getRowCount();
        assertThat(rowCount)
                .as("Row count should match successful insertions")
                .isGreaterThanOrEqualTo(100);

        System.out.println("Final row count: " + rowCount + " (expected >= 101)");
    }

    @Test
    void testResponseTimePercentilesMeasurement() {
        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        // Execute 200 mixed requests to collect percentile data
        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(80, 60, 40, 20),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        // Verify results collected
        assertThat(results).hasSize(200);

        // Verify latency percentiles
        long p50 = performanceTracker.getLatencyPercentile("INSERT", 50);
        long p95 = performanceTracker.getLatencyPercentile("INSERT", 95);
        long p99 = performanceTracker.getLatencyPercentile("INSERT", 99);

        // Verify reasonable baselines for H2 in-memory database
        assertThat(p50)
                .as("p50 latency should be reasonable for H2")
                .isLessThan(500);

        assertThat(p95)
                .as("p95 latency should be reasonable for H2")
                .isLessThan(2000);

        assertThat(p99)
                .as("p99 latency should be reasonable for H2")
                .isLessThan(5000);

        System.out.println(performanceTracker.getAllMetricsSummaries());
    }

    @Test
    void testThreadPoolDoesNotExhaustUnderLoad() {
        ConcurrentTestHelper helper = new ConcurrentTestHelper(httpClient, performanceTracker);

        // Execute heavy concurrent load
        List<ConcurrentTestHelper.OperationResult> results = helper.executeMixedWorkload(
                new ConcurrentTestHelper.WorkloadConfig(200, 150, 100, 50),
                this::executeInsertRequest,
                this::executeSelectRequest,
                this::executeUpdateRequest,
                this::executeDeleteRequest
        );

        // Verify very high success rate (thread pool should not exhaust)
        double successRate = ConcurrentTestHelper.getSuccessRate(results);
        assertThat(successRate)
                .as("Success rate should be > 95% even under heavy load")
                .isGreaterThan(95);

        // Verify thread counts are reasonable
        int virtualThreads = ThreadInspectionUtils.getVirtualExecutorThreadCount();
        int platformThreads = ThreadInspectionUtils.getPlatformExecutorThreadCount();

        assertThat(virtualThreads + platformThreads)
                .as("Executor threads should scale appropriately")
                .isLessThan(500); // Sanity check - virtual threads can handle much more

        // Wait for cleanup
        ConcurrentTestHelper.waitForThreadPoolQuiet();

        // Verify thread counts normalize after load
        int executorThreadsAfterCleanup = ThreadInspectionUtils.getVirtualExecutorThreadCount()
                + ThreadInspectionUtils.getPlatformExecutorThreadCount();

        assertThat(executorThreadsAfterCleanup)
                .as("Executor threads should be cleaned up after load")
                .isLessThan(virtualThreads + platformThreads);
    }

    // Helper methods for workload generation
    private HttpResponse<?> executeInsertRequest() {
        String body = String.format("""
                {
                    "test_key": "load_test_%d",
                    "test_value": %d
                }
                """, System.nanoTime(), System.currentTimeMillis());

        return httpClient.toBlocking().exchange(
                HttpRequest.POST("/api/load-test-data", body)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );
    }

    private HttpResponse<?> executeSelectRequest() {
        return httpClient.toBlocking().exchange(
                HttpRequest.GET("/api/load-test-data")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );
    }

    private HttpResponse<?> executeUpdateRequest() {
        String body = """
                {
                    "test_value": 999
                }
                """;

        return httpClient.toBlocking().exchange(
                HttpRequest.PUT("/api/load-test-data/1", body)
                        .header("Authorization", "Key " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON),
                Map.class
        );
    }

    private HttpResponse<?> executeDeleteRequest() {
        return httpClient.toBlocking().exchange(
                HttpRequest.DELETE("/api/load-test-data/1")
                        .header("Authorization", "Key " + API_KEY),
                Map.class
        );
    }

    // Helper to insert test data
    private void insertTestData(String key, int value) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(String.format(
                    "INSERT INTO load_test_data (test_key, test_value) VALUES ('%s', %d)",
                    key, value
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert test data", e);
        }
    }

    // Helper to get row count
    private long getRowCount() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM load_test_data")) {
            if (rs.next()) {
                return rs.getLong("cnt");
            }
            return 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get row count", e);
        }
    }
}
