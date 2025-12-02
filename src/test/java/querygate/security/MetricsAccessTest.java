package querygate.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Integration checks to ensure management metrics remain reachable without authentication.
 */
@MicronautTest
class MetricsAccessTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void prometheusEndpointIsPublic() {
        HttpResponse<String> response =
                client.toBlocking().exchange(HttpRequest.GET("/prometheus"), String.class);

        Assertions.assertThat(response.getStatus().getCode()).isEqualTo(HttpStatus.OK.getCode());
        Assertions.assertThat(response.getBody()).isPresent();
    }

    @Test
    void metricsEndpointIsPublic() {
        HttpResponse<String> response =
                client.toBlocking().exchange(HttpRequest.GET("/metrics"), String.class);

        Assertions.assertThat(response.getStatus().getCode()).isEqualTo(HttpStatus.OK.getCode());
        Assertions.assertThat(response.getBody()).isPresent();
    }
}
