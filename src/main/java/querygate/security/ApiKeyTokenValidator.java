package querygate.security;

import querygate.config.GatewayProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.token.validator.TokenValidator;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Token validator that validates API keys against configured allowed keys.
 */
@Singleton
@Requires(property = "gateway.security.enabled", value = "true", defaultValue = "true")
public class ApiKeyTokenValidator<T> implements TokenValidator<T> {

    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyTokenValidator.class);

    private final GatewayProperties.SecurityConfig securityConfig;

    public ApiKeyTokenValidator(GatewayProperties properties) {
        this.securityConfig = properties.getSecurity();
    }

    @Override
    public Publisher<Authentication> validateToken(String token, T request) {
        if (token == null || token.isBlank()) {
            LOG.debug("Empty API key provided");
            return Mono.empty();
        }

        // Use constant-time comparison to prevent timing attacks
        if (isValidApiKey(token)) {
            LOG.debug("API key validated successfully");
            return Mono.just(Authentication.build(
                    "api-client",
                    List.of("ROLE_API"),
                    Map.of("keyHash", String.valueOf(token.hashCode()))
            ));
        }

        LOG.warn("Invalid API key attempt");
        return Mono.empty();
    }

    /**
     * Validates API key using constant-time comparison to prevent timing attacks.
     * Compares against all configured keys in constant time regardless of which one matches.
     */
    private boolean isValidApiKey(String token) {
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        boolean valid = false;

        // Always compare against all keys to maintain constant time
        for (String configuredKey : securityConfig.getApiKeys()) {
            byte[] keyBytes = configuredKey.getBytes(StandardCharsets.UTF_8);
            // MessageDigest.isEqual performs constant-time comparison
            if (MessageDigest.isEqual(tokenBytes, keyBytes)) {
                valid = true;
                // Don't return early - continue checking all keys for constant time
            }
        }

        return valid;
    }
}
