package querygate.service;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Service for serializing responses based on content negotiation.
 * Supports JSON (default) and XML responses based on Accept header.
 */
@Singleton
public class ResponseSerializer {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseSerializer.class);

    private final ObjectMapper jsonMapper;
    private final XmlMapper xmlMapper;

    public ResponseSerializer(ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        this.xmlMapper = new XmlMapper();
    }

    /**
     * Determines if the client accepts XML responses.
     */
    public boolean acceptsXml(HttpRequest<?> request) {
        return request.getHeaders()
                .accept()
                .stream()
                .anyMatch(mt -> mt.getName().contains("xml"));
    }

    /**
     * Determines if the client accepts JSON responses.
     */
    public boolean acceptsJson(HttpRequest<?> request) {
        List<MediaType> accept = request.getHeaders().accept();
        // JSON is the default if no Accept header or accepts anything
        if (accept.isEmpty()) {
            return true;
        }
        return accept.stream()
                .anyMatch(mt -> mt.getName().contains("json") || mt.getName().equals("*/*"));
    }

    /**
     * Gets the preferred media type based on Accept header.
     */
    public MediaType getPreferredMediaType(HttpRequest<?> request) {
        if (acceptsXml(request) && !acceptsJson(request)) {
            return MediaType.APPLICATION_XML_TYPE;
        }
        return MediaType.APPLICATION_JSON_TYPE;
    }

    /**
     * Serializes a Map to JSON string.
     */
    public String toJson(Map<String, Object> data) {
        try {
            return jsonMapper.writeValueAsString(data);
        } catch (Exception e) {
            LOG.error("Failed to serialize to JSON", e);
            return "{\"error\":\"Serialization failed\"}";
        }
    }

    /**
     * Serializes a Map to XML string.
     */
    public String toXml(Map<String, Object> data) {
        try {
            // Wrap in a root element for valid XML
            XmlResponse response = new XmlResponse(data);
            return xmlMapper.writeValueAsString(response);
        } catch (Exception e) {
            LOG.error("Failed to serialize to XML", e);
            return "<error>Serialization failed</error>";
        }
    }

    /**
     * Serializes data to the appropriate format based on Accept header.
     */
    public String serialize(HttpRequest<?> request, Map<String, Object> data) {
        if (acceptsXml(request) && !acceptsJson(request)) {
            return toXml(data);
        }
        return toJson(data);
    }

    /**
     * Wrapper class for XML serialization with a root element.
     */
    @JacksonXmlRootElement(localName = "response")
    public static class XmlResponse {
        private final Map<String, Object> data;

        public XmlResponse(Map<String, Object> data) {
            this.data = data;
        }

        public Map<String, Object> getData() {
            return data;
        }
    }
}
