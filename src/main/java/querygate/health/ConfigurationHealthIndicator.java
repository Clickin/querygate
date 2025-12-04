package querygate.health;

import io.micronaut.context.annotation.Requires;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.reactivestreams.Publisher;
import querygate.config.EndpointConfigLoader;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health indicator that checks configuration load status.
 * Verifies that endpoint configurations are loaded and valid.
 */
@Singleton
@Requires(beans = EndpointConfigLoader.class)
public class ConfigurationHealthIndicator implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationHealthIndicator.class);
    private static final String NAME = "configuration";

    private final EndpointConfigLoader endpointConfigLoader;

    public ConfigurationHealthIndicator(EndpointConfigLoader endpointConfigLoader) {
        this.endpointConfigLoader = endpointConfigLoader;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        return Mono.fromCallable(this::checkConfigurationHealth);
    }

    private HealthResult checkConfigurationHealth() {
        Map<String, Object> details = new LinkedHashMap<>();

        try {
            var endpoints = endpointConfigLoader.getAllEndpoints();

            if (endpoints.isEmpty()) {
                details.put("status", "DOWN");
                details.put("reason", "No endpoints configured");
                details.put("endpointCount", 0);

                LOG.warn("Configuration health check failed: No endpoints loaded");
                return HealthResult.builder(NAME, HealthStatus.DOWN)
                        .details(details)
                        .build();
            }

            // Group endpoints by SQL type
            Map<String, Long> endpointsByType = new LinkedHashMap<>();
            endpoints.forEach(endpoint -> {
                String sqlType = endpoint.sqlType().name();
                endpointsByType.merge(sqlType, 1L, Long::sum);
            });

            details.put("status", "UP");
            details.put("endpointCount", endpoints.size());
            details.put("endpointsByType", endpointsByType);

            LOG.debug("Configuration health check passed: {} endpoints loaded", endpoints.size());
            return HealthResult.builder(NAME, HealthStatus.UP)
                    .details(details)
                    .build();

        } catch (Exception e) {
            details.put("status", "DOWN");
            details.put("error", e.getClass().getSimpleName());
            details.put("message", e.getMessage());

            LOG.error("Configuration health check failed", e);
            return HealthResult.builder(NAME, HealthStatus.DOWN)
                    .details(details)
                    .build();
        }
    }
}
