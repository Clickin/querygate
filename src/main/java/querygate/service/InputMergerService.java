package querygate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import querygate.exception.RequestBodyParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for merging input parameters from multiple sources:
 * - Path variables (from URL path)
 * - Query parameters (from URL query string)
 * - Request body (JSON, XML, or form-urlencoded)
 *
 * Priority (highest to lowest): Body > Query Parameters > Path Variables
 */
@Singleton
public class InputMergerService {

    private static final Logger LOG = LoggerFactory.getLogger(InputMergerService.class);

    private final ObjectMapper objectMapper;

    public InputMergerService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Merges parameters from multiple sources into a single Map.
     *
     * @param request       The HTTP request
     * @param pathVariables Variables extracted from the URL path
     * @param body          The raw request body (may be null)
     * @return Merged parameters map
     */
    public Map<String, Object> mergeInputs(
            HttpRequest<?> request,
            @Nullable Map<String, String> pathVariables,
            @Nullable String body) {

        Map<String, Object> merged = new LinkedHashMap<>();

        // 1. Add path variables (lowest priority)
        if (pathVariables != null && !pathVariables.isEmpty()) {
            merged.putAll(pathVariables);
            LOG.debug("Added {} path variables", pathVariables.size());
        }

        // 2. Add query parameters
        int queryParamCount = 0;
        for (String key : request.getParameters().names()) {
            List<String> values = request.getParameters().getAll(key);
            if (values.size() == 1) {
                merged.put(key, values.get(0));
            } else if (values.size() > 1) {
                merged.put(key, values);
            }
            queryParamCount++;
        }
        LOG.debug("Added {} query parameters", queryParamCount);

        // 3. Add body content (highest priority)
        if (body != null && !body.isBlank()) {
            MediaType contentType = request.getContentType()
                    .orElse(MediaType.APPLICATION_JSON_TYPE);
            Map<String, Object> bodyParams = parseBody(body, contentType);
            merged.putAll(bodyParams);
            LOG.debug("Added {} body parameters", bodyParams.size());
        }

        LOG.debug("Merged parameters: {}", merged.keySet());
        return merged;
    }

    /**
     * Parses the request body based on content type.
     */
    private Map<String, Object> parseBody(String body, MediaType contentType) {
        String type = contentType.toString().toLowerCase();

        if (type.contains("json")) {
            return parseJsonBody(body);
        } else if (type.contains("xml")) {
            return parseXmlBody(body);
        } else if (type.contains("form-urlencoded")) {
            return parseFormBody(body);
        }

        // Default to JSON for unknown types
        LOG.warn("Unknown content type '{}', attempting JSON parse", contentType);
        return parseJsonBody(body);
    }

    /**
     * Parses JSON body into a Map.
     *
     * @throws RequestBodyParseException if JSON parsing fails
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonBody(String body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            LOG.error("Failed to parse JSON body: {}", e.getMessage());
            throw new RequestBodyParseException(
                "Invalid JSON format: " + e.getMessage(),
                "application/json",
                e
            );
        }
    }

    /**
     * Parses XML body into a Map.
     * Uses secure XML parsing to prevent XXE attacks.
     *
     * @throws RequestBodyParseException if XML parsing fails
     */
    private Map<String, Object> parseXmlBody(String body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

            // Security: Disable external entities to prevent XXE attacks
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));

            return xmlToMap(doc.getDocumentElement());
        } catch (Exception e) {
            LOG.error("Failed to parse XML body: {}", e.getMessage());
            throw new RequestBodyParseException(
                "Invalid XML format: " + e.getMessage(),
                "application/xml",
                e
            );
        }
    }

    /**
     * Converts an XML element to a Map recursively.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> xmlToMap(Element element) {
        Map<String, Object> map = new LinkedHashMap<>();

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element childElement = (Element) child;
                String name = childElement.getTagName();

                if (hasChildElements(childElement)) {
                    // Nested element - recurse
                    Object existing = map.get(name);
                    Map<String, Object> childMap = xmlToMap(childElement);

                    if (existing instanceof List) {
                        ((List<Object>) existing).add(childMap);
                    } else if (existing instanceof Map) {
                        // Convert to list
                        List<Object> list = new ArrayList<>();
                        list.add(existing);
                        list.add(childMap);
                        map.put(name, list);
                    } else {
                        map.put(name, childMap);
                    }
                } else {
                    // Leaf element - get text content
                    String value = childElement.getTextContent().trim();
                    Object existing = map.get(name);

                    if (existing instanceof List) {
                        ((List<Object>) existing).add(value);
                    } else if (existing != null) {
                        // Convert to list for repeated elements
                        List<Object> list = new ArrayList<>();
                        list.add(existing);
                        list.add(value);
                        map.put(name, list);
                    } else {
                        map.put(name, value);
                    }
                }
            }
        }

        return map;
    }

    /**
     * Checks if an element has child elements.
     */
    private boolean hasChildElements(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses form-urlencoded body into a Map.
     */
    private Map<String, Object> parseFormBody(String body) {
        Map<String, Object> params = new LinkedHashMap<>();

        if (body.isBlank()) {
            return params;
        }

        String[] pairs = body.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length >= 1) {
                String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                String value = keyValue.length == 2
                        ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8)
                        : "";

                // Handle multiple values for same key
                Object existing = params.get(key);
                if (existing instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) existing;
                    list.add(value);
                } else if (existing != null) {
                    List<Object> list = new ArrayList<>();
                    list.add(existing);
                    list.add(value);
                    params.put(key, list);
                } else {
                    params.put(key, value);
                }
            }
        }

        return params;
    }
}
