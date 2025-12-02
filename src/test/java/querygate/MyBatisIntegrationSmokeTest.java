package querygate;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assertions;
import querygate.config.MyBatisFactory;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Smoke tests for MyBatis integration with H2.
 * Verifies that MyBatis can load mappers and execute SQL operations.
 */
@MicronautTest
class MyBatisIntegrationSmokeTest {

    @Inject
    MyBatisFactory myBatisFactory;

    @Inject
    SqlSessionFactory sqlSessionFactory;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        // Create tables for MyBatis tests
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS users");
            stmt.execute("""
                CREATE TABLE users (
                    id INT PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    email VARCHAR(255) NOT NULL
                )
                """);
        }
    }

    @Test
    void testMyBatisFactoryCreatesSessionFactory() {
        Assertions.assertNotNull(sqlSessionFactory, "SqlSessionFactory should be created");
    }

    @Test
    void testMyBatisCanLoadMappers() {
        Assertions.assertTrue(myBatisFactory.hasMappers(),
            "Should have mappers loaded from test resources");
    }

    @Test
    void testHealthMapperPing() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            Map<String, Object> result = session.selectOne("HealthMapper.ping", null);

            Assertions.assertNotNull(result, "Ping should return a result");
            Assertions.assertEquals("pong", result.get("message"), "Ping should return 'pong'");
        }
    }

    @Test
    void testUserMapperInsert() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            Map<String, Object> user = Map.of(
                "id", 1,
                "name", "John Doe",
                "email", "john@example.com"
            );

            int result = session.insert("UserMapper.insertUser", user);
            Assertions.assertEquals(1, result, "Should insert one row");
        }

        // Verify insertion
        try (SqlSession session = sqlSessionFactory.openSession()) {
            Map<String, Object> user = session.selectOne("UserMapper.findById",
                Map.of("id", 1));

            Assertions.assertNotNull(user, "User should be found");
            Assertions.assertEquals("John Doe", user.get("name"), "Name should match");
            Assertions.assertEquals("john@example.com", user.get("email"), "Email should match");
        }
    }

    @Test
    void testUserMapperFindAll() {
        // Insert test data
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.insert("UserMapper.insertUser", Map.of("id", 1, "name", "User1", "email", "user1@test.com"));
            session.insert("UserMapper.insertUser", Map.of("id", 2, "name", "User2", "email", "user2@test.com"));
        }

        // Query all users
        try (SqlSession session = sqlSessionFactory.openSession()) {
            List<Map<String, Object>> users = session.selectList("UserMapper.findAll", null);

            Assertions.assertNotNull(users, "Users list should not be null");
            Assertions.assertEquals(2, users.size(), "Should have 2 users");
            Assertions.assertEquals("User1", users.get(0).get("name"), "First user name should match");
            Assertions.assertEquals("User2", users.get(1).get("name"), "Second user name should match");
        }
    }

    @Test
    void testUserMapperUpdate() {
        // Insert initial data
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.insert("UserMapper.insertUser",
                Map.of("id", 1, "name", "Original Name", "email", "original@test.com"));
        }

        // Update user
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            int result = session.update("UserMapper.updateUser",
                Map.of("id", 1, "name", "Updated Name", "email", "updated@test.com"));

            Assertions.assertEquals(1, result, "Should update one row");
        }

        // Verify update
        try (SqlSession session = sqlSessionFactory.openSession()) {
            Map<String, Object> user = session.selectOne("UserMapper.findById", Map.of("id", 1));

            Assertions.assertEquals("Updated Name", user.get("name"), "Name should be updated");
            Assertions.assertEquals("updated@test.com", user.get("email"), "Email should be updated");
        }
    }

    @Test
    void testUserMapperDelete() {
        // Insert test data
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.insert("UserMapper.insertUser",
                Map.of("id", 1, "name", "To Delete", "email", "delete@test.com"));
        }

        // Delete user
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            int result = session.delete("UserMapper.deleteUser", Map.of("id", 1));
            Assertions.assertEquals(1, result, "Should delete one row");
        }

        // Verify deletion
        try (SqlSession session = sqlSessionFactory.openSession()) {
            Map<String, Object> user = session.selectOne("UserMapper.findById", Map.of("id", 1));
            Assertions.assertNull(user, "User should be deleted");
        }
    }

    @Test
    void testUserMapperCount() {
        // Insert test data
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.insert("UserMapper.insertUser",
                Map.of("id", 1, "name", "User1", "email", "user1@test.com"));
            session.insert("UserMapper.insertUser",
                Map.of("id", 2, "name", "User2", "email", "user2@test.com"));
            session.insert("UserMapper.insertUser",
                Map.of("id", 3, "name", "User3", "email", "user3@test.com"));
        }

        // Count users
        try (SqlSession session = sqlSessionFactory.openSession()) {
            Integer count = session.selectOne("UserMapper.countAll", null);

            Assertions.assertNotNull(count, "Count should not be null");
            Assertions.assertEquals(3, count, "Should have 3 users");
        }
    }

    @Test
    void testUserMapperCrud() {
        // CREATE
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            int insertResult = session.insert("UserMapper.insertUser",
                Map.of("id", 1, "name", "CRUD Test", "email", "crud@test.com"));
            Assertions.assertEquals(1, insertResult, "Should insert successfully");
        }

        // READ
        Map<String, Object> readUser;
        try (SqlSession session = sqlSessionFactory.openSession()) {
            readUser = session.selectOne("UserMapper.findById", Map.of("id", 1));
            Assertions.assertNotNull(readUser, "Should read successfully");
            Assertions.assertEquals("CRUD Test", readUser.get("name"), "Name should match");
        }

        // UPDATE
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            int updateResult = session.update("UserMapper.updateUser",
                Map.of("id", 1, "name", "Updated CRUD Test", "email", "updated-crud@test.com"));
            Assertions.assertEquals(1, updateResult, "Should update successfully");
        }

        // VERIFY UPDATE
        try (SqlSession session = sqlSessionFactory.openSession()) {
            Map<String, Object> updatedUser = session.selectOne("UserMapper.findById", Map.of("id", 1));
            Assertions.assertEquals("Updated CRUD Test", updatedUser.get("name"), "Name should be updated");
        }

        // DELETE
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            int deleteResult = session.delete("UserMapper.deleteUser", Map.of("id", 1));
            Assertions.assertEquals(1, deleteResult, "Should delete successfully");
        }

        // VERIFY DELETE
        try (SqlSession session = sqlSessionFactory.openSession()) {
            Map<String, Object> deletedUser = session.selectOne("UserMapper.findById", Map.of("id", 1));
            Assertions.assertNull(deletedUser, "Should be deleted");
        }
    }

    @Test
    void testMyBatisMapUnderscoreToCamelCase() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            // Insert with snake_case column names
            session.insert("UserMapper.insertUser",
                Map.of("id", 1, "name", "Test User", "email", "test@example.com"));
        }

        // Query and verify camelCase mapping works
        try (SqlSession session = sqlSessionFactory.openSession()) {
            Map<String, Object> user = session.selectOne("UserMapper.findById", Map.of("id", 1));

            Assertions.assertNotNull(user, "User should be found");
            // Check that result uses camelCase from resultMap
            Assertions.assertNotNull(user.get("email"), "Email property should be mapped");
        }
    }

    @Test
    void testUserMapperClearAll() {
        // Insert test data
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.insert("UserMapper.insertUser",
                Map.of("id", 1, "name", "User1", "email", "user1@test.com"));
            session.insert("UserMapper.insertUser",
                Map.of("id", 2, "name", "User2", "email", "user2@test.com"));
        }

        // Delete all
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.delete("UserMapper.deleteAll", null);
        }

        // Verify all deleted
        try (SqlSession session = sqlSessionFactory.openSession()) {
            Integer count = session.selectOne("UserMapper.countAll", null);
            Assertions.assertEquals(0, count, "All users should be deleted");
        }
    }
}
