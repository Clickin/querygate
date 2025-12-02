package querygate.support;

import io.micronaut.context.ApplicationContext;

/**
 * Utility for toggling virtual thread configuration during testing.
 * Allows tests to compare behavior with virtual threads enabled vs disabled.
 */
public class ConfigurationToggle {

    private static volatile boolean virtualThreadsOverride = false;
    private static volatile Boolean virtualThreadsEnabled = null;
    private static final Object LOCK = new Object();

    /**
     * Check if virtual threads are enabled in the current configuration.
     *
     * @param applicationContext the Micronaut ApplicationContext
     * @return true if virtual threads are enabled
     */
    public static boolean isVirtualThreadsEnabled(ApplicationContext applicationContext) {
        if (virtualThreadsEnabled != null) {
            return virtualThreadsEnabled;
        }

        try {
            // Try to get from configuration
            String enabled = applicationContext.getProperty("gateway.virtual-threads.enabled", String.class)
                    .orElse("true");
            return "true".equalsIgnoreCase(enabled);
        } catch (Exception e) {
            // Default to true if unable to determine
            return true;
        }
    }

    /**
     * Retrieve the current executor type being used.
     * Useful to verify whether virtual or platform threads are active.
     *
     * @param applicationContext the Micronaut ApplicationContext
     * @return "virtual" if virtual threads enabled, "platform" if disabled
     */
    public static String getCurrentExecutorType(ApplicationContext applicationContext) {
        boolean enabled = isVirtualThreadsEnabled(applicationContext);

        // Also check actual thread names to verify
        int virtualCount = ThreadInspectionUtils.getVirtualExecutorThreadCount();
        int platformCount = ThreadInspectionUtils.getPlatformExecutorThreadCount();

        if (enabled) {
            return "virtual";
        } else {
            return "platform";
        }
    }

    /**
     * Get a diagnostic report of the current executor configuration.
     *
     * @param applicationContext the Micronaut ApplicationContext
     * @return formatted diagnostic string
     */
    public static String getDiagnosticReport(ApplicationContext applicationContext) {
        StringBuilder report = new StringBuilder();
        report.append("\n=== Executor Configuration ===\n");
        report.append("Virtual threads enabled (config): ").append(isVirtualThreadsEnabled(applicationContext)).append("\n");
        report.append("Current executor type: ").append(getCurrentExecutorType(applicationContext)).append("\n");
        report.append("Virtual executor threads active: ").append(ThreadInspectionUtils.getVirtualExecutorThreadCount()).append("\n");
        report.append("Platform executor threads active: ").append(ThreadInspectionUtils.getPlatformExecutorThreadCount()).append("\n");

        try {
            String executorName = applicationContext.getProperty("gateway.virtual-threads.executor-name", String.class)
                    .orElse("gateway-virtual-executor");
            report.append("Executor name: ").append(executorName).append("\n");
        } catch (Exception e) {
            // Ignore
        }

        return report.toString();
    }

    /**
     * Get the configured executor name.
     *
     * @param applicationContext the Micronaut ApplicationContext
     * @return the executor name
     */
    public static String getExecutorName(ApplicationContext applicationContext) {
        return applicationContext.getProperty("gateway.virtual-threads.executor-name", String.class)
                .orElse("gateway-virtual-executor");
    }
}
