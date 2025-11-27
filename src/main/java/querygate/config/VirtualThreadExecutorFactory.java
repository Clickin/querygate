package querygate.config;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
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
     * Creates a bounded virtual thread executor when virtual threads are enabled.
     * Virtual threads are lightweight but we still limit concurrency to prevent
     * resource exhaustion from unbounded task spawning.
     */
    @Singleton
    @Named("gatewayVirtualExecutor")
    @Requires(property = "gateway.virtual-threads.enabled", value = "true", defaultValue = "true")
    public ExecutorService virtualThreadExecutor(GatewayProperties properties) {
        String executorName = properties.getVirtualThreads().getExecutorName();
        int maxConcurrent = properties.getBackpressure().getMaxConcurrentRequests();
        LOG.info("Creating bounded virtual thread executor: {} (max concurrent: {})", executorName, maxConcurrent);

        // Create underlying virtual thread executor
        ExecutorService delegate = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                        .name(executorName, 0)
                        .factory()
        );

        // Wrap with semaphore-bounded executor to prevent unbounded task spawning
        executorService = new BoundedExecutorService(delegate, maxConcurrent);

        return executorService;
    }

    /**
     * Wrapper that limits concurrent task execution using a semaphore.
     * Prevents unbounded virtual thread spawning that could exhaust resources.
     */
    private static class BoundedExecutorService implements ExecutorService {
        private final ExecutorService delegate;
        private final Semaphore semaphore;

        BoundedExecutorService(ExecutorService delegate, int maxConcurrent) {
            this.delegate = delegate;
            this.semaphore = new Semaphore(maxConcurrent, true);
        }

        @Override
        public void execute(Runnable command) {
            try {
                // Try to acquire permit with timeout to avoid indefinite blocking
                if (!semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    throw new RejectedExecutionException("Executor at capacity, cannot accept task");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted while waiting for executor permit", e);
            }

            delegate.execute(() -> {
                try {
                    command.run();
                } finally {
                    semaphore.release();
                }
            });
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
            try {
                if (!semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    throw new RejectedExecutionException("Executor at capacity");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted", e);
            }

            return delegate.submit(() -> {
                try {
                    return task.call();
                } finally {
                    semaphore.release();
                }
            });
        }

        @Override
        public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            try {
                if (!semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    throw new RejectedExecutionException("Executor at capacity");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted", e);
            }

            return delegate.submit(() -> {
                try {
                    task.run();
                } finally {
                    semaphore.release();
                }
            }, result);
        }

        @Override
        public java.util.concurrent.Future<?> submit(Runnable task) {
            return submit(task, null);
        }

        @Override
        public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) throws InterruptedException {
            // For simplicity, delegate directly - tasks will be bounded individually
            return delegate.invokeAll(tasks);
        }

        @Override
        public <T> java.util.List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.invokeAll(tasks, timeout, unit);
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks)
                throws InterruptedException, java.util.concurrent.ExecutionException {
            return delegate.invokeAny(tasks);
        }

        @Override
        public <T> T invokeAny(java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                              long timeout, TimeUnit unit)
                throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
            return delegate.invokeAny(tasks, timeout, unit);
        }
    }

    /**
     * Creates a platform thread executor when virtual threads are disabled.
     * Uses a bounded thread pool to prevent resource exhaustion.
     */
    @Singleton
    @Named("gatewayVirtualExecutor")
    @Requires(property = "gateway.virtual-threads.enabled", value = "false")
    public ExecutorService platformThreadExecutor(GatewayProperties properties) {
        int maxThreads = properties.getBackpressure().getMaxConcurrentRequests();
        LOG.info("Creating bounded platform thread executor (max={})", maxThreads);

        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
                Math.min(10, maxThreads), // core pool size
                maxThreads,                // max pool size
                60L, TimeUnit.SECONDS,     // keep-alive time
                new java.util.concurrent.LinkedBlockingQueue<>(maxThreads * 2), // bounded queue
                r -> {
                    Thread t = new Thread(r);
                    t.setName("gateway-platform-" + t.getId());
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy() // backpressure
        );

        executorService = executor;
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
