package querygate.validation;

import querygate.config.EndpointConfig;
import querygate.config.EndpointConfig.ParameterConfig;
import querygate.config.EndpointConfig.ValidationConfig;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Config-driven parameter validation module.
 * Validates and transforms input parameters based on endpoint configuration.
 */
@Singleton
public class ValidationModule {

    private static final Logger LOG = LoggerFactory.getLogger(ValidationModule.class);

    // Cache compiled regex patterns for performance
    private final Map<String, Pattern> patternCache = new HashMap<>();

    /**
     * Validates and transforms parameters based on endpoint configuration.
     *
     * @param endpointConfig The endpoint configuration with validation rules
     * @param parameters     The input parameters to validate
     * @return Validated and type-converted parameters
     * @throws ValidationException if validation fails
     */
    public Map<String, Object> validateAndTransform(
            EndpointConfig endpointConfig,
            Map<String, Object> parameters) {

        ValidationConfig validation = endpointConfig.validation();
        if (validation == null) {
            LOG.debug("No validation config for endpoint: {}", endpointConfig.path());
            return parameters;
        }

        Map<String, Object> validated = new LinkedHashMap<>(parameters);
        List<String> errors = new ArrayList<>();

        // Validate required parameters
        for (ParameterConfig required : validation.required()) {
            Object value = validated.get(required.name());

            if (value == null || (value instanceof String s && s.isBlank())) {
                errors.add(String.format("Required parameter '%s' is missing", required.name()));
            } else {
                try {
                    Object transformed = validateParameter(required, value);
                    validated.put(required.name(), transformed);
                } catch (ValidationException e) {
                    errors.add(e.getMessage());
                }
            }
        }

        // Validate optional parameters (if present) and apply defaults
        for (ParameterConfig optional : validation.optional()) {
            Object value = validated.get(optional.name());

            if (value == null) {
                // Apply default value if configured
                if (optional.defaultValue() != null) {
                    validated.put(optional.name(), optional.defaultValue());
                }
            } else if (value instanceof String s && s.isBlank()) {
                // Empty string - treat as missing, apply default
                if (optional.defaultValue() != null) {
                    validated.put(optional.name(), optional.defaultValue());
                } else {
                    validated.remove(optional.name());
                }
            } else {
                try {
                    Object transformed = validateParameter(optional, value);
                    validated.put(optional.name(), transformed);
                } catch (ValidationException e) {
                    errors.add(e.getMessage());
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Validation failed", errors);
        }

        return validated;
    }

    /**
     * Validates a single parameter against its configuration.
     */
    private Object validateParameter(ParameterConfig config, Object value) {
        String paramName = config.name();
        String type = config.type() != null ? config.type().toLowerCase() : "string";

        return switch (type) {
            case "string" -> validateString(config, value);
            case "integer", "int" -> validateInteger(config, value);
            case "long" -> validateLong(config, value);
            case "double", "float", "number" -> validateNumber(config, value);
            case "boolean", "bool" -> validateBoolean(config, value);
            case "date" -> validateDate(config, value);
            case "datetime" -> validateDateTime(config, value);
            case "array", "list" -> validateArray(config, value);
            default -> value; // Pass through unknown types
        };
    }

    private String validateString(ParameterConfig config, Object value) {
        String str = String.valueOf(value);

        if (config.minLength() != null && str.length() < config.minLength()) {
            throw new ValidationException(String.format(
                    "Parameter '%s' must be at least %d characters (got %d)",
                    config.name(), config.minLength(), str.length()));
        }

        if (config.maxLength() != null && str.length() > config.maxLength()) {
            throw new ValidationException(String.format(
                    "Parameter '%s' must be at most %d characters (got %d)",
                    config.name(), config.maxLength(), str.length()));
        }

        if (config.pattern() != null) {
            Pattern pattern = patternCache.computeIfAbsent(
                    config.pattern(), Pattern::compile);
            if (!pattern.matcher(str).matches()) {
                throw new ValidationException(String.format(
                        "Parameter '%s' does not match required pattern '%s'",
                        config.name(), config.pattern()));
            }
        }

        if (config.allowedValues() != null && !config.allowedValues().isEmpty()) {
            if (!config.allowedValues().contains(str)) {
                throw new ValidationException(String.format(
                        "Parameter '%s' must be one of: %s (got '%s')",
                        config.name(), config.allowedValues(), str));
            }
        }

        return str;
    }

    private Integer validateInteger(ParameterConfig config, Object value) {
        int intValue;
        try {
            if (value instanceof Number n) {
                intValue = n.intValue();
            } else {
                intValue = Integer.parseInt(String.valueOf(value).trim());
            }
        } catch (NumberFormatException e) {
            throw new ValidationException(String.format(
                    "Parameter '%s' must be a valid integer (got '%s')",
                    config.name(), value));
        }

        validateNumericRange(config, intValue);
        return intValue;
    }

    private Long validateLong(ParameterConfig config, Object value) {
        long longValue;
        try {
            if (value instanceof Number n) {
                longValue = n.longValue();
            } else {
                longValue = Long.parseLong(String.valueOf(value).trim());
            }
        } catch (NumberFormatException e) {
            throw new ValidationException(String.format(
                    "Parameter '%s' must be a valid long integer (got '%s')",
                    config.name(), value));
        }

        validateNumericRange(config, longValue);
        return longValue;
    }

    private Double validateNumber(ParameterConfig config, Object value) {
        double doubleValue;
        try {
            if (value instanceof Number n) {
                doubleValue = n.doubleValue();
            } else {
                doubleValue = Double.parseDouble(String.valueOf(value).trim());
            }
        } catch (NumberFormatException e) {
            throw new ValidationException(String.format(
                    "Parameter '%s' must be a valid number (got '%s')",
                    config.name(), value));
        }

        validateNumericRange(config, doubleValue);
        return doubleValue;
    }

    private void validateNumericRange(ParameterConfig config, Number value) {
        if (config.min() != null && value.doubleValue() < config.min().doubleValue()) {
            throw new ValidationException(String.format(
                    "Parameter '%s' must be at least %s (got %s)",
                    config.name(), config.min(), value));
        }

        if (config.max() != null && value.doubleValue() > config.max().doubleValue()) {
            throw new ValidationException(String.format(
                    "Parameter '%s' must be at most %s (got %s)",
                    config.name(), config.max(), value));
        }
    }

    private Boolean validateBoolean(ParameterConfig config, Object value) {
        if (value instanceof Boolean b) {
            return b;
        }

        String str = String.valueOf(value).toLowerCase().trim();
        return switch (str) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new ValidationException(String.format(
                    "Parameter '%s' must be a valid boolean (got '%s')",
                    config.name(), value));
        };
    }

    private LocalDate validateDate(ParameterConfig config, Object value) {
        String format = config.format() != null ? config.format() : "yyyy-MM-dd";

        if (value instanceof LocalDate ld) {
            return ld;
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
            return LocalDate.parse(String.valueOf(value).trim(), formatter);
        } catch (DateTimeParseException e) {
            throw new ValidationException(String.format(
                    "Parameter '%s' must be a valid date in format '%s' (got '%s')",
                    config.name(), format, value));
        }
    }

    private LocalDateTime validateDateTime(ParameterConfig config, Object value) {
        String format = config.format() != null ? config.format() : "yyyy-MM-dd'T'HH:mm:ss";

        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
            return LocalDateTime.parse(String.valueOf(value).trim(), formatter);
        } catch (DateTimeParseException e) {
            throw new ValidationException(String.format(
                    "Parameter '%s' must be a valid datetime in format '%s' (got '%s')",
                    config.name(), format, value));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> validateArray(ParameterConfig config, Object value) {
        List<Object> list;

        if (value instanceof List) {
            list = new ArrayList<>((List<Object>) value);
        } else if (value.getClass().isArray()) {
            list = new ArrayList<>(Arrays.asList((Object[]) value));
        } else if (value instanceof String str) {
            // Try to parse comma-separated values
            list = new ArrayList<>(Arrays.asList(str.split(",")));
        } else {
            // Single value - wrap in list
            list = new ArrayList<>(Collections.singletonList(value));
        }

        // Validate min/max items
        if (config.minItems() != null && list.size() < config.minItems()) {
            throw new ValidationException(String.format(
                    "Parameter '%s' must have at least %d items (got %d)",
                    config.name(), config.minItems(), list.size()));
        }

        if (config.maxItems() != null && list.size() > config.maxItems()) {
            throw new ValidationException(String.format(
                    "Parameter '%s' must have at most %d items (got %d)",
                    config.name(), config.maxItems(), list.size()));
        }

        return list;
    }
}
