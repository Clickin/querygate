package querygate.model;

/**
 * Enumeration of SQL operation types supported by the gateway.
 */
public enum SqlType {
    /**
     * SELECT query - returns result set as List of Maps
     */
    SELECT,

    /**
     * INSERT statement - returns affected rows and generated keys
     */
    INSERT,

    /**
     * UPDATE statement - returns affected rows count
     */
    UPDATE,

    /**
     * DELETE statement - returns affected rows count
     */
    DELETE,

    /**
     * BATCH operation - executes multiple statements in a batch
     */
    BATCH
}
