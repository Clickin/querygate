# QueryGate

A high-performance, configuration-driven API gateway that dynamically routes HTTP requests to MyBatis SQL operations. Built with Micronaut 4.6.1 and Java 25, featuring virtual threads for exceptional concurrency.

## Features

### Core Functionality
- **Dynamic Routing**: Configure API endpoints via external YAML without code changes
- **MyBatis Integration**: Direct SQL mapping with external XML mappers
- **Hot Reload**: Automatic detection and reload of configuration and mapper changes
- **Virtual Threads**: Leverages Java 25 virtual threads for high concurrency
- **Content Negotiation**: Automatic JSON/XML serialization based on Accept header

### Request Handling
- **Flexible Body Parsing**: Supports JSON, XML, and form-urlencoded content types
- **Array Parameters**: Native support for array/list parameters in SQL queries
- **Path Variables**: Extract parameters from URL paths (e.g., `/api/users/{id}`)
- **Query Parameters**: Standard query string parameter support
- **Optional Bodies**: POST/PUT/PATCH/DELETE endpoints support optional request bodies

### Response Customization
- **WRAPPED Format** (default): Returns data with metadata
  ```json
  {
    "success": true,
    "data": [...],
    "count": 10,
    "sqlType": "SELECT"
  }
  ```
- **RAW Format**: Returns data directly
  ```json
  [...]
  ```

### Security & Validation
- **API Key Authentication**: Configurable API key-based security
- **Parameter Validation**: Type conversion, range checks, pattern matching, and allowed values
- **Input Sanitization**: Automatic parameter validation and transformation

### Operations
- **CRUD Operations**: Full support for SELECT, INSERT, UPDATE, DELETE
- **Batch Operations**: Efficient batch inserts with configurable batch sizes
- **Transaction Management**: Automatic transaction handling

## Quick Start

### 🐳 Docker Quick Start (Recommended)

The fastest way to try QueryGate:

```bash
# 1. Clone the repository
git clone https://github.com/Clickin/querygate.git
cd querygate

# 2. Start with Docker Compose
docker-compose up -d

# 3. Wait for health check (about 30 seconds)
docker-compose ps

# 4. Test the API
curl http://localhost:8080/health
```

The server will be available at `http://localhost:8080` with:
- Security disabled for easy testing
- H2 in-memory database pre-configured
- Sample endpoints from `config/endpoint-config.yml`

**Stop the service:**
```bash
docker-compose down
```

### 📦 Local Development Setup

### Prerequisites
- Java 25 (using SDKMAN recommended)
- Gradle 9.2.1 (wrapper included)

### Installation

1. Clone the repository:
```bash
git clone https://github.com/Clickin/querygate.git
cd querygate
```

2. Set up Java 25:
```bash
sdk use java 25.0.1-tem
```

3. Build the project:
```bash
./gradlew build
```

4. Run the application:
```bash
./gradlew run
```

The server will start on `http://localhost:8080`

## Configuration

### Application Configuration
Edit `src/main/resources/application.yml`:

```yaml
micronaut:
  server:
    port: 8080
    virtual-threads:
      enabled: true

gateway:
  endpoint-config-path: ./config/endpoint-config.yml
  mybatis:
    mapper-locations: ./config/mappers
    cache-enabled: true
    lazy-loading-enabled: false
    default-statement-timeout: 30
  security:
    enabled: true
    api-key: your-secret-api-key-here
```

### Endpoint Configuration

**IDE Support with JSON Schema:**

The project includes a JSON Schema (`src/main/resources/schemas/endpoint-config.schema.json`) for IDE autocomplete and validation. Your IDE will automatically provide suggestions and validation if you add this comment at the top of your YAML file:

```yaml
# yaml-language-server: $schema=https://raw.githubusercontent.com/Clickin/querygate/main/src/main/resources/schemas/endpoint-config.schema.json
```

For local development, you can also use a relative path:
```yaml
# yaml-language-server: $schema=../src/main/resources/schemas/endpoint-config.schema.json
```

**Configuration File:**

Create/edit `config/endpoint-config.yml`:

