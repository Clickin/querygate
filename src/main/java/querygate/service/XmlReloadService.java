package querygate.service;

import querygate.config.EndpointConfigLoader;
import querygate.config.GatewayProperties;
import querygate.config.MyBatisFactory;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Async;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service that watches external configuration files and triggers reloads.
 * Monitors:
 * - Mapper XML files in the mapper directory
 * - Endpoint configuration YAML file
 *
 * Uses Java WatchService for file system monitoring.
 */
@Singleton
@Context
@Requires(property = "gateway.hot-reload.enabled", value = "true", defaultValue = "true")
public class XmlReloadService {

    private static final Logger LOG = LoggerFactory.getLogger(XmlReloadService.class);

    private final GatewayProperties properties;
    private final MyBatisFactory myBatisFactory;
    private final EndpointConfigLoader endpointConfigLoader;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private WatchService watchService;
    private Thread watchThread;

    public XmlReloadService(
            GatewayProperties properties,
            MyBatisFactory myBatisFactory,
            EndpointConfigLoader endpointConfigLoader) {
        this.properties = properties;
        this.myBatisFactory = myBatisFactory;
        this.endpointConfigLoader = endpointConfigLoader;
    }

    /**
     * Starts the file watcher on application startup.
     */
    @EventListener
    @Async
    public void onStartup(StartupEvent event) {
        startWatching();
    }

    /**
     * Starts watching configuration directories for changes.
     */
    public void startWatching() {
        if (running.compareAndSet(false, true)) {
            try {
                watchService = FileSystems.getDefault().newWatchService();
                registerWatchPaths();

                // Start watch thread using virtual thread
                watchThread = Thread.ofVirtual()
                        .name("xml-reload-watcher")
                        .start(this::watchLoop);

                LOG.info("Hot reload watcher started");

            } catch (IOException e) {
                LOG.error("Failed to start file watcher", e);
                running.set(false);
            }
        }
    }

    /**
     * Registers paths to watch for file changes.
     */
    private void registerWatchPaths() throws IOException {
        // Watch mapper directory
        Path mapperDir = Paths.get(properties.mybatis().mapperLocations());
        if (Files.exists(mapperDir) && Files.isDirectory(mapperDir)) {
            mapperDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            LOG.info("Watching mapper directory: {}", mapperDir.toAbsolutePath());
        } else {
            LOG.warn("Mapper directory does not exist: {}", mapperDir.toAbsolutePath());
        }

        // Watch endpoint config file's parent directory
        Path endpointConfigPath = Paths.get(properties.endpointConfigPath());
        Path configDir = endpointConfigPath.getParent();
        if (configDir != null && Files.exists(configDir)) {
            configDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            LOG.info("Watching config directory: {}", configDir.toAbsolutePath());
        }
    }

    /**
     * Main watch loop that processes file system events.
     */
    private void watchLoop() {
        LOG.info("File watch loop started");
        Path mapperDir = Paths.get(properties.mybatis().mapperLocations());
        Path endpointConfigPath = Paths.get(properties.endpointConfigPath());
        String endpointConfigFileName = endpointConfigPath.getFileName().toString();

        while (running.get()) {
            try {
                // Wait for events with timeout to allow checking running flag
                WatchKey key = watchService.poll(
                        properties.hotReload().pollIntervalMs(),
                        java.util.concurrent.TimeUnit.MILLISECONDS);

                if (key == null) {
                    continue;
                }

                // Debounce: small delay to avoid multiple reloads for rapid changes
                Thread.sleep(100);

                boolean mapperChanged = false;
                boolean configChanged = false;

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        LOG.warn("Watch event overflow");
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path fileName = ev.context();

                    LOG.debug("File change detected: {} - {}", kind.name(), fileName);

                    // Check if it's a mapper XML file
                    if (fileName.toString().endsWith(".xml")) {
                        mapperChanged = true;
                        LOG.info("Mapper file changed: {}", fileName);
                    }

                    // Check if it's the endpoint config file
                    if (fileName.toString().equals(endpointConfigFileName)) {
                        configChanged = true;
                        LOG.info("Endpoint config file changed: {}", fileName);
                    }
                }

                // Perform reloads
                if (mapperChanged) {
                    reloadMappers();
                }

                if (configChanged) {
                    reloadEndpointConfig();
                }

                // Reset the key
                boolean valid = key.reset();
                if (!valid) {
                    LOG.warn("Watch key no longer valid");
                    // Try to re-register
                    try {
                        registerWatchPaths();
                    } catch (IOException e) {
                        LOG.error("Failed to re-register watch paths", e);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.debug("Watch loop interrupted");
                break;
            } catch (Exception e) {
                LOG.error("Error in watch loop", e);
            }
        }

        LOG.info("File watch loop stopped");
    }

    /**
     * Reloads MyBatis mappers.
     */
    public void reloadMappers() {
        LOG.info("Reloading MyBatis mappers...");
        try {
            myBatisFactory.reload();
            LOG.info("MyBatis mappers reloaded successfully");
        } catch (Exception e) {
            LOG.error("Failed to reload MyBatis mappers", e);
        }
    }

    /**
     * Reloads endpoint configuration.
     */
    public void reloadEndpointConfig() {
        LOG.info("Reloading endpoint configuration...");
        try {
            endpointConfigLoader.reloadConfiguration();
            LOG.info("Endpoint configuration reloaded successfully");
        } catch (Exception e) {
            LOG.error("Failed to reload endpoint configuration", e);
        }
    }

    /**
     * Manually trigger a full reload of all configurations.
     */
    public void reloadAll() {
        LOG.info("Triggering full configuration reload");
        reloadMappers();
        reloadEndpointConfig();
    }

    /**
     * Checks if the watcher is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Stops the file watcher on application shutdown.
     */
    @PreDestroy
    public void stopWatching() {
        LOG.info("Stopping file watcher...");
        running.set(false);

        if (watchThread != null) {
            watchThread.interrupt();
            try {
                watchThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                LOG.warn("Error closing watch service", e);
            }
        }

        LOG.info("File watcher stopped");
    }
}
