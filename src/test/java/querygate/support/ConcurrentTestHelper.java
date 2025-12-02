package querygate.support;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.BlockingHttpClient;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Helper utility for concurrent and load testing.
 * Provides methods to execute multiple requests in parallel and verify consistency.
 */
public class ConcurrentTestHelper {

    private final BlockingHttpClient httpClient;
    private final PerformanceTracker performanceTracker;

    public ConcurrentTestHelper(BlockingHttpClient httpClient, PerformanceTracker performanceTracker) {
        this.httpClient = httpClient;
        this.performanceTracker = performanceTracker;
    }

    /**
     * Container class for concurrent CRUD operation counts.
     */
    public static class WorkloadConfig {
        public int inserts;
        public int selects;
        public int updates;
        public int deletes;

        public WorkloadConfig(int inserts, int selects, int updates, int deletes) {
            this.inserts = inserts;
            this.selects = selects;
            this.updates = updates;
            this.deletes = deletes;
        }

        public int getTotalOperations() {
            return inserts + selects + updates + deletes;
        }
    }

    /**
     * Container class for operation result.
     */
    public static class OperationResult {
        public String operationType;
        public int statusCode;
        public long latencyMs;
        public boolean success;
        public String threadName;

        public OperationResult(String operationType, int statusCode, long latencyMs, boolean success, String threadName) {
            this.operationType = operationType;
            this.statusCode = statusCode;
            this.latencyMs = latencyMs;
            this.success = success;
            this.threadName = threadName;
        }

        @Override
        public String toString() {
            return String.format("%s [%d] %dms (%s)", operationType, statusCode, latencyMs, threadName);
        }
    }

