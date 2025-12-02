package querygate.security;

import querygate.config.GatewayProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.token.reader.TokenReader;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Token reader that extracts API keys from the configured request header.
 */
@Singleton
@Requires(property = "gateway.security.enabled", value = "true", defaultValue = "true")
public class ApiKeyTokenReader implements TokenReader<HttpRequest<?>> {

    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyTokenReader.class);

    private final String apiKeyHeader;

    public ApiKeyTokenReader(GatewayProperties properties) {
        this.apiKeyHeader = properties.security().apiKeyHeader();
    }

    @Override
    public Optional<String> findToken(HttpRequest<?> request) {
        Optional<String> rawHeader = request.getHeaders().getFirst(apiKeyHeader);
        LOG.trace("Looking for API key in header '{}': present={}", apiKeyHeader, rawHeader.isPresent());

        if (rawHeader.isEmpty()) {
            return Optional.empty();
        }

        String value = rawHeader.get().trim();

        if ("Authorization".equalsIgnoreCase(apiKeyHeader)) {
            // Expect format: "Key <token>" to avoid confusion with Bearer/JWT
            String prefix = "Key ";
            if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                LOG.debug("Authorization header present but missing Key scheme");
                return Optional.empty();
            }
            String token = value.substring(prefix.length()).trim();
            return token.isEmpty() ? Optional.empty() : Optional.of(token);
        }

        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
