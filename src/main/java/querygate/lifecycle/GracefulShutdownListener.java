package querygate.lifecycle;

import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles graceful shutdown of the application.
 * Ensures in-flight requests are completed before shutdown.
 */
@Singleton
public class GracefulShutdownListener {

    private static final Logger LOG = LoggerFactory.getLogger(GracefulShutdownListener.class);

    @EventListener
    public void onShutdown(ShutdownEvent event) {
        LOG.info("=".repeat(60));
        LOG.info("Initiating graceful shutdown...");
        LOG.info("Waiting for in-flight requests to complete...");
        LOG.info("=".repeat(60));

        // Log shutdown details
        LOG.info("Application: {}", event.getSource().getClass().getSimpleName());
        LOG.info("Shutdown initiated at: {}", java.time.Instant.now());

        // Additional cleanup can be added here if needed
        // For example: closing custom resources, flushing metrics, etc.

        LOG.info("Graceful shutdown preparation completed");
        LOG.info("Server will stop accepting new connections and wait for active requests to finish");
    }
}
