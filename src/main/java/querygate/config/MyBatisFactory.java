package querygate.config;

import querygate.interceptor.SqlLoggingInterceptor;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Factory for creating MyBatis SqlSessionFactory with manual configuration.
 * Loads external XML mappers and supports hot-reload.
 */
@Factory
public class MyBatisFactory {

    private static final Logger LOG = LoggerFactory.getLogger(MyBatisFactory.class);

    private final GatewayProperties gatewayProperties;
    private final DataSource dataSource;
    private final SqlLoggingInterceptor sqlLoggingInterceptor;

    // AtomicReference for hot-reload support - allows swapping the factory without restart
    private final AtomicReference<SqlSessionFactory> sessionFactoryRef = new AtomicReference<>();

    public MyBatisFactory(
            GatewayProperties gatewayProperties,
            @Named("default") DataSource dataSource,
            SqlLoggingInterceptor sqlLoggingInterceptor) {
        this.gatewayProperties = gatewayProperties;
        this.dataSource = dataSource;
        this.sqlLoggingInterceptor = sqlLoggingInterceptor;
    }

    /**
     * Creates the primary SqlSessionFactory bean.
     * This factory is initialized at startup and can be hot-reloaded.
     */
    @Singleton
    public SqlSessionFactory sqlSessionFactory() {
        SqlSessionFactory factory = createSqlSessionFactory();
        sessionFactoryRef.set(factory);
        return factory;
    }

    /**
     * Creates a new SqlSessionFactory instance.
     * Can be called during hot-reload to recreate the factory with updated mappers.
     */
    public SqlSessionFactory createSqlSessionFactory() {
        LOG.info("Creating SqlSessionFactory with external mappers");

        TransactionFactory transactionFactory = new JdbcTransactionFactory();
        Environment environment = new Environment("production", transactionFactory, dataSource);

        Configuration configuration = new Configuration(environment);

        // Apply settings from properties
        GatewayProperties.MyBatisConfig mybatisConfig = gatewayProperties.getMybatis();
        configuration.setCacheEnabled(mybatisConfig.isCacheEnabled());
        configuration.setLazyLoadingEnabled(mybatisConfig.isLazyLoadingEnabled());
        configuration.setDefaultStatementTimeout(mybatisConfig.getDefaultStatementTimeout());

        // Additional MyBatis settings for Map-based results
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setCallSettersOnNulls(true);
        configuration.setReturnInstanceForEmptyRow(true);

        // Add SQL logging interceptor
        if (gatewayProperties.getSqlLogging().isEnabled()) {
            configuration.addInterceptor(sqlLoggingInterceptor);
            LOG.debug("SQL logging interceptor added");
        }

        // Load external XML mappers
        loadExternalMappers(configuration);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        LOG.info("SqlSessionFactory created successfully");

        return factory;
    }

    /**
     * Loads all XML mapper files from the external mapper directory.
     */
    private void loadExternalMappers(Configuration configuration) {
        String mapperLocation = gatewayProperties.getMybatis().getMapperLocations();
        Path mapperDir = Paths.get(mapperLocation);

        LOG.info("Loading MyBatis mappers from: {}", mapperDir.toAbsolutePath());

        if (!Files.exists(mapperDir)) {
            LOG.warn("Mapper directory does not exist: {}. Creating it...", mapperDir);
            try {
                Files.createDirectories(mapperDir);
            } catch (IOException e) {
                LOG.error("Failed to create mapper directory", e);
            }
            return;
        }

        if (!Files.isDirectory(mapperDir)) {
            LOG.error("Mapper location is not a directory: {}", mapperDir);
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mapperDir, "*.xml")) {
            int mapperCount = 0;
            for (Path mapperFile : stream) {
                loadMapper(configuration, mapperFile);
                mapperCount++;
            }
            LOG.info("Loaded {} mapper file(s) from {}", mapperCount, mapperDir);
        } catch (IOException e) {
            LOG.error("Failed to scan mapper directory: {}", mapperDir, e);
            throw new IllegalStateException("Failed to load mapper files", e);
        }
    }

    /**
     * Loads a single mapper XML file into the MyBatis configuration.
     */
    private void loadMapper(Configuration configuration, Path mapperFile) {
        String resourcePath = mapperFile.toAbsolutePath().toString();
        LOG.debug("Loading mapper: {}", mapperFile.getFileName());

        try (InputStream is = Files.newInputStream(mapperFile)) {
            XMLMapperBuilder mapperBuilder = new XMLMapperBuilder(
                    is,
                    configuration,
                    resourcePath,
                    configuration.getSqlFragments()
            );
            mapperBuilder.parse();
            LOG.debug("Successfully loaded mapper: {}", mapperFile.getFileName());
        } catch (IOException e) {
            LOG.error("Failed to load mapper file: {}", mapperFile, e);
            throw new IllegalStateException("Failed to load mapper: " + mapperFile, e);
        } catch (Exception e) {
            LOG.error("Error parsing mapper file: {}", mapperFile, e);
            throw new IllegalStateException("Error parsing mapper: " + mapperFile, e);
        }
    }

    /**
     * Recreates the SqlSessionFactory with reloaded mappers.
     * Called by XmlReloadService when mapper files change.
     *
     * @return the new SqlSessionFactory instance
     */
    public SqlSessionFactory reload() {
        LOG.info("Reloading SqlSessionFactory due to mapper changes");

        try {
            SqlSessionFactory newFactory = createSqlSessionFactory();
            SqlSessionFactory oldFactory = sessionFactoryRef.getAndSet(newFactory);

            LOG.info("SqlSessionFactory reloaded successfully");
            return newFactory;

        } catch (Exception e) {
            LOG.error("Failed to reload SqlSessionFactory, keeping existing configuration", e);
            throw new IllegalStateException("Mapper reload failed", e);
        }
    }

    /**
     * Gets the current SqlSessionFactory instance.
     * Use this method instead of injecting SqlSessionFactory directly
     * to ensure hot-reload compatibility.
     *
     * @return the current SqlSessionFactory
     */
    public SqlSessionFactory getCurrentFactory() {
        SqlSessionFactory factory = sessionFactoryRef.get();
        if (factory == null) {
            throw new IllegalStateException("SqlSessionFactory not initialized");
        }
        return factory;
    }

    /**
     * Checks if the mapper directory contains any XML files.
     *
     * @return true if mappers exist
     */
    public boolean hasMappers() {
        String mapperLocation = gatewayProperties.getMybatis().getMapperLocations();
        Path mapperDir = Paths.get(mapperLocation);

        if (!Files.exists(mapperDir) || !Files.isDirectory(mapperDir)) {
            return false;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mapperDir, "*.xml")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }
}
