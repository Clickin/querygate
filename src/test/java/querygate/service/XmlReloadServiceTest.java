package querygate.service;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for XmlReloadService.
 * Tests file watching and configuration reload functionality.
 */
@MicronautTest
class XmlReloadServiceTest {

    @Inject
    XmlReloadService xmlReloadService;

    @Test
    void testWhenServiceCreatedThenIsNotInitiallyRunning() {
        // Act
        boolean isRunning = xmlReloadService.isRunning();

        // Assert
        assertThat(isRunning).isFalse();
    }

    @Test
    void testWhenStartWatchingCalledThenStartsWatching() {
        // Act
        xmlReloadService.startWatching();

        try {
            boolean isRunning = xmlReloadService.isRunning();

            // Assert
            assertThat(isRunning).isTrue();
        } finally {
            // Cleanup
            xmlReloadService.stopWatching();
        }
    }

    @Test
    void testWhenStartWatchingCalledTwiceThenSecondCallIsIgnored() {
        // Act
        xmlReloadService.startWatching();

        try {
            xmlReloadService.startWatching();  // Second call
            boolean isRunning = xmlReloadService.isRunning();

            // Assert
            assertThat(isRunning).isTrue();
        } finally {
            xmlReloadService.stopWatching();
        }
    }

    @Test
    void testWhenStopWatchingCalledThenStopsWatching() {
        // Arrange
        xmlReloadService.startWatching();
        assertThat(xmlReloadService.isRunning()).isTrue();

        // Act
        xmlReloadService.stopWatching();

        // Assert
        assertThat(xmlReloadService.isRunning()).isFalse();
    }

    @Test
    void testWhenReloadAllCalledThenTriggersReload() {
        // Act & Assert - should not throw exception
        assertThatCode(() -> xmlReloadService.reloadAll())
                .doesNotThrowAnyException();
    }

    @Test
    void testWhenReloadMappersCalledThenTriggersMapperReload() {
        // Act & Assert - should not throw exception
        assertThatCode(() -> xmlReloadService.reloadMappers())
                .doesNotThrowAnyException();
    }

    @Test
    void testWhenReloadEndpointConfigCalledThenTriggersConfigReload() {
        // Act & Assert - should not throw exception
        assertThatCode(() -> xmlReloadService.reloadEndpointConfig())
                .doesNotThrowAnyException();
    }

    @Test
    void testWhenStoppingWatcherThenHandlesInterruptionGracefully() {
        // Arrange
        xmlReloadService.startWatching();

        // Act & Assert - should not throw exception
        assertThatCode(() -> xmlReloadService.stopWatching())
                .doesNotThrowAnyException();

        assertThat(xmlReloadService.isRunning()).isFalse();
    }
}
