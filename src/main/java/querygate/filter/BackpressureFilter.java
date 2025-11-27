package querygate.filter;

import querygate.config.GatewayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import jakarta.annotation.PostConstruct;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP filter that implements backpressure by limiting concurrent requests.
 * Returns 429 Too Many Requests when the system is overloaded, signaling retry semantics.
 */
@Filter("/api/**")
@Requires(property = "gateway.backpressure.enabled", value = "true", defaultValue = "true")
public class BackpressureFilter implements HttpServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(BackpressureFilter.class);

    private final GatewayProperties.BackpressureConfig config;
    private final MeterRegistry meterRegistry;
    private final Semaphore semaphore;
    private final AtomicInteger activeRequests;
    private final AtomicInteger rejectedRequests;

    public BackpressureFilter(GatewayProperties properties, MeterRegistry meterRegistry) {
        this.config = properties.getBackpressure();
        this.meterRegistry = meterRegistry;
        this.semaphore = new Semaphore(config.getMaxConcurrentRequests(), true);
        this.activeRequests = new AtomicInteger(0);
        this.rejectedRequests = new AtomicInteger(0);

        LOG.info("Backpressure filter initialized with max concurrent requests: {}",
                config.getMaxConcurrentRequests());
    }

    @PostConstruct
    void registerMetrics() {
        meterRegistry.gauge("gateway.backpressure.active_requests", activeRequests);
        meterRegistry.gauge("gateway.backpressure.available_permits", semaphore, Semaphore::availablePermits);
        meterRegistry.gauge("gateway.backpressure.rejected_total", rejectedRequests);
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        // Try to acquire permit with timeout
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Mono.just(serviceUnavailable("Request interrupted"));
        }

        if (!acquired) {
            rejectedRequests.incrementAndGet();
            LOG.warn("Request rejected due to backpressure: {} {} (active={})",
                    request.getMethod(), request.getPath(), activeRequests.get());
            meterRegistry.counter("gateway.backpressure.rejections",
                    "path", request.getPath()).increment();
            return Mono.just(tooManyRequests("Too many requests, please retry later"));
        }

        activeRequests.incrementAndGet();
        LOG.trace("Request acquired permit: {} {} (active={})",
                request.getMethod(), request.getPath(), activeRequests.get());

        return Mono.from(chain.proceed(request))
                .timeout(Duration.ofMillis(config.getRequestTimeoutMs()))
                .doFinally(signalType -> {
                    semaphore.release();
                    activeRequests.decrementAndGet();
                    LOG.trace("Request released permit (signal={}): active={}",
                            signalType, activeRequests.get());
                })
                .onErrorResume(throwable -> {
                    if (throwable instanceof java.util.concurrent.TimeoutException) {
                        LOG.warn("Request timed out: {} {}", request.getMethod(), request.getPath());
                        meterRegistry.counter("gateway.backpressure.timeouts",
                                "path", request.getPath()).increment();
                        return Mono.just(HttpResponse.status(HttpStatus.GATEWAY_TIMEOUT)
                                .body(Map.of(
                                        "success", false,
                                        "error", "Request Timeout",
                                        "message", "Request processing took too long"
                                )));
                    }
                    return Mono.error(throwable);
                });
    }

    private MutableHttpResponse<?> serviceUnavailable(String message) {
        return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(Map.of(
                        "success", false,
                        "error", "Service Unavailable",
                        "message", message
                ));
    }

    private MutableHttpResponse<?> tooManyRequests(String message) {
        return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "5")
                .body(Map.of(
                        "success", false,
                        "error", "Too Many Requests",
                        "message", message
                ));
    }

    @Override
    public int getOrder() {
        return -100; // Run early in the filter chain
    }
}
