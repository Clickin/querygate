package generic.db.gateway.controller;

import generic.db.gateway.config.EndpointConfig;
import generic.db.gateway.config.EndpointConfigLoader;
import generic.db.gateway.exception.EndpointNotFoundException;
import generic.db.gateway.model.SqlExecutionResult;
import generic.db.gateway.service.DynamicSqlService;
import generic.db.gateway.service.InputMergerService;
import generic.db.gateway.service.ResponseSerializer;
import generic.db.gateway.validation.ValidationModule;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Dynamic routing controller that handles all API endpoints.
 * Routes requests to appropriate SQL operations based on external configuration.
 *
 * All paths under /api are handled dynamically based on endpoint-config.yml.
 */
@Controller("/api")
public class DynamicRoutingController {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicRoutingController.class);

    private final EndpointConfigLoader endpointConfigLoader;
    private final DynamicSqlService dynamicSqlService;
    private final InputMergerService inputMergerService;
    private final ValidationModule validationModule;
    private final ResponseSerializer responseSerializer;

    public DynamicRoutingController(
            EndpointConfigLoader endpointConfigLoader,
            DynamicSqlService dynamicSqlService,
            InputMergerService inputMergerService,
            ValidationModule validationModule,
            ResponseSerializer responseSerializer) {
        this.endpointConfigLoader = endpointConfigLoader;
        this.dynamicSqlService = dynamicSqlService;
        this.inputMergerService = inputMergerService;
        this.validationModule = validationModule;
        this.responseSerializer = responseSerializer;
    }

    /**
     * Handles GET requests for all paths under /api.
     */
    @Get("/{+path}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public CompletableFuture<HttpResponse<Map<String, Object>>> handleGet(
            HttpRequest<?> request,
            @PathVariable String path) {
        return handleRequest(request, "GET", "/api/" + path, null);
    }

    /**
     * Handles POST requests for all paths under /api.
     */
    @Post("/{+path}")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML,
            MediaType.TEXT_XML, MediaType.APPLICATION_FORM_URLENCODED})
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public CompletableFuture<HttpResponse<Map<String, Object>>> handlePost(
            HttpRequest<?> request,
            @PathVariable String path,
            @Body(value = "") String body) {
        return handleRequest(request, "POST", "/api/" + path, body);
    }

    /**
     * Handles PUT requests for all paths under /api.
     */
    @Put("/{+path}")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public CompletableFuture<HttpResponse<Map<String, Object>>> handlePut(
            HttpRequest<?> request,
            @PathVariable String path,
            @Body(value = "") String body) {
        return handleRequest(request, "PUT", "/api/" + path, body);
    }

    /**
     * Handles DELETE requests for all paths under /api.
     */
    @Delete("/{+path}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public CompletableFuture<HttpResponse<Map<String, Object>>> handleDelete(
            HttpRequest<?> request,
            @PathVariable String path,
            @Body(value = "") String body) {
        return handleRequest(request, "DELETE", "/api/" + path, body);
    }

    /**
     * Handles PATCH requests for all paths under /api.
     */
    @Patch("/{+path}")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public CompletableFuture<HttpResponse<Map<String, Object>>> handlePatch(
            HttpRequest<?> request,
            @PathVariable String path,
            @Body(value = "") String body) {
        return handleRequest(request, "PATCH", "/api/" + path, body);
    }

    /**
     * Common request handler for all HTTP methods.
     */
    private CompletableFuture<HttpResponse<Map<String, Object>>> handleRequest(
            HttpRequest<?> request,
            String method,
            String fullPath,
            String body) {

        LOG.debug("Handling {} request for path: {}", method, fullPath);

        // 1. Find matching endpoint configuration
        EndpointConfig endpointConfig = endpointConfigLoader.findMatchingEndpoint(method, fullPath);
        if (endpointConfig == null) {
            LOG.warn("No endpoint found for {} {}", method, fullPath);
            throw new EndpointNotFoundException(method, fullPath);
        }

        LOG.debug("Found endpoint config: {} -> {}", fullPath, endpointConfig.sqlId());

        // 2. Extract path variables
        Map<String, String> pathVariables =
                endpointConfigLoader.extractPathVariables(endpointConfig.path(), fullPath);

        // 3. Merge all inputs
        Map<String, Object> mergedParams = inputMergerService.mergeInputs(request, pathVariables, body);

        // 4. Validate and transform parameters
        Map<String, Object> validatedParams =
                validationModule.validateAndTransform(endpointConfig, mergedParams);

        // 5. Execute SQL asynchronously
        return dynamicSqlService.execute(endpointConfig, validatedParams)
                .thenApply(result -> buildResponse(request, endpointConfig, result));
    }

    /**
     * Builds the HTTP response based on SQL result and content negotiation.
     */
    private HttpResponse<Map<String, Object>> buildResponse(
            HttpRequest<?> request,
            EndpointConfig config,
            SqlExecutionResult result) {

        HttpStatus status = determineStatus(config, result);
        MediaType mediaType = responseSerializer.getPreferredMediaType(request);

        return HttpResponse
                .status(status)
                .contentType(mediaType)
                .body(result.toMap());
    }

    /**
     * Determines the appropriate HTTP status based on the operation and result.
     */
    private HttpStatus determineStatus(EndpointConfig config, SqlExecutionResult result) {
        if (!result.isSuccess()) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return switch (config.sqlType()) {
            case INSERT -> HttpStatus.CREATED;
            case DELETE -> result.getAffectedRows() > 0 ? HttpStatus.OK : HttpStatus.NOT_FOUND;
            case UPDATE -> result.getAffectedRows() > 0 ? HttpStatus.OK : HttpStatus.NOT_FOUND;
            default -> HttpStatus.OK;
        };
    }
}
