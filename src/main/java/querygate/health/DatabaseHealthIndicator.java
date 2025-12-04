package querygate.health;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micronaut.context.annotation.Requires;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import org.apache.ibatis.session.SqlSessionFactory;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health indicator that checks the database connection status.
 * Executes a simple validation query to ensure the database is accessible.
 */
@Singleton
@Requires(beans = SqlSessionFactory.class)
public class DatabaseHealthIndicator implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseHealthIndicator.class);
    private static final String NAME = "database";

    private final SqlSessionFactory sqlSessionFactory;
    private final HikariDataSource hikariDataSource;

    public DatabaseHealthIndicator(SqlSessionFactory sqlSessionFactory, DataSource dataSource) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.hikariDataSource = dataSource instanceof HikariDataSource ?
                (HikariDataSource) dataSource : null;
    }

    @Override
    public Publisher<HealthResult> getResult() {
        return Mono.fromCallable(this::checkDatabaseHealth);
    }

    private HealthResult checkDatabaseHealth() {
        Map<String, Object> details = new LinkedHashMap<>();

        try (Connection connection = sqlSessionFactory.getConfiguration()
                .getEnvironment()
                .getDataSource()
                .getConnection()) {

            boolean isValid = connection.isValid(5); // 5 second timeout

            if (isValid) {
                details.put("status", "UP");
                details.put("database", connection.getMetaData().getDatabaseProductName());
                details.put("version", connection.getMetaData().getDatabaseProductVersion());
                details.put("url", sanitizeUrl(connection.getMetaData().getURL()));
                addPoolMetrics(details);

                LOG.debug("Database health check passed");
                return HealthResult.builder(NAME, HealthStatus.UP)
                        .details(details)
                        .build();
            } else {
                details.put("status", "DOWN");
                details.put("reason", "Connection validation failed");
                addPoolMetrics(details);

                LOG.warn("Database health check failed: Connection not valid");
                return HealthResult.builder(NAME, HealthStatus.DOWN)
                        .details(details)
                        .build();
            }

        } catch (Exception e) {
            details.put("status", "DOWN");
            details.put("error", e.getClass().getSimpleName());
            details.put("message", e.getMessage());

            LOG.error("Database health check failed", e);
            return HealthResult.builder(NAME, HealthStatus.DOWN)
                    .details(details)
                    .build();
        }
    }

    /**
     * Sanitizes database URL to remove sensitive information like passwords.
     */
    private String sanitizeUrl(String url) {
        if (url == null) {
            return "unknown";
        }
        // Remove password parameter if present
        return url.replaceAll("password=[^;&]*", "password=***");
    }

    private void addPoolMetrics(Map<String, Object> details) {
        if (hikariDataSource == null) {
            return;
        }
        HikariPoolMXBean poolMXBean = hikariDataSource.getHikariPoolMXBean();
        if (poolMXBean != null) {
            details.put("pool.active", poolMXBean.getActiveConnections());
            details.put("pool.idle", poolMXBean.getIdleConnections());
            details.put("pool.total", poolMXBean.getTotalConnections());
            details.put("pool.waiting", poolMXBean.getThreadsAwaitingConnection());
        }
    }
}