```yaml
version: "1.0"

endpoints:
  # Simple SELECT endpoint
  - path: /api/users
    method: GET
    sql-id: UserMapper.selectAllUsers
    sql-type: SELECT
    description: "Retrieve all users"
    response-format: wrapped  # or 'raw'
    validation:
      required: []
      optional:
        - name: limit
          type: integer
          min: 1
          max: 100
          default: 20

  # SELECT with path variable
  - path: /api/users/{id}
    method: GET
    sql-id: UserMapper.selectUserById
    sql-type: SELECT
    description: "Get user by ID"
    validation:
      required:
        - name: id
          type: long
          source: path

  # POST with array parameter
  - path: /api/users/search
    method: POST
    sql-id: UserMapper.searchByIds
    sql-type: SELECT
    description: "Search users by multiple IDs"
    response-format: raw
    validation:
      required:
        - name: userIds
          type: array
          min-items: 1
          max-items: 100

  # INSERT endpoint
  - path: /api/users
    method: POST
    sql-id: UserMapper.insertUser
    sql-type: INSERT
    validation:
      required:
        - name: username
          type: string
          min-length: 3
          max-length: 50
          pattern: "^[a-zA-Z0-9_]+$"
        - name: email
          type: string
          pattern: "^[\\w\\-\\.]+@[\\w\\-]+\\.[a-z]{2,}$"

  # Batch INSERT
  - path: /api/users/batch
    method: POST
    sql-id: UserMapper.batchInsertUsers
    sql-type: BATCH
    batch-config:
      item-key: users
      batch-size: 100
    validation:
      required:
        - name: users
          type: array
          min-items: 1
          max-items: 1000
```

### MyBatis Mapper
Create mapper XML in `config/mappers/`:

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">

<mapper namespace="UserMapper">
    <!-- Simple SELECT -->
    <select id="selectAllUsers" resultType="map">
        SELECT id, username, email, status
        FROM users
        WHERE 1=1
        <if test="status != null">
            AND status = #{status}
        </if>
        LIMIT #{limit} OFFSET #{offset}
    </select>

    <!-- SELECT with array parameter -->
    <select id="searchByIds" resultType="map">
        SELECT id, username, email
        FROM users
        WHERE id IN
        <foreach item="userId" collection="userIds" open="(" separator="," close=")">
            #{userId}
        </foreach>
    </select>

    <!-- INSERT with generated key -->
    <insert id="insertUser" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO users (username, email, status)
        VALUES (#{username}, #{email}, #{status})
    </insert>

    <!-- Batch INSERT -->
    <insert id="batchInsertUsers">
        INSERT INTO users (username, email) VALUES
        <foreach collection="users" item="user" separator=",">
            (#{user.username}, #{user.email})
        </foreach>
    </insert>
</mapper>
```

## API Usage Examples

### Simple GET Request
```bash
curl -X GET "http://localhost:8080/api/users?limit=10&offset=0" \
  -H "X-API-Key: your-secret-api-key"
```

### GET with Path Variable
```bash
curl -X GET "http://localhost:8080/api/users/123" \
  -H "X-API-Key: your-secret-api-key"
```

### POST with Array Parameter
```bash
curl -X POST "http://localhost:8080/api/users/search" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-secret-api-key" \
  -d '{"userIds": [1, 2, 3, 4, 5]}'
```

### POST Insert
```bash
curl -X POST "http://localhost:8080/api/users" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-secret-api-key" \
  -d '{
    "username": "johndoe",
    "email": "john@example.com",
    "status": "ACTIVE"
  }'
```

### Batch Insert
```bash
curl -X POST "http://localhost:8080/api/users/batch" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-secret-api-key" \
  -d '{
    "users": [
      {"username": "user1", "email": "user1@example.com"},
      {"username": "user2", "email": "user2@example.com"}
    ]
  }'
```

### XML Request/Response
```bash
curl -X POST "http://localhost:8080/api/users" \
  -H "Content-Type: application/xml" \
  -H "Accept: application/xml" \
  -H "X-API-Key: your-secret-api-key" \
  -d '<user><username>johndoe</username><email>john@example.com</email></user>'
