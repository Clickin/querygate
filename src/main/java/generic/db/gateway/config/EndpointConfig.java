package generic.db.gateway.config;

import generic.db.gateway.model.SqlType;
import io.micronaut.core.annotation.Nullable;

import java.util.List;

/**
 * Configuration record representing a single endpoint mapping.
 * Maps an HTTP endpoint to a MyBatis SQL statement with validation rules.
 */
public record EndpointConfig(
        String path,
        String method,
        String sqlId,
        SqlType sqlType,
        @Nullable String description,
        @Nullable ValidationConfig validation,
        @Nullable BatchConfig batchConfig
) {
    /**
     * Validation configuration for endpoint parameters.
     */
    public record ValidationConfig(
            List<ParameterConfig> required,
            List<ParameterConfig> optional
    ) {
        public ValidationConfig {
            required = required != null ? List.copyOf(required) : List.of();
            optional = optional != null ? List.copyOf(optional) : List.of();
        }
    }

    /**
     * Configuration for a single parameter with validation rules.
     */
    public record ParameterConfig(
            String name,
            String type,
            @Nullable String source,           // path, query, body (default: auto-detect)
            @Nullable Integer minLength,
            @Nullable Integer maxLength,
            @Nullable String pattern,
            @Nullable Number min,
            @Nullable Number max,
            @Nullable List<String> allowedValues,
            @Nullable Object defaultValue,
            @Nullable String format,           // For date/time types
            @Nullable Integer minItems,        // For array types
            @Nullable Integer maxItems         // For array types
    ) {
        /**
         * Creates a simple required parameter with just name and type.
         */
        public static ParameterConfig simple(String name, String type) {
            return new ParameterConfig(name, type, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        /**
         * Creates a path parameter.
         */
        public static ParameterConfig pathParam(String name, String type) {
            return new ParameterConfig(name, type, "path", null, null, null,
                    null, null, null, null, null, null, null);
        }

        /**
         * Checks if this parameter comes from the URL path.
         */
        public boolean isPathParameter() {
            return "path".equalsIgnoreCase(source);
        }

        /**
         * Checks if this parameter comes from the query string.
         */
        public boolean isQueryParameter() {
            return "query".equalsIgnoreCase(source);
        }

        /**
         * Checks if this parameter comes from the request body.
         */
        public boolean isBodyParameter() {
            return "body".equalsIgnoreCase(source);
        }
    }

    /**
     * Configuration for batch operations.
     */
    public record BatchConfig(
            String itemKey,      // The key in the request body containing the array of items
            int batchSize        // Number of items per batch
    ) {
        public BatchConfig {
            if (itemKey == null || itemKey.isBlank()) {
                throw new IllegalArgumentException("itemKey cannot be null or blank");
            }
            if (batchSize <= 0) {
                batchSize = 100; // Default batch size
            }
        }
    }

    /**
     * Builder for creating EndpointConfig instances programmatically.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String path;
        private String method;
        private String sqlId;
        private SqlType sqlType;
        private String description;
        private ValidationConfig validation;
        private BatchConfig batchConfig;

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder sqlId(String sqlId) {
            this.sqlId = sqlId;
            return this;
        }

        public Builder sqlType(SqlType sqlType) {
            this.sqlType = sqlType;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder validation(ValidationConfig validation) {
            this.validation = validation;
            return this;
        }

        public Builder batchConfig(BatchConfig batchConfig) {
            this.batchConfig = batchConfig;
            return this;
        }

        public EndpointConfig build() {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("path is required");
            }
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException("method is required");
            }
            if (sqlId == null || sqlId.isBlank()) {
                throw new IllegalArgumentException("sqlId is required");
            }
            if (sqlType == null) {
                throw new IllegalArgumentException("sqlType is required");
            }
            return new EndpointConfig(path, method.toUpperCase(), sqlId, sqlType,
                    description, validation, batchConfig);
        }
    }
}
