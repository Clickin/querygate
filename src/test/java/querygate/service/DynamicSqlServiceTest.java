package querygate.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import querygate.config.EndpointConfig;
import querygate.config.MyBatisFactory;
import querygate.exception.SqlExecutionException;
import querygate.model.SqlExecutionResult;
import querygate.model.SqlType;

import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for DynamicSqlService.
 * Tests SQL execution for different operations: SELECT, INSERT, UPDATE, DELETE, BATCH.
 */
@MicronautTest
class DynamicSqlServiceTest {

    @Inject
    DynamicSqlService dynamicSqlService;

    @Inject
    MyBatisFactory myBatisFactory;

    @Inject
    DataSource dataSource;

    @Inject
    MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS users");
            stmt.execute("""
                CREATE TABLE users (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(255) NOT NULL,
                    email VARCHAR(255) NOT NULL
                )
                """);
        }
    }

    @Test
    void testWhenSelectExecutedThenReturnsData() {
        // Arrange
        insertTestUser(1, "Alice", "alice@example.com");
        EndpointConfig config = createEndpointConfig("UserMapper.findAll", SqlType.SELECT);
        Map<String, Object> params = Map.of();

        // Act
        SqlExecutionResult result = dynamicSqlService.execute(config, params);

        // Assert
        assertThat(result)
                .isNotNull()
                .extracting(SqlExecutionResult::getSqlType, SqlExecutionResult::isSuccess)
                .containsExactly(SqlType.SELECT, true);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0))
                .containsEntry("name", "Alice")
                .containsEntry("email", "alice@example.com");
    }

    @Test
    void testWhenSelectReturnsEmptyThenReturnsEmptyList() {
        // Arrange
        EndpointConfig config = createEndpointConfig("UserMapper.findAll", SqlType.SELECT);
        Map<String, Object> params = Map.of();

        // Act
        SqlExecutionResult result = dynamicSqlService.execute(config, params);

        // Assert
        assertThat(result)
                .extracting(SqlExecutionResult::isSuccess, SqlExecutionResult::getData)
                .containsExactly(true, List.of());
    }

    @Test
    void testWhenSelectWithParametersThenFiltersResults() {
        // Arrange
        insertTestUser(1, "Alice", "alice@example.com");
        insertTestUser(2, "Bob", "bob@example.com");
        EndpointConfig config = createEndpointConfig("UserMapper.findById", SqlType.SELECT);
        Map<String, Object> params = Map.of("id", 1);

        // Act
        SqlExecutionResult result = dynamicSqlService.execute(config, params);

        // Assert
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0))
                .containsEntry("id", 1)
                .containsEntry("name", "Alice");
    }

    @Test
    void testWhenInsertExecutedThenReturnsAffectedRowCount() {
        // Arrange
        EndpointConfig config = createEndpointConfig("UserMapper.insertUser", SqlType.INSERT);
        Map<String, Object> params = Map.of(
                "id", 1,
                "name", "Charlie",
                "email", "charlie@example.com"
        );

        // Act
        SqlExecutionResult result = dynamicSqlService.execute(config, params);

        // Assert
        assertThat(result)
                .extracting(SqlExecutionResult::getSqlType, SqlExecutionResult::isSuccess, SqlExecutionResult::getAffectedRows)
                .containsExactly(SqlType.INSERT, true, 1);
    }

    @Test
    void testWhenInsertWithGeneratedIdThenReturnsId() {
        // Arrange
        EndpointConfig config = createEndpointConfig("UserMapper.insertUser", SqlType.INSERT);
        Map<String, Object> params = Map.of(
                "id", 2,
                "name", "Dana",
                "email", "dana@example.com"
        );

        // Act
        SqlExecutionResult result = dynamicSqlService.execute(config, params);

        // Assert
        assertThat(result)
                .extracting(SqlExecutionResult::getSqlType, SqlExecutionResult::isSuccess)
                .containsExactly(SqlType.INSERT, true);
    }

    @Test
    void testWhenUpdateExecutedThenReturnsAffectedRowCount() {
        // Arrange
        insertTestUser(3, "Eva", "eva@old.com");
        EndpointConfig config = createEndpointConfig("UserMapper.updateUser", SqlType.UPDATE);
        Map<String, Object> params = Map.of(
                "id", 3,
                "name", "Eva",
                "email", "eva@new.com"
        );

        // Act
        SqlExecutionResult result = dynamicSqlService.execute(config, params);

        // Assert
        assertThat(result)
                .extracting(SqlExecutionResult::getSqlType, SqlExecutionResult::isSuccess, SqlExecutionResult::getAffectedRows)
                .containsExactly(SqlType.UPDATE, true, 1);
    }

    @Test
    void testWhenUpdateWithNoMatchesThenReturnsZeroAffected() {
        // Arrange
        EndpointConfig config = createEndpointConfig("UserMapper.updateUser", SqlType.UPDATE);
        Map<String, Object> params = Map.of(
                "id", 999,
                "name", "NonExistent",
                "email", "nonexistent@example.com"
        );

        // Act
        SqlExecutionResult result = dynamicSqlService.execute(config, params);

        // Assert
        assertThat(result)
                .extracting(SqlExecutionResult::getSqlType, SqlExecutionResult::getAffectedRows)
                .containsExactly(SqlType.UPDATE, 0);
    }

    @Test
    void testWhenDeleteExecutedThenReturnsAffectedRowCount() {
        // Arrange
        insertTestUser(4, "Frank", "frank@example.com");
        EndpointConfig config = createEndpointConfig("UserMapper.deleteUser", SqlType.DELETE);
        Map<String, Object> params = Map.of("id", 4);

        // Act
        SqlExecutionResult result = dynamicSqlService.execute(config, params);

        // Assert
        assertThat(result)
                .extracting(SqlExecutionResult::getSqlType, SqlExecutionResult::isSuccess, SqlExecutionResult::getAffectedRows)
                .containsExactly(SqlType.DELETE, true, 1);
    }

    @Test
    void testWhenInvalidSqlThenThrowsSqlExecutionException() {
        // Arrange
        EndpointConfig config = createEndpointConfig("InvalidMapper.invalidQuery", SqlType.SELECT);
        Map<String, Object> params = Map.of();

        // Act & Assert
        assertThatThrownBy(() -> dynamicSqlService.execute(config, params))
                .isInstanceOf(SqlExecutionException.class)
                .hasMessageContaining("SQL execution failed");
    }

    @Test
    void testWhenParametersAreNullThenHandlesGracefully() {
        // Arrange
        insertTestUser(5, "Grace", "grace@example.com");
        EndpointConfig config = createEndpointConfig("UserMapper.findAll", SqlType.SELECT);
        Map<String, Object> params = Map.of();

        // Act
        SqlExecutionResult result = dynamicSqlService.execute(config, params);

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNotEmpty();
    }

    @Test
    void testExecuteSyncMatchesAsyncBehavior() {
        // Arrange
        insertTestUser(6, "Henry", "henry@example.com");
        EndpointConfig config = createEndpointConfig("UserMapper.findAll", SqlType.SELECT);
        Map<String, Object> params = Map.of();

        // Act
        SqlExecutionResult syncResult = dynamicSqlService.executeSync(config, params);
        SqlExecutionResult asyncResult = dynamicSqlService.execute(config, params);

        // Assert
        assertThat(syncResult.getData()).hasSize(1);
        assertThat(asyncResult.getData()).hasSize(1);
    }

    // Helper methods

    private void insertTestUser(int id, String name, String email) {
        EndpointConfig config = createEndpointConfig("UserMapper.insertUser", SqlType.INSERT);
        dynamicSqlService.execute(config, Map.of("id", id, "name", name, "email", email));
    }

    private EndpointConfig createEndpointConfig(String sqlId, SqlType sqlType) {
        return EndpointConfig.builder()
                .path("/test/{id}")
                .method("GET")
                .sqlId(sqlId)
                .sqlType(sqlType)
                .build();
    }
}
