package querygate;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import jakarta.inject.Inject;

/**
 * Smoke tests for API endpoints.
 * Verifies that the application is responding to HTTP requests.
 */
@MicronautTest
class ApiEndpointSmokeTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void testHealthEndpointIsAvailable() {
        // /health endpoint is public (no authentication required)
        HttpResponse<String> response = client.toBlocking().exchange(
            HttpRequest.GET("/health"),
            String.class
        );

        Assertions.assertNotNull(response, "Health endpoint should respond");
        Assertions.assertTrue(response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300,
            "Health endpoint should return 2xx status");
    }

    @Test
    void testPrometheusEndpointIsAvailable() {
        // /prometheus endpoint is public (no authentication required)
        HttpResponse<String> response = client.toBlocking().exchange(
            HttpRequest.GET("/prometheus"),
            String.class
        );

        Assertions.assertNotNull(response, "Prometheus endpoint should respond");
        Assertions.assertTrue(response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300,
            "Prometheus endpoint should return 2xx status");
    }

    @Test
    void testMetricsEndpointIsAvailable() {
        // /metrics endpoint is public (no authentication required)
        HttpResponse<String> response = client.toBlocking().exchange(
            HttpRequest.GET("/metrics"),
            String.class
        );

        Assertions.assertNotNull(response, "Metrics endpoint should respond");
        Assertions.assertTrue(response.getStatus().getCode() >= 200 && response.getStatus().getCode() < 300,
            "Metrics endpoint should return 2xx status");
    }

    @Test
    void testApiGatewayIsResponsive() {
        // Verify API gateway controller is loaded
        // This will return 401 unauthorized since we're not providing auth,
        // but it proves the endpoint is being handled
        try {
            client.toBlocking().exchange(
                HttpRequest.GET("/api/ping"),
                String.class
            );
        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            // Expected 401 Unauthorized - means the endpoint exists
            Assertions.assertEquals(401, e.getStatus().getCode(),
                "API endpoint should be responsive (returns 401 for missing auth)");
        }
    }

    @Test
    void testUnknownEndpointReturns404() {
        // Non-existent endpoints should return 404
        try {
            client.toBlocking().exchange(
                HttpRequest.GET("/api/nonexistent-endpoint-xyz"),
                String.class
            );
            // If no exception, check status
            Assertions.fail("Expected 404 response for non-existent endpoint");
        } catch (io.micronaut.http.client.exceptions.HttpClientResponseException e) {
            Assertions.assertTrue(e.getStatus().getCode() >= 400,
                "Unknown endpoint should return 4xx error");
        }
    }
}
