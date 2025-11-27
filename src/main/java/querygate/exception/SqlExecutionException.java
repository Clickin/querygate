package querygate.exception;

/**
 * Exception thrown when SQL execution fails.
 * Details are logged server-side but not exposed to clients.
 */
public class SqlExecutionException extends RuntimeException {

    private final String sqlId;

    public SqlExecutionException(String sqlId, String message, Throwable cause) {
        super(message, cause);
        this.sqlId = sqlId;
    }

    public SqlExecutionException(String sqlId, String message) {
        super(message);
        this.sqlId = sqlId;
    }

    public String getSqlId() {
        return sqlId;
    }
}