    /**
     * Execute a supplier concurrently and collect all results.
     *
     * @param requestCount the number of concurrent requests
     * @param supplier the supplier that returns an HttpResponse
     * @return list of all responses
     */
    public <T> List<HttpResponse<T>> executeParallel(int requestCount, Supplier<HttpResponse<T>> supplier) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(requestCount, 20));
        List<Future<HttpResponse<T>>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(supplier::get));
            }

            List<HttpResponse<T>> results = new ArrayList<>();
            for (Future<HttpResponse<T>> future : futures) {
                try {
                    results.add(future.get(10, TimeUnit.SECONDS));
                } catch (TimeoutException e) {
                    throw new RuntimeException("Request timed out", e);
                }
            }

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Execute a workload of mixed CRUD operations concurrently.
     *
     * @param config the workload configuration
     * @param insertSupplier the INSERT operation supplier
     * @param selectSupplier the SELECT operation supplier
     * @param updateSupplier the UPDATE operation supplier
     * @param deleteSupplier the DELETE operation supplier
     * @return list of operation results
     */
    public List<OperationResult> executeMixedWorkload(
            WorkloadConfig config,
            Supplier<HttpResponse<?>> insertSupplier,
            Supplier<HttpResponse<?>> selectSupplier,
            Supplier<HttpResponse<?>> updateSupplier,
            Supplier<HttpResponse<?>> deleteSupplier) {

        List<OperationResult> results = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(config.getTotalOperations(), 50));
        List<Future<?>> futures = new ArrayList<>();

        try {
            // Submit INSERT operations
            for (int i = 0; i < config.inserts; i++) {
                futures.add(executor.submit(() -> {
                    long startTime = System.currentTimeMillis();
                    try {
                        HttpResponse<?> response = insertSupplier.get();
                        long latency = System.currentTimeMillis() - startTime;
                        boolean success = response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300;
                        results.add(new OperationResult("INSERT", response.getStatus().getCode(), latency, success, ThreadInspectionUtils.getExecutingThreadName()));
                        performanceTracker.recordLatency("INSERT", latency);
                    } catch (Exception e) {
                        long latency = System.currentTimeMillis() - startTime;
                        results.add(new OperationResult("INSERT", 0, latency, false, ThreadInspectionUtils.getExecutingThreadName()));
                    }
                }));
            }

            // Submit SELECT operations
            for (int i = 0; i < config.selects; i++) {
                futures.add(executor.submit(() -> {
                    long startTime = System.currentTimeMillis();
                    try {
                        HttpResponse<?> response = selectSupplier.get();
                        long latency = System.currentTimeMillis() - startTime;
                        boolean success = response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300;
                        results.add(new OperationResult("SELECT", response.getStatus().getCode(), latency, success, ThreadInspectionUtils.getExecutingThreadName()));
                        performanceTracker.recordLatency("SELECT", latency);
                    } catch (Exception e) {
                        long latency = System.currentTimeMillis() - startTime;
                        results.add(new OperationResult("SELECT", 0, latency, false, ThreadInspectionUtils.getExecutingThreadName()));
                    }
                }));
            }

            // Submit UPDATE operations
            for (int i = 0; i < config.updates; i++) {
                futures.add(executor.submit(() -> {
                    long startTime = System.currentTimeMillis();
                    try {
                        HttpResponse<?> response = updateSupplier.get();
                        long latency = System.currentTimeMillis() - startTime;
                        boolean success = response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300;
                        results.add(new OperationResult("UPDATE", response.getStatus().getCode(), latency, success, ThreadInspectionUtils.getExecutingThreadName()));
                        performanceTracker.recordLatency("UPDATE", latency);
                    } catch (Exception e) {
                        long latency = System.currentTimeMillis() - startTime;
                        results.add(new OperationResult("UPDATE", 0, latency, false, ThreadInspectionUtils.getExecutingThreadName()));
                    }
                }));
            }

            // Submit DELETE operations
            for (int i = 0; i < config.deletes; i++) {
                futures.add(executor.submit(() -> {
                    long startTime = System.currentTimeMillis();
                    try {
                        HttpResponse<?> response = deleteSupplier.get();
                        long latency = System.currentTimeMillis() - startTime;
                        boolean success = response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300;
                        results.add(new OperationResult("DELETE", response.getStatus().getCode(), latency, success, ThreadInspectionUtils.getExecutingThreadName()));
                        performanceTracker.recordLatency("DELETE", latency);
                    } catch (Exception e) {
                        long latency = System.currentTimeMillis() - startTime;
                        results.add(new OperationResult("DELETE", 0, latency, false, ThreadInspectionUtils.getExecutingThreadName()));
                    }
                }));
            }

            // Wait for all to complete
            for (Future<?> future : futures) {
                try {
                    future.get(15, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    throw new RuntimeException("Workload operation timed out", e);
                }
            }

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Verify that all operations succeeded.
     *
     * @param results the operation results
     * @return true if all succeeded
     */
    public static boolean verifyAllSucceeded(List<OperationResult> results) {
        return results.stream().allMatch(r -> r.success);
    }

    /**
     * Get the success rate of operations.
     *
     * @param results the operation results
     * @return success rate as a percentage (0-100)
     */
    public static double getSuccessRate(List<OperationResult> results) {
        if (results.isEmpty()) {
            return 0;
        }
        long successful = results.stream().filter(r -> r.success).count();
        return (successful * 100.0) / results.size();
    }

    /**
     * Get the count of each operation type.
     *
     * @param results the operation results
     * @return map of operation type to count
     */
    public static Map<String, Integer> getOperationCounts(List<OperationResult> results) {
        Map<String, Integer> counts = new HashMap<>();
        for (OperationResult result : results) {
            counts.put(result.operationType, counts.getOrDefault(result.operationType, 0) + 1);
        }
        return counts;
    }

    /**
     * Get the average latency for a specific operation type.
     *
     * @param results the operation results
     * @param operationType the operation type (e.g., "INSERT", "SELECT")
     * @return average latency in milliseconds
     */
    public static long getAverageLatencyForType(List<OperationResult> results, String operationType) {
        return Math.round(results.stream()
                .filter(r -> r.operationType.equals(operationType))
                .mapToLong(r -> r.latencyMs)
                .average()
                .orElse(0));
    }

    /**
     * Wait for the thread pool to become quiet (no active tasks).
     * Useful after concurrent operations to ensure cleanup.
     */
    public static void waitForThreadPoolQuiet() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get a report of concurrent execution results.
     *
     * @param results the operation results
     * @return formatted report string
     */
    public static String getExecutionReport(List<OperationResult> results) {
        if (results.isEmpty()) {
            return "No results to report";
        }

        StringBuilder report = new StringBuilder();
        report.append("\n=== Concurrent Execution Report ===\n");
        report.append("Total operations: ").append(results.size()).append("\n");
        report.append("Success rate: ").append(String.format("%.2f%%", getSuccessRate(results))).append("\n");
        report.append("Failed operations: ").append(results.stream().filter(r -> !r.success).count()).append("\n\n");

        getOperationCounts(results).forEach((type, count) -> {
            report.append(type).append(": ").append(count)
                    .append(" (avg latency: ").append(getAverageLatencyForType(results, type)).append("ms)\n");
        });

        return report.toString();
    }
}
