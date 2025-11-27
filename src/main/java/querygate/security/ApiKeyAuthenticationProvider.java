package querygate.security;

import querygate.config.GatewayProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.AuthenticationProvider;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Authentication provider that validates API keys from request headers.
 * Only active when security is enabled in configuration.
 */
@Singleton
@Requires(property = "gateway.security.enabled", value = "true", defaultValue = "true")
public class ApiKeyAuthenticationProvider<T> implements AuthenticationProvider<T> {

    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyAuthenticationProvider.class);

    private final GatewayProperties.SecurityConfig securityConfig;

    public ApiKeyAuthenticationProvider(GatewayProperties properties) {
        this.securityConfig = properties.getSecurity();
    }

    @Override
    public Publisher<AuthenticationResponse> authenticate(T httpRequest, AuthenticationRequest<?, ?> authenticationRequest) {
        String apiKey = (String) authenticationRequest.getIdentity();

        if (apiKey == null || apiKey.isBlank()) {
            LOG.debug("No API key provided");
            return Mono.just(AuthenticationResponse.failure("API key required"));
        }

        if (securityConfig.getApiKeys().contains(apiKey)) {
            LOG.debug("API key validated successfully");
            return Mono.just(AuthenticationResponse.success(
                    "api-client",
                    List.of("ROLE_API"),
                    Map.of("keyHash", String.valueOf(apiKey.hashCode()))
            ));
        }

        LOG.warn("Invalid API key attempt");
        return Mono.just(AuthenticationResponse.failure("Invalid API key"));
    }
}
