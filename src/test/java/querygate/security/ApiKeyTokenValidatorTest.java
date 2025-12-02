package querygate.security;

import io.micronaut.security.authentication.Authentication;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import querygate.config.GatewayProperties;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

class ApiKeyTokenValidatorTest {

    @Test
    void returnsRoleForValidKey() {
        GatewayProperties properties = propertiesWithKeys(List.of("secret-key"));

        ApiKeyTokenValidator<Object> validator = new ApiKeyTokenValidator<>(properties);

        Authentication authentication = first(validator.validateToken("secret-key", null));

        Assertions.assertThat(authentication).isNotNull();
        Assertions.assertThat(authentication.getRoles()).containsExactly("ROLE_API");
        Assertions.assertThat(authentication.getAttributes())
                .containsEntry("keyHash", String.valueOf("secret-key".hashCode()));
    }

    @Test
    void returnsEmptyForUnknownOrBlankKeys() {
        GatewayProperties properties = propertiesWithKeys(List.of("secret-key"));

        ApiKeyTokenValidator<Object> validator = new ApiKeyTokenValidator<>(properties);

        Authentication missing = first(validator.validateToken(null, null));
        Authentication wrong = first(validator.validateToken("wrong-key", null));

        Assertions.assertThat(missing).isNull();
        Assertions.assertThat(wrong).isNull();
    }

    private <T> T first(Publisher<T> publisher) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(T t) {
                value.set(t);
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }

        return value.get();
    }

    private GatewayProperties propertiesWithKeys(List<String> keys) {
        GatewayProperties.SecurityConfig security =
                new GatewayProperties.SecurityConfig(true, "X-API-Key", keys);

        return new GatewayProperties(null, null, null, null, null, security);
    }
}
