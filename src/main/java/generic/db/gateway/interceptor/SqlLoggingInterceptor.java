package generic.db.gateway.interceptor;

import generic.db.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * MyBatis interceptor that logs SQL statements, parameters, and execution timing.
 * Records metrics to Prometheus via Micrometer.
 */
@Singleton
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public class SqlLoggingInterceptor implements Interceptor {

    private static final Logger LOG = LoggerFactory.getLogger(SqlLoggingInterceptor.class);
    private static final Logger SQL_LOG = LoggerFactory.getLogger("SQL_LOGGER");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final GatewayProperties.SqlLoggingConfig config;
    private final MeterRegistry meterRegistry;

    public SqlLoggingInterceptor(GatewayProperties properties, MeterRegistry meterRegistry) {
        this.config = properties.getSqlLogging();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!config.isEnabled()) {
            return invocation.proceed();
        }

        MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
        Object parameter = invocation.getArgs().length > 1 ? invocation.getArgs()[1] : null;

        String sqlId = mappedStatement.getId();
        BoundSql boundSql = mappedStatement.getBoundSql(parameter);
        String sql = normalizeSql(boundSql.getSql());

        String parameterStr = "";
        if (config.isLogParameters()) {
            parameterStr = extractParameters(mappedStatement.getConfiguration(), boundSql);
        }

        long startTime = System.currentTimeMillis();

        try {
            Object result = invocation.proceed();

            long duration = System.currentTimeMillis() - startTime;
            logSqlExecution(sqlId, sql, parameterStr, duration, null, getResultInfo(result));

            return result;

        } catch (Throwable e) {
            long duration = System.currentTimeMillis() - startTime;
            logSqlExecution(sqlId, sql, parameterStr, duration, e, null);
            throw e;
        }
    }

    private void logSqlExecution(String sqlId, String sql, String params,
                                 long duration, Throwable error, String resultInfo) {
        boolean isSlowQuery = duration >= config.getSlowQueryThresholdMs();

        StringBuilder logMessage = new StringBuilder();
        logMessage.append("\n=== SQL Execution ===\n");
        logMessage.append("SQL ID: ").append(sqlId).append("\n");
        logMessage.append("SQL: ").append(sql).append("\n");
        logMessage.append("Parameters: ").append(params.isEmpty() ? "(none)" : params).append("\n");
        logMessage.append("Duration: ").append(duration).append(" ms");

        if (isSlowQuery) {
            logMessage.append(" [SLOW QUERY]");
        }

        if (resultInfo != null) {
            logMessage.append("\n").append("Result: ").append(resultInfo);
        }

        logMessage.append("\n=====================");

        if (error != null) {
            SQL_LOG.error(logMessage + "\nError: " + error.getMessage());
        } else if (isSlowQuery) {
            SQL_LOG.warn(logMessage.toString());
        } else if (SQL_LOG.isDebugEnabled()) {
            SQL_LOG.debug(logMessage.toString());
        }

        // Record metrics
        recordMetrics(sqlId, duration, isSlowQuery, error != null);
    }

    private void recordMetrics(String sqlId, long duration, boolean isSlowQuery, boolean hasError) {
        Timer.builder("gateway.sql.duration")
                .tag("sqlId", sqlId)
                .tag("slow", String.valueOf(isSlowQuery))
                .tag("error", String.valueOf(hasError))
                .register(meterRegistry)
                .record(Duration.ofMillis(duration));

        if (isSlowQuery) {
            meterRegistry.counter("gateway.sql.slow_queries",
                    "sqlId", sqlId).increment();
        }

        if (hasError) {
            meterRegistry.counter("gateway.sql.errors",
                    "sqlId", sqlId).increment();
        }
    }

    private String extractParameters(Configuration configuration, BoundSql boundSql) {
        Object parameterObject = boundSql.getParameterObject();
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();

        if (parameterMappings.isEmpty() || parameterObject == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder("[");
        TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();

        try {
            if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                // Simple type parameter
                sb.append(formatValue(parameterObject));
            } else {
                // Complex type - extract each parameter
                MetaObject metaObject = configuration.newMetaObject(parameterObject);

                for (int i = 0; i < parameterMappings.size(); i++) {
                    ParameterMapping parameterMapping = parameterMappings.get(i);
                    String propertyName = parameterMapping.getProperty();
                    Object value;

                    if (boundSql.hasAdditionalParameter(propertyName)) {
                        value = boundSql.getAdditionalParameter(propertyName);
                    } else if (propertyName.startsWith("__frch_")) {
                        // Foreach parameter - skip detailed logging
                        continue;
                    } else {
                        try {
                            value = metaObject.getValue(propertyName);
                        } catch (Exception e) {
                            value = "<unavailable>";
                        }
                    }

                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(propertyName).append("=").append(formatValue(value));
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to extract parameters: {}", e.getMessage());
            return "[extraction failed]";
        }

        return sb.append("]").toString();
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof String) {
            String str = (String) value;
            if (str.length() > 100) {
                return "'" + str.substring(0, 100) + "...[truncated]'";
            }
            return "'" + str + "'";
        } else if (value instanceof Date) {
            synchronized (DATE_FORMAT) {
                return DATE_FORMAT.format((Date) value);
            }
        } else if (value instanceof byte[]) {
            return "[binary data " + ((byte[]) value).length + " bytes]";
        } else {
            return value.toString();
        }
    }

    private String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private String getResultInfo(Object result) {
        if (result == null) {
            return "null";
        } else if (result instanceof List) {
            return ((List<?>) result).size() + " rows";
        } else if (result instanceof Integer) {
            return result + " rows affected";
        } else {
            return result.getClass().getSimpleName();
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // Configuration is handled via GatewayProperties injection
    }
}
