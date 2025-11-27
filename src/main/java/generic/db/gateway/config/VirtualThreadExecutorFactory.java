package generic.db.gateway.config;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Factory for creating virtual thread executor for async SQL execution.
 * Virtual threads are ideal for I/O-bound database operations.
 */
@Factory
public class VirtualThreadExecutorFactory {

    private static final Logger LOG = LoggerFactory.getLogger(VirtualThreadExecutorFactory.class);

    private ExecutorService executorService;

    /**
     * Creates a virtual thread executor when virtual threads are enabled.
     * Virtual threads are lightweight and perfect for blocking I/O operations.
     */
    @Singleton
    @Named("gatewayVirtualExecutor")
    @Requires(property = "gateway.virtual-threads.enabled", value = "true", defaultValue = "true")
    public ExecutorService virtualThreadExecutor(GatewayProperties properties) {
        String executorName = properties.getVirtualThreads().getExecutorName();
        LOG.info("Creating virtual thread executor: {}", executorName);

        executorService = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name(executorName, 0)
                        .factory()
        );

        return executorService;
    }

    /**
     * Creates a platform thread executor when virtual threads are disabled.
     * Falls back to cached thread pool for compatibility.
     */
    @Singleton
    @Named("gatewayVirtualExecutor")
    @Requires(property = "gateway.virtual-threads.enabled", value = "false")
    public ExecutorService platformThreadExecutor() {
        LOG.info("Creating platform thread executor (virtual threads disabled)");

        executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("gateway-platform-" + t.getId());
            t.setDaemon(true);
            return t;
        });

        return executorService;
    }

    /**
     * Gracefully shuts down the executor on application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            LOG.info("Shutting down executor service");
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    LOG.warn("Executor did not terminate gracefully, forcing shutdown");
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executorService.shutdownNow();
            }
        }
    }
}
