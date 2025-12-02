package querygate;

import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Smoke tests for H2 database connectivity and basic operations.
 * Verifies that the application can connect to H2 and perform basic SQL operations.
 */
@MicronautTest
class H2SmokeTest {

    @Inject
    EmbeddedApplication<?> application;

    @Inject
    DataSource dataSource;

    @Test
    void testApplicationStartsSuccessfully() {
        Assertions.assertTrue(application.isRunning(), "Application should be running");
    }

    @Test
    void testH2DatabaseConnectivity() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            Assertions.assertNotNull(conn, "Connection should not be null");
            Assertions.assertTrue(conn.isValid(2), "Connection should be valid");
        }
    }

    @Test
    void testH2DatabaseVersion() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String dbName = conn.getMetaData().getDatabaseProductName();
            String dbVersion = conn.getMetaData().getDatabaseProductVersion();

            Assertions.assertEquals("H2", dbName, "Database should be H2");
            Assertions.assertNotNull(dbVersion, "Database version should be available");
        }
    }

    @Test
    void testCreateAndQueryTable() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Create test table
            stmt.execute("CREATE TABLE smoke_test_table (id INT PRIMARY KEY, name VARCHAR(255))");

            // Insert test data
            int insertResult = stmt.executeUpdate("INSERT INTO smoke_test_table VALUES (1, 'Smoke Test')");
            Assertions.assertEquals(1, insertResult, "Should insert one row");

            // Query test data
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM smoke_test_table WHERE id = 1")) {
                Assertions.assertTrue(rs.next(), "Should find inserted row");
                Assertions.assertEquals(1, rs.getInt("id"), "ID should match");
                Assertions.assertEquals("Smoke Test", rs.getString("name"), "Name should match");
            }

            // Clean up
            stmt.execute("DROP TABLE smoke_test_table");
        }
    }

    @Test
    void testMultipleConnections() throws SQLException {
        // Verify we can get multiple connections (HikariCP pool is working)
        try (Connection conn1 = dataSource.getConnection();
             Connection conn2 = dataSource.getConnection();
             Connection conn3 = dataSource.getConnection()) {

            Assertions.assertNotNull(conn1, "First connection should not be null");
            Assertions.assertNotNull(conn2, "Second connection should not be null");
            Assertions.assertNotNull(conn3, "Third connection should not be null");
            Assertions.assertTrue(conn1.isValid(2), "First connection should be valid");
            Assertions.assertTrue(conn2.isValid(2), "Second connection should be valid");
            Assertions.assertTrue(conn3.isValid(2), "Third connection should be valid");
        }
    }

    @Test
    void testTransactionRollback() throws SQLException {
        // First create the table
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE rollback_test (id INT PRIMARY KEY)");
        }

        // Now test rollback of data changes
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                // Insert data
                stmt.executeUpdate("INSERT INTO rollback_test VALUES (1)");

                // Rollback the insertion
                conn.rollback();
            }

            // Verify data was not inserted after rollback
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM rollback_test")) {
                Assertions.assertTrue(rs.next(), "Should have result");
                Assertions.assertEquals(0, rs.getInt("cnt"), "Should have 0 rows after rollback");
            }
        }

        // Clean up
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE rollback_test");
        }
    }

    @Test
    void testDataPersistenceWithinSession() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Create table
            stmt.execute("CREATE TABLE persistence_test (id INT PRIMARY KEY, data VARCHAR(100))");

            // Insert data
            stmt.executeUpdate("INSERT INTO persistence_test VALUES (1, 'test1')");
            stmt.executeUpdate("INSERT INTO persistence_test VALUES (2, 'test2')");

            // Query and verify all data is persisted
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM persistence_test")) {
                Assertions.assertTrue(rs.next(), "Should have result");
                Assertions.assertEquals(2, rs.getInt("cnt"), "Should have 2 rows");
            }

            // Clean up
            stmt.execute("DROP TABLE persistence_test");
        }
    }

    @Test
    void testH2InMemoryDatabase() throws SQLException {
        // Verify we're using in-memory database by checking connection URL
        try (Connection conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            Assertions.assertTrue(url.contains("mem:"),
                "Should be using in-memory H2 database (jdbc:h2:mem:)");
        }
    }

    @Test
    void testBasicAggregates() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Create and populate test table
            stmt.execute("CREATE TABLE aggregate_test (id INT, amount INT)");
            stmt.executeUpdate("INSERT INTO aggregate_test VALUES (1, 10)");
            stmt.executeUpdate("INSERT INTO aggregate_test VALUES (2, 20)");
            stmt.executeUpdate("INSERT INTO aggregate_test VALUES (3, 30)");

            // Test SUM aggregate
            try (ResultSet rs = stmt.executeQuery("SELECT SUM(amount) as total FROM aggregate_test")) {
                Assertions.assertTrue(rs.next(), "Should have result");
                Assertions.assertEquals(60, rs.getInt("total"), "Sum should be 60");
            }

            // Test COUNT aggregate
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM aggregate_test")) {
                Assertions.assertTrue(rs.next(), "Should have result");
                Assertions.assertEquals(3, rs.getInt("cnt"), "Count should be 3");
            }

            // Clean up
            stmt.execute("DROP TABLE aggregate_test");
        }
    }

    @Test
    void testPreparedStatements() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("CREATE TABLE prepared_stmt_test (id INT PRIMARY KEY, name VARCHAR(100))");

            // Test prepared statement for INSERT
            try (var pstmt = conn.prepareStatement("INSERT INTO prepared_stmt_test VALUES (?, ?)")) {
                pstmt.setInt(1, 1);
                pstmt.setString(2, "Test Value");
                int result = pstmt.executeUpdate();
                Assertions.assertEquals(1, result, "Should insert one row");
            }

            // Test prepared statement for SELECT
            try (var pstmt = conn.prepareStatement("SELECT * FROM prepared_stmt_test WHERE id = ?")) {
                pstmt.setInt(1, 1);
                try (ResultSet rs = pstmt.executeQuery()) {
                    Assertions.assertTrue(rs.next(), "Should find inserted row");
                    Assertions.assertEquals("Test Value", rs.getString("name"), "Name should match");
                }
            }

            // Clean up
            conn.createStatement().execute("DROP TABLE prepared_stmt_test");
        }
    }
}
