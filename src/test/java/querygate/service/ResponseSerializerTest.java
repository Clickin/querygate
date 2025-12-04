package querygate.service;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ResponseSerializer.
 * Tests content negotiation and serialization to JSON and XML.
 */
@MicronautTest
class ResponseSerializerTest {

    @Inject
    ResponseSerializer responseSerializer;

    @Test
    void testWhenNoAcceptHeaderThenDefaultsToJson() {
        // Arrange
        HttpRequest<?> request = HttpRequest.GET("/api/test");

        // Act
        boolean acceptsJson = responseSerializer.acceptsJson(request);
        MediaType preferred = responseSerializer.getPreferredMediaType(request);

        // Assert
        assertThat(acceptsJson).isTrue();
        assertThat((Object) preferred).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
    }

    @Test
    void testWhenAcceptJsonThenAcceptsJson() {
        // Arrange
        HttpRequest<?> request = HttpRequest.GET("/api/test")
                .accept(MediaType.APPLICATION_JSON);

        // Act
        boolean acceptsJson = responseSerializer.acceptsJson(request);

        // Assert
        assertThat(acceptsJson).isTrue();
    }

    @Test
    void testWhenAcceptXmlThenAcceptsXml() {
        // Arrange
        HttpRequest<?> request = HttpRequest.GET("/api/test")
                .accept(MediaType.APPLICATION_XML);

        // Act
        boolean acceptsXml = responseSerializer.acceptsXml(request);

        // Assert
        assertThat(acceptsXml).isTrue();
    }

    @Test
    void testWhenAcceptWildcardThenDefaultsToJson() {
        // Arrange
        HttpRequest<?> request = HttpRequest.GET("/api/test")
                .accept(MediaType.ALL);

        // Act
        boolean acceptsJson = responseSerializer.acceptsJson(request);
        MediaType preferred = responseSerializer.getPreferredMediaType(request);

        // Assert
        assertThat(acceptsJson).isTrue();
        assertThat((Object) preferred).isEqualTo(MediaType.APPLICATION_JSON_TYPE);
    }

    @Test
    void testWhenAcceptXmlOnlyThenReturnsXmlAsPreferred() {
        // Arrange
        HttpRequest<?> request = HttpRequest.GET("/api/test")
                .accept(MediaType.APPLICATION_XML);

        // Act
        MediaType preferred = responseSerializer.getPreferredMediaType(request);

        // Assert
        assertThat((Object) preferred).isEqualTo(MediaType.APPLICATION_XML_TYPE);
    }

    @Test
    void testWhenSerializingSimpleMapToJsonThenReturnsValidJson() {
        // Arrange
        Map<String, Object> data = Map.of("name", "Alice", "age", 30);

        // Act
        String json = responseSerializer.toJson(data);

        // Assert
        assertThat(json)
                .contains("\"name\":\"Alice\"")
                .contains("\"age\":30");
    }

    @Test
    void testWhenSerializingNestedMapToJsonThenPreservesStructure() {
        // Arrange
        Map<String, Object> data = Map.of(
                "user", Map.of("name", "Bob", "email", "bob@example.com"),
                "status", "active"
        );

        // Act
        String json = responseSerializer.toJson(data);

        // Assert
        assertThat(json)
                .contains("\"user\"")
                .contains("\"name\":\"Bob\"")
                .contains("\"email\":\"bob@example.com\"");
    }

    @Test
    void testWhenSerializingListToJsonThenPreservesArray() {
        // Arrange
        Map<String, Object> data = Map.of(
                "items", List.of("apple", "banana", "cherry")
        );

        // Act
        String json = responseSerializer.toJson(data);

        // Assert
        assertThat(json)
                .contains("\"items\"")
                .contains("apple")
                .contains("banana");
    }

    @Test
    void testWhenSerializingNullValuesThenHandlesGracefully() {
        // Arrange
        Map<String, Object> data = Map.of(
                "name", "Charlie",
                "middleName", null
        );

        // Act
        String json = responseSerializer.toJson(data);

        // Assert
        assertThat(json)
                .contains("\"name\":\"Charlie\"")
                .contains("\"middleName\":null");
    }

    @Test
    void testWhenSerializingToXmlThenWrapsInRootElement() {
        // Arrange
        Map<String, Object> data = Map.of("message", "Hello World");

        // Act
        String xml = responseSerializer.toXml(data);

        // Assert
        assertThat(xml)
                .startsWith("<?xml")
                .contains("<response")
                .contains("Hello World");
    }

    @Test
    void testWhenSerializingComplexDataToXmlThenCreatesValidXml() {
        // Arrange
        Map<String, Object> data = Map.of(
                "id", 1,
                "name", "Dana",
                "active", true
        );

        // Act
        String xml = responseSerializer.toXml(data);

        // Assert
        assertThat(xml)
                .contains("<response")
                .contains("Dana");
    }

    @Test
    void testWhenSerializeCalledWithJsonRequestThenReturnsJson() {
        // Arrange
        HttpRequest<?> request = HttpRequest.GET("/api/test")
                .accept(MediaType.APPLICATION_JSON);
        Map<String, Object> data = Map.of("result", "success");

        // Act
        String serialized = responseSerializer.serialize(request, data);

        // Assert
        assertThat(serialized)
                .contains("\"result\":\"success\"");
    }

    @Test
    void testWhenSerializeCalledWithXmlRequestThenReturnsXml() {
        // Arrange
        HttpRequest<?> request = HttpRequest.GET("/api/test")
                .accept(MediaType.APPLICATION_XML);
        Map<String, Object> data = Map.of("result", "success");

        // Act
        String serialized = responseSerializer.serialize(request, data);

        // Assert
        assertThat(serialized)
                .contains("<response");
    }

    @Test
    void testWhenSerializeCalledWithNoPreferenceThenDefaultsToJson() {
        // Arrange
        HttpRequest<?> request = HttpRequest.GET("/api/test");
        Map<String, Object> data = Map.of("result", "success");

        // Act
        String serialized = responseSerializer.serialize(request, data);

        // Assert
        assertThat(serialized)
                .contains("\"result\":\"success\"");
    }

    @Test
    void testWhenSerializingEmptyMapThenHandlesGracefully() {
        // Arrange
        Map<String, Object> data = Map.of();

        // Act
        String json = responseSerializer.toJson(data);
        String xml = responseSerializer.toXml(data);

        // Assert
        assertThat(json).contains("{}");
        assertThat(xml).contains("<response");
    }

    @Test
    void testWhenSerializingBooleanValuesThenPreservesTypes() {
        // Arrange
        Map<String, Object> data = Map.of(
                "success", true,
                "error", false
        );

        // Act
        String json = responseSerializer.toJson(data);

        // Assert
        assertThat(json)
                .contains("\"success\":true")
                .contains("\"error\":false");
    }

    @Test
    void testWhenSerializingNumericValuesThenPreservesTypes() {
        // Arrange
        Map<String, Object> data = Map.of(
                "count", 42,
                "rating", 4.5
        );

        // Act
        String json = responseSerializer.toJson(data);

        // Assert
        assertThat(json)
                .contains("\"count\":42")
                .contains("\"rating\":4.5");
    }
}
