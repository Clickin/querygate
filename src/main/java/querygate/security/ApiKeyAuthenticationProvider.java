package querygate.security;

import querygate.config.GatewayProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.AuthenticationProvider;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        this.securityConfig = properties.security();
    }

    @Override
    public Publisher<AuthenticationResponse> authenticate(T httpRequest, AuthenticationRequest<?, ?> authenticationRequest) {
        String apiKey = (String) authenticationRequest.getIdentity();

        if (apiKey == null || apiKey.isBlank()) {
            LOG.debug("No API key provided");
            return subscriber -> emit(subscriber, AuthenticationResponse.failure("API key required"));
        }

        if (securityConfig.apiKeys().contains(apiKey)) {
            LOG.debug("API key validated successfully");
            AuthenticationResponse response = AuthenticationResponse.success(
                    "api-client",
                    List.of("ROLE_API"),
                    Map.of("keyHash", String.valueOf(apiKey.hashCode()))
            );
            return subscriber -> emit(subscriber, response);
        }

        LOG.warn("Invalid API key attempt");
        return subscriber -> emit(subscriber, AuthenticationResponse.failure("Invalid API key"));
    }

    private void emit(Subscriber<? super AuthenticationResponse> subscriber, AuthenticationResponse response) {
        subscriber.onSubscribe(new Subscription() {
            private boolean done;
            @Override
            public void request(long n) {
                if (done || n <= 0) {
                    return;
                }
                done = true;
                subscriber.onNext(response);
                subscriber.onComplete();
            }

            @Override
            public void cancel() {
                done = true;
            }
        });
    }
}
