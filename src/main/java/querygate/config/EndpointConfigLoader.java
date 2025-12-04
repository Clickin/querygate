package querygate.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import querygate.model.SqlType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Loads and manages endpoint configuration from external YAML file.
 * Supports path pattern matching and runtime reload.
 */
@Singleton
@Context
public class EndpointConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(EndpointConfigLoader.class);

    // Pattern to match path variables like {id}, {userId}
    private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile("\\{([^}]+)}");

    private final GatewayProperties properties;

    // Map of exact endpoint key (METHOD:PATH) to config
    private final Map<EndpointKey, EndpointConfig> endpointMap = new ConcurrentHashMap<>();

    // List of configs with path variables (need pattern matching)
    // Using CopyOnWriteArrayList for thread-safe iteration during hot reload
    private final List<PatternEndpoint> patternEndpoints = new CopyOnWriteArrayList<>();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchema endpointSchema;

    public EndpointConfigLoader(GatewayProperties properties) {
        this.properties = properties;
        this.endpointSchema = loadSchema();
    }

    @EventListener
    public void onStartup(StartupEvent event) {
        loadConfiguration();
    }

    /**
     * Loads endpoint configuration from the YAML file.
     */
    public synchronized void loadConfiguration() {
        String configPath = properties.endpointConfigPath();
        Path path = Paths.get(configPath);

        LOG.info("Loading endpoint configuration from: {}", path.toAbsolutePath());

        if (!Files.exists(path)) {
            LOG.warn("Endpoint configuration file does not exist: {}. Creating sample...", path);
            createSampleConfig(path);
            return;
        }

        try {
            String yamlContent = Files.readString(path);
            Map<String, Object> config = loadYaml(yamlContent);
            validateAgainstSchema(config);

            Map<EndpointKey, EndpointConfig> newEndpointMap = new ConcurrentHashMap<>();
            List<PatternEndpoint> newPatternEndpoints = new CopyOnWriteArrayList<>();

            parseEndpoints(config, newEndpointMap, newPatternEndpoints);

            endpointMap.clear();
            endpointMap.putAll(newEndpointMap);
            patternEndpoints.clear();
            patternEndpoints.addAll(newPatternEndpoints);

            LOG.info("Loaded {} endpoint configurations", endpointMap.size() + patternEndpoints.size());
        } catch (IOException e) {
            LOG.error("Failed to load endpoint configuration", e);
            throw new IllegalStateException("Failed to load endpoint configuration", e);
        } catch (IllegalStateException e) {
            LOG.error("Invalid endpoint configuration: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Reloads the endpoint configuration from file.
     */
    public synchronized void reloadConfiguration() {
        LOG.info("Reloading endpoint configuration");
        endpointMap.clear();
        patternEndpoints.clear();
        loadConfiguration();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(String yamlContent) {
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(yamlContent);
        if (loaded == null) {
            throw new IllegalStateException("Endpoint configuration file is empty");
        }
        if (!(loaded instanceof Map)) {
            throw new IllegalStateException("Endpoint configuration root must be a map");
        }
        return (Map<String, Object>) loaded;
    }

    private void validateAgainstSchema(Map<String, Object> config) {
        if (endpointSchema == null) {
            return;
        }
        Set<ValidationMessage> validationMessages = endpointSchema.validate(objectMapper.valueToTree(config));
        if (!validationMessages.isEmpty()) {
            String message = validationMessages.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; "));
            throw new IllegalStateException("Endpoint configuration failed schema validation: " + message);
        }
    }

    private JsonSchema loadSchema() {
        try (InputStream schemaStream = getClass().getClassLoader()
                .getResourceAsStream("schemas/endpoint-config.schema.json")) {
            if (schemaStream == null) {
                LOG.warn("Endpoint config schema not found on classpath; skipping validation");
                return null;
            }
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            return factory.getSchema(objectMapper.readTree(schemaStream));
        } catch (Exception e) {
            LOG.warn("Failed to load endpoint config schema; validation disabled", e);
            return null;
        }
    }

    /**
     * Finds an endpoint configuration by exact method and path match.
     */
    public EndpointConfig getEndpoint(String method, String path) {
        return endpointMap.get(new EndpointKey(method.toUpperCase(), path));
    }

    /**
     * Finds a matching endpoint configuration, including pattern matching.
     *
     * @param method      HTTP method (GET, POST, etc.)
     * @param requestPath The actual request path
     * @return Matching EndpointConfig or null if not found
     */
    public EndpointConfig findMatchingEndpoint(String method, String requestPath) {
        String upperMethod = method.toUpperCase();

        // Try exact match first
        EndpointConfig exact = endpointMap.get(new EndpointKey(upperMethod, requestPath));
        if (exact != null) {
            return exact;
        }

        // Try pattern matching
        for (PatternEndpoint pe : patternEndpoints) {
            if (pe.method().equals(upperMethod) && pe.pattern().matcher(requestPath).matches()) {
                return pe.config();
            }
        }

        return null;
    }

    /**
     * Extracts path variables from a request path based on the endpoint pattern.
     *
     * @param pattern     The endpoint pattern (e.g., /api/users/{id})
     * @param requestPath The actual request path (e.g., /api/users/123)
     * @return Map of variable names to values
     */
    public Map<String, String> extractPathVariables(String pattern, String requestPath) {
        Map<String, String> variables = new HashMap<>();

        String[] patternParts = pattern.split("/");
        String[] pathParts = requestPath.split("/");

        for (int i = 0; i < patternParts.length && i < pathParts.length; i++) {
            Matcher matcher = PATH_VARIABLE_PATTERN.matcher(patternParts[i]);
            if (matcher.matches()) {
                String varName = matcher.group(1);
                variables.put(varName, pathParts[i]);
            }
        }

        return variables;
    }

    /**
     * Returns all registered endpoints for debugging/admin purposes.
     */
    public List<EndpointConfig> getAllEndpoints() {
        List<EndpointConfig> all = new ArrayList<>(endpointMap.values());
        patternEndpoints.forEach(pe -> all.add(pe.config()));
        return all;
    }

    /**
     * Checks if an endpoint exists for the given method and path.
     */
    public boolean hasEndpoint(String method, String path) {
        return findMatchingEndpoint(method, path) != null;
    }

    // --- Private methods ---

    @SuppressWarnings("unchecked")
    private void parseEndpoints(Map<String, Object> config,
                                Map<EndpointKey, EndpointConfig> newEndpointMap,
                                List<PatternEndpoint> newPatternEndpoints) {
        Object endpointsObj = config.get("endpoints");
        if (endpointsObj == null) {
            throw new IllegalStateException("No endpoints found in configuration");
        }
        if (!(endpointsObj instanceof List<?> endpoints)) {
            throw new IllegalStateException("'endpoints' must be a list");
        }

        for (Object epObj : endpoints) {
            if (!(epObj instanceof Map<?, ?> ep)) {
                throw new IllegalStateException("Endpoint definition must be a map: " + epObj);
            }
            EndpointConfig endpointConfig = parseEndpointConfig((Map<String, Object>) ep);
            registerEndpoint(endpointConfig, newEndpointMap, newPatternEndpoints);
        }
    }

    @SuppressWarnings("unchecked")
    private EndpointConfig parseEndpointConfig(Map<String, Object> ep) {
        String path = requireString(ep, "path", "endpoint");
        String method = requireString(ep, "method", path).toUpperCase(Locale.ROOT);
        String sqlId = requireString(ep, "sql-id", method + " " + path);
        String sqlTypeStr = requireString(ep, "sql-type", method + " " + path).toUpperCase(Locale.ROOT);
        String description = (String) ep.get("description");

        SqlType sqlType;
        try {
            sqlType = SqlType.valueOf(sqlTypeStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid sql-type '" + sqlTypeStr + "' for endpoint " + method + " " + path);
        }

        // Parse validation config
        EndpointConfig.ValidationConfig validation = null;
        Object validationObj = ep.get("validation");
        Map<String, Object> validationMap = validationObj != null
                ? asMap(validationObj, "validation", method + " " + path)
                : null;
        if (validationMap != null && !validationMap.isEmpty()) {
            validation = parseValidationConfig(validationMap, method + " " + path);
        }

        // Parse batch config
        EndpointConfig.BatchConfig batchConfig = null;
        Object batchObj = ep.get("batch-config");
        Map<String, Object> batchMap = batchObj != null
                ? asMap(batchObj, "batch-config", method + " " + path)
                : null;
        if (batchMap != null && !batchMap.isEmpty()) {
            batchConfig = parseBatchConfig(batchMap);
        }

        // Parse response format
        EndpointConfig.ResponseFormat responseFormat = null;
        String responseFormatStr = (String) ep.get("response-format");
        if (responseFormatStr != null && !responseFormatStr.isBlank()) {
            try {
                responseFormat = EndpointConfig.ResponseFormat.valueOf(responseFormatStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                LOG.warn("Invalid response-format '{}' for endpoint {} {}, defaulting to WRAPPED",
                        responseFormatStr, method, path);
            }
        }

        return new EndpointConfig(path, method, sqlId, sqlType, description, validation, batchConfig, responseFormat);
    }

    @SuppressWarnings("unchecked")
    private EndpointConfig.ValidationConfig parseValidationConfig(Map<String, Object> validationMap, String context) {
        List<EndpointConfig.ParameterConfig> required = new ArrayList<>();
        List<EndpointConfig.ParameterConfig> optional = new ArrayList<>();

        Object requiredObj = validationMap.get("required");
        if (requiredObj instanceof List<?> requiredList) {
            for (Object param : requiredList) {
                required.add(parseParameterConfig(asMap(param, "validation.required", context)));
            }
        } else if (requiredObj != null) {
            throw new IllegalStateException("'validation.required' must be a list for endpoint " + context);
        }

        Object optionalObj = validationMap.get("optional");
        if (optionalObj instanceof List<?> optionalList) {
            for (Object param : optionalList) {
                optional.add(parseParameterConfig(asMap(param, "validation.optional", context)));
            }
        } else if (optionalObj != null) {
            throw new IllegalStateException("'validation.optional' must be a list for endpoint " + context);
        }

        return new EndpointConfig.ValidationConfig(required, optional);
    }

    @SuppressWarnings("unchecked")
    private EndpointConfig.ParameterConfig parseParameterConfig(Map<String, Object> param) {
        String name = requireString(param, "name", "parameter");
        String type = requireString(param, "type", "parameter " + name);
        String source = (String) param.get("source");
        Integer minLength = getInteger(param, "min-length");
        Integer maxLength = getInteger(param, "max-length");
        String pattern = (String) param.get("pattern");
        Number min = (Number) param.get("min");
        Number max = (Number) param.get("max");
        List<String> allowedValues = (List<String>) param.get("allowed-values");
        Object defaultValue = param.get("default");
        String format = (String) param.get("format");
        Integer minItems = getInteger(param, "min-items");
        Integer maxItems = getInteger(param, "max-items");

        return new EndpointConfig.ParameterConfig(
                name, type, source, minLength, maxLength, pattern,
                min, max, allowedValues, defaultValue, format, minItems, maxItems
        );
    }

    private EndpointConfig.BatchConfig parseBatchConfig(Map<String, Object> batchMap) {
        String itemKey = requireString(batchMap, "item-key", "batch-config");
        Integer batchSize = getInteger(batchMap, "batch-size");
        return new EndpointConfig.BatchConfig(itemKey, batchSize != null ? batchSize : 100);
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(value.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value, String key, String context) {
        if (value == null) {
            throw new IllegalStateException("'" + key + "' entry cannot be null for endpoint " + context);
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalStateException("'" + key + "' must be an object for endpoint " + context);
    }

    private String requireString(Map<String, Object> map, String key, String context) {
        if (map == null) {
            throw new IllegalStateException("Configuration section missing for " + context);
        }
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("Missing required field '" + key + "' for " + context);
        }
        return value.toString();
    }

    private void registerEndpoint(EndpointConfig config,
                                  Map<EndpointKey, EndpointConfig> newEndpointMap,
                                  List<PatternEndpoint> newPatternEndpoints) {
        String path = config.path();
        String method = config.method();

        if (PATH_VARIABLE_PATTERN.matcher(path).find()) {
            // Path contains variables - use pattern matching
            Pattern pattern = compilePathPattern(path);
            newPatternEndpoints.add(new PatternEndpoint(method, pattern, config));
            LOG.debug("Registered pattern endpoint: {} {} -> {}", method, path, config.sqlId());
        } else {
            // Exact path match
            newEndpointMap.put(new EndpointKey(method, path), config);
            LOG.debug("Registered exact endpoint: {} {} -> {}", method, path, config.sqlId());
        }
    }

    /**
     * Compiles a path pattern into a regex.
     * /api/users/{id} -> /api/users/([^/]+)
     */
    private Pattern compilePathPattern(String path) {
        String regex = PATH_VARIABLE_PATTERN.matcher(path).replaceAll("([^/]+)");
        // Escape special regex characters in the rest of the path
        regex = regex.replace(".", "\\.");
        return Pattern.compile("^" + regex + "$");
    }

    private void createSampleConfig(Path path) {
        String sample = """
                version: "1.0"

                endpoints:
                  - path: /api/health
                    method: GET
                    sql-id: HealthMapper.ping
                    sql-type: SELECT
                    description: "Health check endpoint"
                    validation:
                      required: []
                      optional: []
                """;

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, sample);
            LOG.info("Created sample endpoint configuration at: {}", path);
        } catch (IOException e) {
            LOG.error("Failed to create sample configuration", e);
        }
    }

    // Helper records
    private record EndpointKey(String method, String path) {
    }

    private record PatternEndpoint(String method, Pattern pattern, EndpointConfig config) {
    }
}