```

## Response Formats

### WRAPPED Format (Default)
Includes metadata about the operation:

**SELECT Response:**
```json
{
  "success": true,
  "data": [
    {"id": 1, "username": "user1", "email": "user1@example.com"},
    {"id": 2, "username": "user2", "email": "user2@example.com"}
  ],
  "count": 2,
  "sqlType": "SELECT"
}
```

**INSERT Response:**
```json
{
  "success": true,
  "affectedRows": 1,
  "generatedId": 123,
  "sqlType": "INSERT"
}
```

**UPDATE/DELETE Response:**
```json
{
  "success": true,
  "affectedRows": 1,
  "sqlType": "UPDATE"
}
```

### RAW Format
Returns data directly without wrapper:

**SELECT Response:**
```json
[
  {"id": 1, "username": "user1", "email": "user1@example.com"},
  {"id": 2, "username": "user2", "email": "user2@example.com"}
]
```

**INSERT Response:**
```json
{
  "affectedRows": 1,
  "generatedId": 123
}
```

## Validation

### Supported Types
- `string`: Text validation with min/max length and regex patterns
- `integer` / `int`: Integer numbers with range validation
- `long`: Long integers
- `double` / `float` / `number`: Decimal numbers
- `boolean` / `bool`: Boolean values (true/false, 1/0, yes/no)
- `date`: Date validation with custom format
- `datetime`: DateTime validation
- `array` / `list`: Array validation with min/max items

### Validation Rules
```yaml
validation:
  required:
    - name: username
      type: string
      min-length: 3
      max-length: 50
      pattern: "^[a-zA-Z0-9_]+$"

    - name: age
      type: integer
      min: 18
      max: 120

    - name: tags
      type: array
      min-items: 1
      max-items: 10

    - name: status
      type: string
      allowed-values: [ACTIVE, INACTIVE, PENDING]

  optional:
    - name: limit
      type: integer
      default: 20
