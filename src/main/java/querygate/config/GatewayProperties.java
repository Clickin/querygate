package querygate.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Configuration properties for the SQL Gateway.
 * Maps to the 'gateway' prefix in application.yml.
 */
@ConfigurationProperties("gateway")
public class GatewayProperties {

    private MyBatisConfig mybatis = new MyBatisConfig();
    private HotReloadConfig hotReload = new HotReloadConfig();
    private SqlLoggingConfig sqlLogging = new SqlLoggingConfig();
    private VirtualThreadConfig virtualThreads = new VirtualThreadConfig();
    private SecurityConfig security = new SecurityConfig();
    private BackpressureConfig backpressure = new BackpressureConfig();

    @NotBlank
    private String endpointConfigPath = "./config/endpoint-config.yml";

    // Main getters and setters

    public MyBatisConfig getMybatis() {
        return mybatis;
    }

    public void setMybatis(MyBatisConfig mybatis) {
        this.mybatis = mybatis;
    }

    public HotReloadConfig getHotReload() {
        return hotReload;
    }

    public void setHotReload(HotReloadConfig hotReload) {
        this.hotReload = hotReload;
    }

    public SqlLoggingConfig getSqlLogging() {
        return sqlLogging;
    }

    public void setSqlLogging(SqlLoggingConfig sqlLogging) {
        this.sqlLogging = sqlLogging;
    }

    public VirtualThreadConfig getVirtualThreads() {
        return virtualThreads;
    }

    public void setVirtualThreads(VirtualThreadConfig virtualThreads) {
        this.virtualThreads = virtualThreads;
    }

    public String getEndpointConfigPath() {
        return endpointConfigPath;
    }

    public void setEndpointConfigPath(String endpointConfigPath) {
        this.endpointConfigPath = endpointConfigPath;
    }

    public SecurityConfig getSecurity() {
        return security;
    }

    public void setSecurity(SecurityConfig security) {
        this.security = security;
    }

    public BackpressureConfig getBackpressure() {
        return backpressure;
    }

    public void setBackpressure(BackpressureConfig backpressure) {
        this.backpressure = backpressure;
    }

    /**
     * MyBatis configuration properties.
     */
    @ConfigurationProperties("mybatis")
    public static class MyBatisConfig {

        @NotBlank
        private String mapperLocations = "./config/mappers";

        private boolean cacheEnabled = false;

        private boolean lazyLoadingEnabled = false;

        @Positive
        private int defaultStatementTimeout = 30;

        public String getMapperLocations() {
            return mapperLocations;
        }

        public void setMapperLocations(String mapperLocations) {
            this.mapperLocations = mapperLocations;
        }

        public boolean isCacheEnabled() {
            return cacheEnabled;
        }

        public void setCacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
        }

        public boolean isLazyLoadingEnabled() {
            return lazyLoadingEnabled;
        }

        public void setLazyLoadingEnabled(boolean lazyLoadingEnabled) {
            this.lazyLoadingEnabled = lazyLoadingEnabled;
        }

        public int getDefaultStatementTimeout() {
            return defaultStatementTimeout;
        }

        public void setDefaultStatementTimeout(int defaultStatementTimeout) {
            this.defaultStatementTimeout = defaultStatementTimeout;
        }
    }

    /**
     * Hot reload configuration properties.
     */
    @ConfigurationProperties("hot-reload")
    public static class HotReloadConfig {

        private boolean enabled = true;

        @Positive
        private long pollIntervalMs = 5000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }
    }

    /**
     * SQL logging configuration properties.
     */
    @ConfigurationProperties("sql-logging")
    public static class SqlLoggingConfig {

        private boolean enabled = true;

        private boolean logParameters = true;

        private boolean logTiming = true;

        @Positive
        private long slowQueryThresholdMs = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isLogParameters() {
            return logParameters;
        }

        public void setLogParameters(boolean logParameters) {
            this.logParameters = logParameters;
        }

        public boolean isLogTiming() {
            return logTiming;
        }

        public void setLogTiming(boolean logTiming) {
            this.logTiming = logTiming;
        }

        public long getSlowQueryThresholdMs() {
            return slowQueryThresholdMs;
        }

        public void setSlowQueryThresholdMs(long slowQueryThresholdMs) {
            this.slowQueryThresholdMs = slowQueryThresholdMs;
        }
    }

    /**
     * Virtual thread executor configuration properties.
     */
    @ConfigurationProperties("virtual-threads")
    public static class VirtualThreadConfig {

        private boolean enabled = true;

        private String executorName = "gateway-virtual-executor";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getExecutorName() {
            return executorName;
        }

        public void setExecutorName(String executorName) {
            this.executorName = executorName;
        }
    }

    /**
     * Security configuration properties.
     */
    @ConfigurationProperties("security")
    public static class SecurityConfig {

        private boolean enabled = true;

        private String apiKeyHeader = "X-API-Key";

        private java.util.List<String> apiKeys = java.util.List.of();

        private java.util.List<String> allowedNetworks = java.util.List.of("127.0.0.1", "::1");

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKeyHeader() {
            return apiKeyHeader;
        }

        public void setApiKeyHeader(String apiKeyHeader) {
            this.apiKeyHeader = apiKeyHeader;
        }

        public java.util.List<String> getApiKeys() {
            return apiKeys;
        }

        public void setApiKeys(java.util.List<String> apiKeys) {
            this.apiKeys = apiKeys;
        }

        public java.util.List<String> getAllowedNetworks() {
            return allowedNetworks;
        }

        public void setAllowedNetworks(java.util.List<String> allowedNetworks) {
            this.allowedNetworks = allowedNetworks;
        }
    }

    /**
     * Backpressure configuration properties.
     */
    @ConfigurationProperties("backpressure")
    public static class BackpressureConfig {

        private boolean enabled = true;

        @Positive
        private int maxConcurrentRequests = 100;

        @Positive
        private long requestTimeoutMs = 30000;

        @Positive
        private long statementTimeoutSeconds = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxConcurrentRequests() {
            return maxConcurrentRequests;
        }

        public void setMaxConcurrentRequests(int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
        }

        public long getRequestTimeoutMs() {
            return requestTimeoutMs;
        }

        public void setRequestTimeoutMs(long requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
        }

        public long getStatementTimeoutSeconds() {
            return statementTimeoutSeconds;
        }

        public void setStatementTimeoutSeconds(long statementTimeoutSeconds) {
            this.statementTimeoutSeconds = statementTimeoutSeconds;
        }
    }
}
