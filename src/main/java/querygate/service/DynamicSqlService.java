package querygate.service;

import querygate.config.EndpointConfig;
import querygate.config.MyBatisFactory;
import querygate.exception.SqlExecutionException;
import querygate.model.SqlExecutionResult;
import querygate.model.SqlType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Service for executing dynamic SQL operations via MyBatis.
 * Supports SELECT, INSERT, UPDATE, DELETE, and BATCH operations.
 * Uses virtual threads for async execution.
 */
@Singleton
public class DynamicSqlService {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicSqlService.class);

    private final MyBatisFactory myBatisFactory;
    private final MeterRegistry meterRegistry;

    public DynamicSqlService(
            MyBatisFactory myBatisFactory,
            MeterRegistry meterRegistry) {
        this.myBatisFactory = myBatisFactory;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Executes SQL based on endpoint configuration on the configured executor.
     *
     * @param endpointConfig The endpoint configuration
     * @param parameters     The merged and validated parameters
     * @return Execution result
     */
    public SqlExecutionResult execute(
            EndpointConfig endpointConfig,
            Map<String, Object> parameters) {

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            SqlExecutionResult result = switch (endpointConfig.sqlType()) {
                case SELECT -> executeSelect(endpointConfig.sqlId(), parameters);
                case INSERT -> executeInsert(endpointConfig.sqlId(), parameters);
                case UPDATE -> executeUpdate(endpointConfig.sqlId(), parameters);
                case DELETE -> executeDelete(endpointConfig.sqlId(), parameters);
                case BATCH -> executeBatch(endpointConfig, parameters);
            };

            sample.stop(Timer.builder("gateway.sql.execution")
                    .tag("sqlType", endpointConfig.sqlType().name())
                    .tag("sqlId", endpointConfig.sqlId())
                    .tag("status", "success")
                    .register(meterRegistry));

            return result;

        } catch (Exception e) {
            sample.stop(Timer.builder("gateway.sql.execution")
                    .tag("sqlType", endpointConfig.sqlType().name())
                    .tag("sqlId", endpointConfig.sqlId())
                    .tag("status", "error")
                    .register(meterRegistry));

            LOG.error("SQL execution failed: {} - {}", endpointConfig.sqlId(), e.getMessage(), e);
            // Throw exception to be handled by GlobalExceptionHandler
            // Do NOT expose raw SQL errors to clients
            throw new SqlExecutionException(
                    endpointConfig.sqlId(),
                    "SQL execution failed",
                    e
            );
        }
    }

    /**
     * Executes a synchronous SQL operation.
     * Use this for internal calls where async is not needed.
     */
    public SqlExecutionResult executeSync(
            EndpointConfig endpointConfig,
            Map<String, Object> parameters) {

        return switch (endpointConfig.sqlType()) {
            case SELECT -> executeSelect(endpointConfig.sqlId(), parameters);
            case INSERT -> executeInsert(endpointConfig.sqlId(), parameters);
            case UPDATE -> executeUpdate(endpointConfig.sqlId(), parameters);
            case DELETE -> executeDelete(endpointConfig.sqlId(), parameters);
            case BATCH -> executeBatch(endpointConfig, parameters);
        };
    }

    /**
     * Executes a SELECT query and returns results as List of Maps.
     */
    public SqlExecutionResult executeSelect(String sqlId, Map<String, Object> parameters) {
        LOG.debug("Executing SELECT: {} with params: {}", sqlId, parameters.keySet());

        SqlSessionFactory factory = myBatisFactory.getCurrentFactory();
        try (SqlSession session = factory.openSession()) {
            List<Map<String, Object>> results = session.selectList(sqlId, parameters);
            LOG.debug("SELECT returned {} rows", results.size());
            return SqlExecutionResult.forSelect(results);
        }
    }

    /**
     * Executes a single SELECT and returns one row.
     */
    public SqlExecutionResult executeSelectOne(String sqlId, Map<String, Object> parameters) {
        LOG.debug("Executing SELECT ONE: {} with params: {}", sqlId, parameters.keySet());

        SqlSessionFactory factory = myBatisFactory.getCurrentFactory();
        try (SqlSession session = factory.openSession()) {
            Map<String, Object> result = session.selectOne(sqlId, parameters);
            if (result != null) {
                return SqlExecutionResult.forSelect(List.of(result));
            } else {
                return SqlExecutionResult.forSelect(List.of());
            }
        }
    }

    /**
     * Executes an INSERT statement with auto-commit.
     */
    public SqlExecutionResult executeInsert(String sqlId, Map<String, Object> parameters) {
        LOG.debug("Executing INSERT: {} with params: {}", sqlId, parameters.keySet());

        SqlSessionFactory factory = myBatisFactory.getCurrentFactory();
        try (SqlSession session = factory.openSession(true)) { // auto-commit
            int affected = session.insert(sqlId, parameters);

            // Check for generated keys (MyBatis puts them in the parameter map)
            Object generatedId = parameters.get("id");
            LOG.debug("INSERT affected {} rows, generated id: {}", affected, generatedId);

            return SqlExecutionResult.forInsert(affected, generatedId);
        }
    }

    /**
     * Executes an UPDATE statement with auto-commit.
     */
    public SqlExecutionResult executeUpdate(String sqlId, Map<String, Object> parameters) {
        LOG.debug("Executing UPDATE: {} with params: {}", sqlId, parameters.keySet());

        SqlSessionFactory factory = myBatisFactory.getCurrentFactory();
        try (SqlSession session = factory.openSession(true)) {
            int affected = session.update(sqlId, parameters);
            LOG.debug("UPDATE affected {} rows", affected);
            return SqlExecutionResult.forUpdate(affected);
        }
    }

    /**
     * Executes a DELETE statement with auto-commit.
     */
    public SqlExecutionResult executeDelete(String sqlId, Map<String, Object> parameters) {
        LOG.debug("Executing DELETE: {} with params: {}", sqlId, parameters.keySet());

        SqlSessionFactory factory = myBatisFactory.getCurrentFactory();
        try (SqlSession session = factory.openSession(true)) {
            int affected = session.delete(sqlId, parameters);
            LOG.debug("DELETE affected {} rows", affected);
            return SqlExecutionResult.forDelete(affected);
        }
    }

    /**
     * Executes a batch operation for multiple items.
     */
    @SuppressWarnings("unchecked")
    public SqlExecutionResult executeBatch(EndpointConfig config, Map<String, Object> parameters) {
        EndpointConfig.BatchConfig batchConfig = config.batchConfig();
        if (batchConfig == null) {
            throw new IllegalArgumentException("Batch configuration required for BATCH sql type");
        }

        String itemKey = batchConfig.itemKey();
        int batchSize = batchConfig.batchSize();

        Object itemsObj = parameters.get(itemKey);
        if (itemsObj == null) {
            throw new IllegalArgumentException("Batch items not found with key: " + itemKey);
        }

        List<Map<String, Object>> items;
        if (itemsObj instanceof List) {
            items = (List<Map<String, Object>>) itemsObj;
        } else {
            throw new IllegalArgumentException("Batch items must be a list");
        }

        if (items.isEmpty()) {
            return SqlExecutionResult.forBatch(0, 0);
        }

        LOG.debug("Executing BATCH: {} with {} items, batch size: {}",
                config.sqlId(), items.size(), batchSize);

        SqlSessionFactory factory = myBatisFactory.getCurrentFactory();
        int totalAffected = 0;
        int batchCount = 0;

        try (SqlSession session = factory.openSession(ExecutorType.BATCH, false)) {
            for (int i = 0; i < items.size(); i++) {
                session.insert(config.sqlId(), items.get(i));

                // Flush batch at batch size intervals
                if ((i + 1) % batchSize == 0) {
                    session.flushStatements();
                    session.commit();
                    batchCount++;
                }
            }

            // Flush remaining items
            if (items.size() % batchSize != 0) {
                session.flushStatements();
                session.commit();
                batchCount++;
            }

            totalAffected = items.size();
        }

        LOG.debug("BATCH completed: {} items in {} batches", totalAffected, batchCount);
        return SqlExecutionResult.forBatch(totalAffected, batchCount);
    }
}
