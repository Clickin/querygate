package querygate.support;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class for inspecting and verifying thread execution context.
 * Used to verify that sync operations are properly offloaded to virtual threads
 * and that event loop threads are not blocked by database operations.
 */
public class ThreadInspectionUtils {

    private static final String NETTY_THREAD_PATTERN = "netty";
    private static final String VIRTUAL_EXECUTOR_PATTERN = "gateway-virtual-executor";
    private static final String PLATFORM_EXECUTOR_PATTERN = "gateway-platform";

    /**
     * Get the name of the currently executing thread.
     *
     * @return the thread name
     */
    public static String getExecutingThreadName() {
        return Thread.currentThread().getName();
    }

    /**
     * Get the ID of the currently executing thread.
     *
     * @return the thread ID
     */
    public static long getExecutingThreadId() {
        return Thread.currentThread().getId();
    }

    /**
     * Check if the current thread is a Netty event loop thread.
     *
     * @return true if executing on a Netty thread
     */
    public static boolean isEventLoopThread() {
        String threadName = getExecutingThreadName().toLowerCase();
        return threadName.contains(NETTY_THREAD_PATTERN);
    }

    /**
     * Check if the current thread is a virtual executor thread.
     *
     * @return true if executing on a virtual executor thread
     */
    public static boolean isVirtualExecutorThread() {
        String threadName = getExecutingThreadName().toLowerCase();
        return threadName.contains(VIRTUAL_EXECUTOR_PATTERN);
    }

    /**
     * Check if the current thread is a platform executor thread (fallback).
     *
     * @return true if executing on a platform executor thread
     */
    public static boolean isPlatformExecutorThread() {
        String threadName = getExecutingThreadName().toLowerCase();
        return threadName.contains(PLATFORM_EXECUTOR_PATTERN);
    }

    /**
     * Check if the current thread is any kind of executor thread (virtual or platform).
     *
     * @return true if executing on an executor thread
     */
    public static boolean isExecutorThread() {
        return isVirtualExecutorThread() || isPlatformExecutorThread();
    }

    /**
     * Get all currently active thread names matching a pattern.
     *
     * @param pattern the pattern to match (case-insensitive)
     * @return set of matching thread names
     */
    public static Set<String> getThreadNames(String pattern) {
        String lowerPattern = pattern.toLowerCase();
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.toLowerCase().contains(lowerPattern))
                .collect(Collectors.toSet());
    }

    /**
     * Get count of threads matching a pattern.
     *
     * @param pattern the pattern to match (case-insensitive)
     * @return the count of matching threads
     */
    public static int getThreadCount(String pattern) {
        return getThreadNames(pattern).size();
    }

    /**
     * Get count of active Netty threads.
     *
     * @return the count of Netty threads
     */
    public static int getNettyThreadCount() {
        return getThreadCount(NETTY_THREAD_PATTERN);
    }

    /**
     * Get count of active virtual executor threads.
     *
     * @return the count of virtual executor threads
     */
    public static int getVirtualExecutorThreadCount() {
        return getThreadCount(VIRTUAL_EXECUTOR_PATTERN);
    }

    /**
     * Get count of active platform executor threads.
     *
     * @return the count of platform executor threads
     */
    public static int getPlatformExecutorThreadCount() {
        return getThreadCount(PLATFORM_EXECUTOR_PATTERN);
    }

    /**
     * Get all thread names currently active in the JVM.
     *
     * @return set of all thread names
     */
    public static Set<String> getAllThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .collect(Collectors.toSet());
    }

    /**
     * Capture the current stack trace as a string array.
     *
     * @return the stack trace elements as strings
     */
    public static String[] captureStackTrace() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        return Arrays.stream(trace)
                .map(StackTraceElement::toString)
                .toArray(String[]::new);
    }

    /**
     * Capture stack traces for all threads matching a pattern.
     *
     * @param pattern the thread name pattern to match
     * @return map of thread name to stack trace
     */
    public static java.util.Map<String, String[]> captureStackTraces(String pattern) {
        String lowerPattern = pattern.toLowerCase();
        java.util.Map<String, String[]> traces = new java.util.HashMap<>();

        Thread.getAllStackTraces().forEach((thread, stackTrace) -> {
            if (thread.getName().toLowerCase().contains(lowerPattern)) {
                traces.put(thread.getName(), Arrays.stream(stackTrace)
                        .map(StackTraceElement::toString)
                        .toArray(String[]::new));
            }
        });

        return traces;
    }

    /**
     * Verify that a set of threads does NOT contain any Netty event loop threads.
     * Throws AssertionError if any event loop threads are found.
     *
     * @param threadNames the set of thread names to check
     */
    public static void assertNoEventLoopThreads(Set<String> threadNames) {
        Set<String> eventLoopThreads = threadNames.stream()
                .filter(name -> name.toLowerCase().contains(NETTY_THREAD_PATTERN))
                .collect(Collectors.toSet());

        if (!eventLoopThreads.isEmpty()) {
            throw new AssertionError("Found event loop threads executing database operations: " + eventLoopThreads);
        }
    }

    /**
     * Get a detailed report of all thread pool states.
     *
     * @return formatted string with thread pool information
     */
    public static String getThreadPoolReport() {
        StringBuilder report = new StringBuilder();
        report.append("\n=== Thread Pool Report ===\n");
        report.append("Netty threads: ").append(getNettyThreadCount()).append("\n");
        report.append("Virtual executor threads: ").append(getVirtualExecutorThreadCount()).append("\n");
        report.append("Platform executor threads: ").append(getPlatformExecutorThreadCount()).append("\n");
        report.append("Total active threads: ").append(Thread.activeCount()).append("\n");

        Set<String> nettyThreads = getThreadNames(NETTY_THREAD_PATTERN);
        if (!nettyThreads.isEmpty()) {
            report.append("\nNetty threads:\n");
            nettyThreads.forEach(name -> report.append("  - ").append(name).append("\n"));
        }

        Set<String> virtualThreads = getThreadNames(VIRTUAL_EXECUTOR_PATTERN);
        if (!virtualThreads.isEmpty()) {
            report.append("\nVirtual executor threads:\n");
            virtualThreads.forEach(name -> report.append("  - ").append(name).append("\n"));
        }

        return report.toString();
    }
}
