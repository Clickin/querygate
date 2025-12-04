package querygate.config;

import querygate.model.SqlType;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    public EndpointConfigLoader(GatewayProperties properties) {
        this.properties = properties;
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
            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(Files.newInputStream(path));
            parseEndpoints(config);
            LOG.info("Loaded {} endpoint configurations", endpointMap.size() + patternEndpoints.size());
        } catch (IOException e) {
            LOG.error("Failed to load endpoint configuration", e);
            throw new IllegalStateException("Failed to load endpoint configuration", e);
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
    private void parseEndpoints(Map<String, Object> config) {
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) config.get("endpoints");
        if (endpoints == null) {
            LOG.warn("No endpoints found in configuration");
            return;
        }

        for (Map<String, Object> ep : endpoints) {
            try {
                EndpointConfig endpointConfig = parseEndpointConfig(ep);
                registerEndpoint(endpointConfig);
            } catch (Exception e) {
                LOG.error("Failed to parse endpoint config: {}", ep, e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private EndpointConfig parseEndpointConfig(Map<String, Object> ep) {
        String path = (String) ep.get("path");
        String method = ((String) ep.get("method")).toUpperCase();
        String sqlId = (String) ep.get("sql-id");
        String sqlTypeStr = ((String) ep.get("sql-type")).toUpperCase();
        String description = (String) ep.get("description");

        SqlType sqlType = SqlType.valueOf(sqlTypeStr);

        // Parse validation config
        EndpointConfig.ValidationConfig validation = null;
        Map<String, Object> validationMap = (Map<String, Object>) ep.get("validation");
        if (validationMap != null) {
            validation = parseValidationConfig(validationMap);
        }

        // Parse batch config
        EndpointConfig.BatchConfig batchConfig = null;
        Map<String, Object> batchMap = (Map<String, Object>) ep.get("batch-config");
        if (batchMap != null) {
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
    private EndpointConfig.ValidationConfig parseValidationConfig(Map<String, Object> validationMap) {
        List<EndpointConfig.ParameterConfig> required = new ArrayList<>();
        List<EndpointConfig.ParameterConfig> optional = new ArrayList<>();

        List<Map<String, Object>> requiredList = (List<Map<String, Object>>) validationMap.get("required");
        if (requiredList != null) {
            for (Map<String, Object> param : requiredList) {
                required.add(parseParameterConfig(param));
            }
        }

        List<Map<String, Object>> optionalList = (List<Map<String, Object>>) validationMap.get("optional");
        if (optionalList != null) {
            for (Map<String, Object> param : optionalList) {
                optional.add(parseParameterConfig(param));
            }
        }

        return new EndpointConfig.ValidationConfig(required, optional);
    }

    @SuppressWarnings("unchecked")
    private EndpointConfig.ParameterConfig parseParameterConfig(Map<String, Object> param) {
        String name = (String) param.get("name");
        String type = (String) param.get("type");
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
        String itemKey = (String) batchMap.get("item-key");
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

    private void registerEndpoint(EndpointConfig config) {
        String path = config.path();
        String method = config.method();

        if (PATH_VARIABLE_PATTERN.matcher(path).find()) {
            // Path contains variables - use pattern matching
            Pattern pattern = compilePathPattern(path);
            patternEndpoints.add(new PatternEndpoint(method, pattern, config));
            LOG.debug("Registered pattern endpoint: {} {} -> {}", method, path, config.sqlId());
        } else {
            // Exact path match
            endpointMap.put(new EndpointKey(method, path), config);
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