```

## Development

### Project Structure
```
querygate/
├── config/
│   ├── endpoint-config.yml    # API endpoint definitions
│   └── mappers/               # MyBatis XML mappers
├── src/main/
│   ├── java/querygate/
│   │   ├── controller/        # HTTP controllers
│   │   ├── service/           # Business logic
│   │   ├── config/            # Configuration classes
│   │   ├── validation/        # Parameter validation
│   │   ├── security/          # Authentication
│   │   └── exception/         # Error handling
│   └── resources/
│       └── application.yml    # Application config
└── build.gradle
```

### Building

**Development build:**
```bash
./gradlew build
```

**Run tests:**
```bash
./gradlew test
```

**Create optimized JAR:**
```bash
./gradlew optimizedJitJar
```

**Run in development:**
```bash
./gradlew run
```

### Hot Reload
The application automatically watches for changes:
- `config/endpoint-config.yml` - Endpoint configuration
- `config/mappers/*.xml` - MyBatis mapper files

Changes are detected and reloaded automatically without restart.

## Performance

- **Virtual Threads**: Uses Java 25 virtual threads for high concurrency
- **Connection Pooling**: HikariCP for efficient database connections
- **Async Execution**: Non-blocking request processing
- **Batch Operations**: Efficient bulk inserts
- **MyBatis Caching**: Optional statement-level caching

## Error Handling

The gateway provides structured error responses with configurable detail levels.

### Error Response Configuration

Control error detail exposure via `application.yml`:

```yaml
gateway:
  error-handling:
    # Expose detailed error messages (default: true in dev, false in prod)
    expose-details: true
    # Include stack traces in responses (default: false)
    expose-stack-trace: false
```

**Security Note:** In production, set `expose-details: false` to prevent exposing:
- Internal SQL query details
- MyBatis mapper names and SQL IDs
- Detailed validation error messages
- System implementation details

### Error Response Examples

**Development Mode (expose-details: true):**
```json
{
  "success": false,
  "error": "Request Body Parse Error",
  "message": "Invalid JSON format: Unexpected token at position 5",
  "contentType": "application/json",
  "path": "/api/users",
  "method": "POST"
}
```

**Production Mode (expose-details: false):**
```json
{
  "success": false,
  "error": "Request Body Parse Error",
  "message": "Invalid request format",
  "path": "/api/users",
  "method": "POST"
}
```

**Validation Error (with details):**
```json
{
  "success": false,
  "error": "Validation Error",
  "message": "Validation failed",
  "details": [
    "Required parameter 'username' is missing",
    "Parameter 'age' must be at least 18"
  ],
  "path": "/api/users",
  "method": "POST"
}
```

**Database Error (production mode):**
```json
{
  "success": false,
  "error": "Database Error",
  "message": "A database error occurred",
  "path": "/api/users",
  "method": "GET"
}
```

All errors are always logged server-side with full details for debugging.

### RAW Response Format Error Handling

**Important:** For endpoints configured with `response-format: raw`, error handling works differently to prevent client-side DTO mapping issues.

**Problem:**
```yaml
# Endpoint configuration
- path: /api/users
  method: GET
  sql-type: SELECT
  response-format: raw  # Client expects: List<User>
```

If errors returned JSON like `{"success": false, "error": "..."}`, client DTO mapping would fail since it expects `List<User>`.

**Solution:**

For RAW format endpoints, errors are returned via **HTTP headers** with an **empty response body**:

```bash
# Error response for RAW format endpoint
HTTP/1.1 400 Bad Request
X-Error-Type: ValidationError
X-Error-Message: Request validation failed
Content-Length: 0
```

**Error Headers:**
- `X-Error-Type`: Error category (ValidationError, ParseError, DatabaseError, NotFound, BadRequest, InternalError)
- `X-Error-Message`: Human-readable error message
- `X-Error-Details`: Additional error details (when expose-details: true)
- `X-Error-SqlId`: SQL statement ID (for database errors, when expose-details: true)
- `X-Error-ContentType`: Request content type (for parse errors, when expose-details: true)

**Client Implementation Example:**

```java
// Java client example
HttpResponse<List<User>> response = client.get("/api/users");

if (response.getStatusCode() >= 400) {
    String errorType = response.getHeaders().get("X-Error-Type");
    String errorMessage = response.getHeaders().get("X-Error-Message");
    throw new ApiException(errorType, errorMessage);
}

List<User> users = response.getBody(); // Safe - only reached on success
```

```typescript
// TypeScript client example
const response = await fetch('/api/users');

if (!response.ok) {
  const errorType = response.headers.get('X-Error-Type');
  const errorMessage = response.headers.get('X-Error-Message');
  throw new Error(`${errorType}: ${errorMessage}`);
}

const users: User[] = await response.json(); // Safe DTO mapping
```

**Key Points:**
- ✅ Always check HTTP status code first (200-299 = success)
- ✅ For errors (400+), read error info from headers
- ✅ Response body is empty on errors for RAW format
- ✅ Prevents DTO mapping exceptions on client side
- ✅ Works with all typed HTTP clients

**WRAPPED format** (default) continues to use structured JSON error responses as shown above.

## Security

### API Key Authentication
Enable in `application.yml`:
```yaml
gateway:
  security:
    enabled: true
    api-key: your-secret-api-key-here
```

Include in requests:
```bash
-H "X-API-Key: your-secret-api-key-here"
```

### SQL Injection Prevention
- All parameters are bound using MyBatis prepared statements
- Input validation and type conversion before SQL execution
- No dynamic SQL concatenation

## Monitoring

### Health Endpoint
```bash
curl http://localhost:8080/health
```

### Metrics
Built-in Micrometer metrics for:
- SQL execution time
- Request throughput
- Error rates
- Database connection pool stats

## Technology Stack

- **Framework**: Micronaut 4.6.1
- **Language**: Java 25
- **Build Tool**: Gradle 9.2.1
- **SQL Mapping**: MyBatis 3.5.19
- **Connection Pool**: HikariCP
- **Serialization**: Micronaut Serde
- **Virtual Threads**: Java 25 Project Loom

## License

[Your License Here]

## Contributing

[Your Contributing Guidelines Here]

## References

- [Micronaut Documentation](https://docs.micronaut.io/4.6.1/guide/index.html)
- [MyBatis Documentation](https://mybatis.org/mybatis-3/)
- [Java Virtual Threads](https://openjdk.org/jeps/444)
