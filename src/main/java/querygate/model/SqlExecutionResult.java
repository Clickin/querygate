package querygate.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified result wrapper for all SQL operations.
 * Provides a consistent response structure regardless of SQL type.
 */
@Serdeable
public class SqlExecutionResult {

    private final SqlType sqlType;
    private final List<Map<String, Object>> data;
    private final int affectedRows;
    private final Object generatedId;
    private final int batchCount;
    private final boolean success;
    private final String message;

    private SqlExecutionResult(
            SqlType sqlType,
            @Nullable List<Map<String, Object>> data,
            int affectedRows,
            @Nullable Object generatedId,
            int batchCount,
            boolean success,
            @Nullable String message) {
        this.sqlType = sqlType;
        this.data = data;
        this.affectedRows = affectedRows;
        this.generatedId = generatedId;
        this.batchCount = batchCount;
        this.success = success;
        this.message = message;
    }

    /**
     * Creates a result for SELECT operations.
     *
     * @param data the result set as a list of maps
     * @return SqlExecutionResult for SELECT
     */
    public static SqlExecutionResult forSelect(List<Map<String, Object>> data) {
        return new SqlExecutionResult(
                SqlType.SELECT,
                data,
                data != null ? data.size() : 0,
                null,
                0,
                true,
                null
        );
    }

    /**
     * Creates a result for INSERT operations.
     *
     * @param affectedRows number of rows inserted
     * @param generatedId  the auto-generated key (if any)
     * @return SqlExecutionResult for INSERT
     */
    public static SqlExecutionResult forInsert(int affectedRows, @Nullable Object generatedId) {
        return new SqlExecutionResult(
                SqlType.INSERT,
                null,
                affectedRows,
                generatedId,
                0,
                true,
                null
        );
    }

    /**
     * Creates a result for UPDATE operations.
     *
     * @param affectedRows number of rows updated
     * @return SqlExecutionResult for UPDATE
     */
    public static SqlExecutionResult forUpdate(int affectedRows) {
        return new SqlExecutionResult(
                SqlType.UPDATE,
                null,
                affectedRows,
                null,
                0,
                true,
                null
        );
    }

    /**
     * Creates a result for DELETE operations.
     *
     * @param affectedRows number of rows deleted
     * @return SqlExecutionResult for DELETE
     */
    public static SqlExecutionResult forDelete(int affectedRows) {
        return new SqlExecutionResult(
                SqlType.DELETE,
                null,
                affectedRows,
                null,
                0,
                true,
                null
        );
    }

    /**
     * Creates a result for BATCH operations.
     *
     * @param totalAffected total number of rows affected across all batches
     * @param batchCount    number of batches executed
     * @return SqlExecutionResult for BATCH
     */
    public static SqlExecutionResult forBatch(int totalAffected, int batchCount) {
        return new SqlExecutionResult(
                SqlType.BATCH,
                null,
                totalAffected,
                null,
                batchCount,
                true,
                null
        );
    }

    /**
     * Creates an error result.
     *
     * @param sqlType the SQL type that failed
     * @param message error message
     * @return SqlExecutionResult indicating failure
     */
    public static SqlExecutionResult forError(SqlType sqlType, String message) {
        return new SqlExecutionResult(
                sqlType,
                null,
                0,
                null,
                0,
                false,
                message
        );
    }

    // Getters

    public SqlType getSqlType() {
        return sqlType;
    }

    public List<Map<String, Object>> getData() {
        return data;
    }

    public int getAffectedRows() {
        return affectedRows;
    }

    public Object getGeneratedId() {
        return generatedId;
    }

    public int getBatchCount() {
        return batchCount;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    /**
     * Converts this result to a Map for JSON/XML serialization.
     *
     * @return Map representation of this result
     */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("sqlType", sqlType.name());

        if (data != null) {
            result.put("data", data);
            result.put("count", data.size());
        }

        if (sqlType != SqlType.SELECT) {
            result.put("affectedRows", affectedRows);
        }

        if (generatedId != null) {
            result.put("generatedId", generatedId);
        }

        if (batchCount > 0) {
            result.put("batchCount", batchCount);
        }

        if (message != null) {
            result.put("message", message);
        }

        return result;
    }

    @Override
    public String toString() {
        return "SqlExecutionResult{" +
                "sqlType=" + sqlType +
                ", success=" + success +
                ", affectedRows=" + affectedRows +
                ", dataSize=" + (data != null ? data.size() : 0) +
                '}';
    }
}
