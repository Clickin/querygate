package querygate.support;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Utility class for tracking and collecting performance metrics during testing.
 * Provides methods to measure latency, throughput, and percentile calculations.
 */
public class PerformanceTracker {

    private final Map<String, Long> timers = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> latencies = new ConcurrentHashMap<>();
    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();

    /**
     * Start a timer for a named operation.
     *
     * @param name the operation name
     */
    public void startTimer(String name) {
        startTimes.put(name, System.currentTimeMillis());
    }

    /**
     * Stop a timer and record the elapsed time in milliseconds.
     *
     * @param name the operation name
     * @return the elapsed time in milliseconds
     */
    public long stopTimer(String name) {
        Long startTime = startTimes.remove(name);
        if (startTime == null) {
            throw new IllegalStateException("Timer '" + name + "' was not started");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        timers.put(name, elapsed);
        return elapsed;
    }

    /**
     * Record a latency measurement for an operation.
     *
     * @param operation the operation name
     * @param latencyMs the latency in milliseconds
     */
    public void recordLatency(String operation, long latencyMs) {
        latencies.computeIfAbsent(operation, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(latencyMs);
    }

    /**
     * Get all recorded latencies for an operation.
     *
     * @param operation the operation name
     * @return list of recorded latencies
     */
    public List<Long> getLatencies(String operation) {
        return new ArrayList<>(latencies.getOrDefault(operation, new ArrayList<>()));
    }

    /**
     * Calculate the latency percentile for an operation.
     *
     * @param operation the operation name
     * @param percentile the percentile (0-100)
     * @return the latency value at that percentile
     */
    public long getLatencyPercentile(String operation, double percentile) {
        List<Long> lats = getLatencies(operation);
        if (lats.isEmpty()) {
            throw new IllegalStateException("No latencies recorded for operation: " + operation);
        }

        Collections.sort(lats);
        int index = (int) Math.ceil((percentile / 100.0) * lats.size()) - 1;
        index = Math.max(0, Math.min(index, lats.size() - 1));
        return lats.get(index);
    }

    /**
     * Get the average latency for an operation.
     *
     * @param operation the operation name
     * @return the average latency in milliseconds
     */
    public long getAverageLatency(String operation) {
        List<Long> lats = getLatencies(operation);
        if (lats.isEmpty()) {
            throw new IllegalStateException("No latencies recorded for operation: " + operation);
        }
        return Math.round(lats.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    /**
     * Get the minimum latency for an operation.
     *
     * @param operation the operation name
     * @return the minimum latency in milliseconds
     */
    public long getMinLatency(String operation) {
        List<Long> lats = getLatencies(operation);
        if (lats.isEmpty()) {
            throw new IllegalStateException("No latencies recorded for operation: " + operation);
        }
        return lats.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    /**
     * Get the maximum latency for an operation.
     *
     * @param operation the operation name
     * @return the maximum latency in milliseconds
     */
    public long getMaxLatency(String operation) {
        List<Long> lats = getLatencies(operation);
        if (lats.isEmpty()) {
            throw new IllegalStateException("No latencies recorded for operation: " + operation);
        }
        return lats.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    /**
     * Calculate throughput (operations per second) for a set of operations.
     *
     * @param operationCount the total number of operations
     * @param durationSeconds the duration in seconds
     * @return throughput in operations per second
     */
    public double getThroughput(int operationCount, double durationSeconds) {
        if (durationSeconds == 0) {
            throw new IllegalArgumentException("Duration cannot be zero");
        }
        return operationCount / durationSeconds;
    }

    /**
     * Get a summary of metrics for an operation.
     *
     * @param operation the operation name
     * @return formatted metrics summary
     */
    public String getMetricsSummary(String operation) {
        List<Long> lats = getLatencies(operation);
        if (lats.isEmpty()) {
            return "No metrics recorded for operation: " + operation;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("\n=== Metrics for ").append(operation).append(" ===\n");
        summary.append("Count: ").append(lats.size()).append("\n");
        summary.append("Min: ").append(getMinLatency(operation)).append("ms\n");
        summary.append("Max: ").append(getMaxLatency(operation)).append("ms\n");
        summary.append("Avg: ").append(getAverageLatency(operation)).append("ms\n");
        summary.append("p50: ").append(getLatencyPercentile(operation, 50)).append("ms\n");
        summary.append("p95: ").append(getLatencyPercentile(operation, 95)).append("ms\n");
        summary.append("p99: ").append(getLatencyPercentile(operation, 99)).append("ms\n");

        return summary.toString();
    }

    /**
     * Get summaries for all recorded operations.
     *
     * @return formatted string with all metrics
     */
    public String getAllMetricsSummaries() {
        StringBuilder summary = new StringBuilder();
        for (String operation : latencies.keySet()) {
            summary.append(getMetricsSummary(operation));
        }
        return summary.toString();
    }

    /**
     * Reset all timers and latency data.
     */
    public void resetAll() {
        timers.clear();
        latencies.clear();
        startTimes.clear();
    }

    /**
     * Reset data for a specific operation.
     *
     * @param operation the operation name
     */
    public void reset(String operation) {
        timers.remove(operation);
        latencies.remove(operation);
    }

    /**
     * Get the number of latency measurements recorded for an operation.
     *
     * @param operation the operation name
     * @return the count of measurements
     */
    public int getMeasurementCount(String operation) {
        return getLatencies(operation).size();
    }

    /**
     * Get total time spent on an operation (sum of all latencies).
     *
     * @param operation the operation name
     * @return total time in milliseconds
     */
    public long getTotalTime(String operation) {
        return getLatencies(operation).stream().mapToLong(Long::longValue).sum();
    }

    /**
     * Get a detailed latency distribution report.
     *
     * @param operation the operation name
     * @return formatted distribution report
     */
    public String getLatencyDistribution(String operation) {
        List<Long> lats = new ArrayList<>(getLatencies(operation));
        if (lats.isEmpty()) {
            return "No latencies recorded for operation: " + operation;
        }

        Collections.sort(lats);
        StringBuilder report = new StringBuilder();
        report.append("\n=== Latency Distribution for ").append(operation).append(" ===\n");

        // Bucketed distribution (0-10ms, 10-50ms, etc.)
        int[] buckets = {10, 50, 100, 200, 500, 1000};
        int[] counts = new int[buckets.length + 1];

        for (long lat : lats) {
            boolean found = false;
            for (int i = 0; i < buckets.length; i++) {
                if (lat <= buckets[i]) {
                    counts[i]++;
                    found = true;
                    break;
                }
            }
            if (!found) {
                counts[buckets.length]++;
            }
        }

        report.append("0-10ms: ").append(counts[0]).append("\n");
        report.append("10-50ms: ").append(counts[1]).append("\n");
        report.append("50-100ms: ").append(counts[2]).append("\n");
        report.append("100-200ms: ").append(counts[3]).append("\n");
        report.append("200-500ms: ").append(counts[4]).append("\n");
        report.append("500-1000ms: ").append(counts[5]).append("\n");
        report.append(">1000ms: ").append(counts[6]).append("\n");

        return report.toString();
    }
}
