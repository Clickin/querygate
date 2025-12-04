package querygate.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable configuration properties for the SQL Gateway.
 * Maps to the 'gateway' prefix in application.yml.
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @NotBlank @Bindable(defaultValue = "./config/endpoint-config.yml") String endpointConfigPath,
        MyBatisConfig mybatis,
        HotReloadConfig hotReload,
        SqlLoggingConfig sqlLogging,
        VirtualThreadConfig virtualThreads,
        SecurityConfig security,
        ErrorHandlingConfig errorHandling
) {

    public GatewayProperties {
        endpointConfigPath = defaultIfBlank(endpointConfigPath, "./config/endpoint-config.yml");
        mybatis = mybatis != null ? mybatis : new MyBatisConfig("./config/mappers", false, false, 30);
        hotReload = hotReload != null ? hotReload : new HotReloadConfig(true, 5000);
        sqlLogging = sqlLogging != null ? sqlLogging : new SqlLoggingConfig(true, true, true, 1000);
        virtualThreads = virtualThreads != null ? virtualThreads : new VirtualThreadConfig(true, "gateway-virtual-executor");
        security = security != null ? security : new SecurityConfig(true, "Authorization", List.of());
        if (security.enabled() && security.apiKeys().isEmpty()) {
            throw new IllegalStateException(
                    "Security is enabled but no API keys are configured. " +
                            "Set gateway.security.api-keys or disable security explicitly.");
        }
        errorHandling = errorHandling != null ? errorHandling : new ErrorHandlingConfig(false, false);
    }

    @ConfigurationProperties("mybatis")
    public record MyBatisConfig(
            @NotBlank @Bindable(defaultValue = "./config/mappers") String mapperLocations,
            @Bindable(defaultValue = "false") boolean cacheEnabled,
            @Bindable(defaultValue = "false") boolean lazyLoadingEnabled,
            @Positive @Bindable(defaultValue = "30") int defaultStatementTimeout
    ) {
        public MyBatisConfig {
            mapperLocations = defaultIfBlank(mapperLocations, "./config/mappers");
        }
    }

    @ConfigurationProperties("hot-reload")
    public record HotReloadConfig(
            @Bindable(defaultValue = "true") boolean enabled,
            @Positive @Bindable(defaultValue = "5000") long pollIntervalMs
    ) {
    }

    @ConfigurationProperties("sql-logging")
    public record SqlLoggingConfig(
            @Bindable(defaultValue = "true") boolean enabled,
            @Bindable(defaultValue = "true") boolean logParameters,
            @Bindable(defaultValue = "true") boolean logTiming,
            @Positive @Bindable(defaultValue = "1000") long slowQueryThresholdMs
    ) {
    }

    @ConfigurationProperties("virtual-threads")
    public record VirtualThreadConfig(
            @Bindable(defaultValue = "true") boolean enabled,
            @Bindable(defaultValue = "gateway-virtual-executor") String executorName
    ) {
        public VirtualThreadConfig {
            executorName = defaultIfBlank(executorName, "gateway-virtual-executor");
        }
    }

        @ConfigurationProperties("security")
        public record SecurityConfig(
                @Bindable(defaultValue = "true") boolean enabled,
                @Bindable(defaultValue = "Authorization") String apiKeyHeader,
                List<String> apiKeys
        ) {
            public SecurityConfig {
                apiKeyHeader = defaultIfBlank(apiKeyHeader, "Authorization");
                apiKeys = apiKeys == null ? List.of() : apiKeys.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(key -> !key.isEmpty())
                        .collect(Collectors.toUnmodifiableList());
            }
        }

    @ConfigurationProperties("error-handling")
    public record ErrorHandlingConfig(
            @Bindable(defaultValue = "false") boolean exposeDetails,
            @Bindable(defaultValue = "false") boolean exposeStackTrace
    ) {
        /**
         * @param exposeDetails Whether to expose detailed error messages to clients.
         *                      If false, returns generic error messages.
         *                      Recommended: false in production, true in development.
         * @param exposeStackTrace Whether to include stack traces in error responses.
         *                         If false, stack traces are only logged server-side.
         *                         Recommended: false in production, true in development.
         */
        public ErrorHandlingConfig {
        }
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
