package querygate.security;

import querygate.config.GatewayProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.token.validator.TokenValidator;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        this.securityConfig = properties.security();
    }

    @Override
    public Publisher<Authentication> validateToken(String token, T request) {
        if (token == null || token.isBlank()) {
            LOG.debug("Empty API key provided");
            return this::completeWithoutAuthentication;
        }

        if (securityConfig.apiKeys().contains(token)) {
            LOG.debug("API key validated successfully");
            Authentication auth = Authentication.build(
                    "api-client",
                    List.of("ROLE_API"),
                    Map.of("keyHash", String.valueOf(token.hashCode()))
            );
            return subscriber -> emitAuthentication(subscriber, auth);
        }

        LOG.warn("Invalid API key attempt");
        return this::completeWithoutAuthentication;
    }

    private void emitAuthentication(Subscriber<? super Authentication> subscriber, Authentication authentication) {
        subscriber.onSubscribe(new Subscription() {
            private boolean done;
            @Override
            public void request(long n) {
                if (done || n <= 0) {
                    return;
                }
                done = true;
                subscriber.onNext(authentication);
                subscriber.onComplete();
            }

            @Override
            public void cancel() {
                done = true;
            }
        });
    }

    private void completeWithoutAuthentication(Subscriber<? super Authentication> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            private boolean done;
            @Override
            public void request(long n) {
                if (done) {
                    return;
                }
                done = true;
                subscriber.onComplete();
            }

            @Override
            public void cancel() {
                done = true;
            }
        });
    }
}
