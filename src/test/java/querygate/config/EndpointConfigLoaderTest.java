package querygate.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndpointConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void whenRequiredFieldsMissingThenSchemaValidationFails() throws Exception {
        Path configFile = tempDir.resolve("endpoint.yml");
        Files.writeString(configFile, """
                version: "1.0"
                endpoints:
                  - path: /api/test
                    sql-id: TestMapper.find
                    sql-type: SELECT
                """);

        GatewayProperties properties = new GatewayProperties(
                configFile.toString(),
                null,
                null,
                null,
                null,
                new GatewayProperties.SecurityConfig(true, "Authorization", List.of("test-key")),
                new GatewayProperties.ErrorHandlingConfig(false, false)
        );

        EndpointConfigLoader loader = new EndpointConfigLoader(properties);

        assertThatThrownBy(loader::loadConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema validation")
                .hasMessageContaining("method");
    }

    @Test
    void whenSqlTypeInvalidThenClearErrorIsThrown() throws Exception {
        Path configFile = tempDir.resolve("endpoint-invalid-sql.yml");
        Files.writeString(configFile, """
                version: "1.0"
                endpoints:
                  - path: /api/test
                    method: GET
                    sql-id: TestMapper.find
                    sql-type: INVALID
                """);

        GatewayProperties properties = new GatewayProperties(
                configFile.toString(),
                null,
                null,
                null,
                null,
                new GatewayProperties.SecurityConfig(true, "Authorization", List.of("test-key")),
                new GatewayProperties.ErrorHandlingConfig(false, false)
        );

        EndpointConfigLoader loader = new EndpointConfigLoader(properties);

        assertThatThrownBy(loader::loadConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid sql-type");
    }
}
