package querygate.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@MicronautTest(startApplication = false)
class ApiKeyAuthConfigurationTest {

    @Inject
    ApiKeyTokenReader tokenReader;

    @Inject
    ApiKeyTokenValidator<HttpRequest<?>> tokenValidator;

    @Test
    void readsTokenFromConfiguredHeader() {
        HttpRequest<?> request = HttpRequest.GET("/api/ping")
                .header("Authorization", "Key secret-key");

        Optional<String> token = tokenReader.findToken(request);

        Assertions.assertThat(token).contains("secret-key");
    }

    @Test
    void rejectsMissingOrInvalidKey() throws InterruptedException {
        HttpRequest<?> request = HttpRequest.GET("/api/ping");

        // Missing header should yield no token and no authentication
        Assertions.assertThat(tokenReader.findToken(request)).isEmpty();
        Assertions.assertThat(blockingAuth(tokenValidator.validateToken(null, request))).isNull();

        // Wrong scheme or key should also fail validation
        HttpRequest<?> bearerRequest = HttpRequest.GET("/api/ping")
                .header("Authorization", "Bearer secret-key");
        Assertions.assertThat(tokenReader.findToken(bearerRequest)).isEmpty();
        Assertions.assertThat(blockingAuth(tokenValidator.validateToken("wrong-key", request))).isNull();
    }

    @Test
    void acceptsConfiguredApiKey() throws InterruptedException {
        Authentication authentication = blockingAuth(tokenValidator.validateToken("secret-key", null));

        Assertions.assertThat(authentication).isNotNull();
        Assertions.assertThat(authentication.getRoles()).contains("ROLE_API");
        Assertions.assertThat(authentication.getAttributes()).containsKey("keyHash");
    }

    /**
     * Blocks on the authentication publisher for a short period to retrieve a single authentication.
     */
    private Authentication blockingAuth(Publisher<Authentication> publisher) throws InterruptedException {
        AtomicReference<Authentication> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(Authentication authentication) {
                ref.set(authentication);
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        latch.await(2, TimeUnit.SECONDS);
        return ref.get();
    }
}
