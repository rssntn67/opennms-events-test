package org.opennms.integrationtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/**
 * Manages the lifecycle of OpenNMS + PostgreSQL containers for integration testing.
 *
 * <p>Usage:
 * <pre>
 *   OpenNMSEnvironment env = new OpenNMSEnvironment.Builder()
 *       .withKarFile(Path.of("target/my-plugin.kar"))
 *       .withEventConfig(Path.of("src/test/resources/config/events/my-plugin-events.xml"))
 *       .build();
 *
 *   env.start();  // Blocks until OpenNMS is healthy
 *   // ... run tests using env.getRestBaseUrl() ...
 *   env.stop();
 * </pre>
 */
public class OpenNMSEnvironment {

    private static final Logger LOG = LoggerFactory.getLogger(OpenNMSEnvironment.class);

    private static final String DEFAULT_OPENNMS_IMAGE = "opennms/horizon:33.0.5";
    private static final String DEFAULT_POSTGRES_IMAGE = "postgres:15-alpine";

    private static final String OPENNMS_DB_USER = "opennms";
    private static final String OPENNMS_DB_PASS = "opennms";
    private static final String OPENNMS_DB_NAME = "opennms";
    private static final String OPENNMS_ADMIN_USER = "admin";
    private static final String OPENNMS_ADMIN_PASS = "admin";

    private static final int OPENNMS_WEB_PORT = 8980;
    private static final int OPENNMS_TRAP_PORT = 1162;
    private static final int OPENNMS_KARAF_PORT = 8101;

    private final String opennmsImage;
    private final String postgresImage;
    private final Path karFile;
    private final Path eventConfigFile;
    private final Duration startupTimeout;

    private Network network;
    private PostgreSQLContainer<?> postgres;
    private GenericContainer<?> opennms;

    private OpenNMSEnvironment(Builder builder) {
        this.opennmsImage = builder.opennmsImage;
        this.postgresImage = builder.postgresImage;
        this.karFile = builder.karFile;
        this.eventConfigFile = builder.eventConfigFile;
        this.startupTimeout = builder.startupTimeout;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Start the PostgreSQL and OpenNMS containers.
     * Blocks until OpenNMS REST API is healthy.
     */
    public void start() {
        LOG.info("Starting OpenNMS integration test environment...");

        network = Network.newNetwork();

        startPostgres();
        startOpenNMS();

        LOG.info("OpenNMS environment is ready.");
        LOG.info("  REST API: {}", getRestBaseUrl());
        LOG.info("  Trap port: {}", getTrapPort());
        LOG.info("  Trap host: {}", getTrapHost());
    }

    /**
     * Stop and remove all containers.
     */
    public void stop() {
        LOG.info("Stopping OpenNMS environment...");
        if (opennms != null && opennms.isRunning()) {
            opennms.stop();
        }
        if (postgres != null && postgres.isRunning()) {
            postgres.stop();
        }
        if (network != null) {
            network.close();
        }
        LOG.info("Environment stopped.");
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    /** Base URL for the OpenNMS REST API (e.g., http://localhost:32789/opennms) */
    public String getRestBaseUrl() {
        return String.format("http://%s:%d/opennms",
                opennms.getHost(), opennms.getMappedPort(OPENNMS_WEB_PORT));
    }

    /** Host for sending SNMP traps (from outside Docker) */
    public String getTrapHost() {
        return opennms.getHost();
    }

    /** Mapped UDP port for SNMP traps */
    public int getTrapPort() {
        return opennms.getMappedPort(OPENNMS_TRAP_PORT);
    }

    /** Host for the OpenNMS container within the Docker network */
    public String getOpenNMSInternalHost() {
        return "opennms";
    }

    public String getAdminUser() {
        return OPENNMS_ADMIN_USER;
    }

    public String getAdminPassword() {
        return OPENNMS_ADMIN_PASS;
    }

    public GenericContainer<?> getOpenNMSContainer() {
        return opennms;
    }

    public PostgreSQLContainer<?> getPostgresContainer() {
        return postgres;
    }

    public Network getNetwork() {
        return network;
    }

    // ── Internal Setup ───────────────────────────────────────────────────────

    private void startPostgres() {
        LOG.info("Starting PostgreSQL ({})...", postgresImage);

        postgres = new PostgreSQLContainer<>(DockerImageName.parse(postgresImage))
                .withDatabaseName(OPENNMS_DB_NAME)
                .withUsername(OPENNMS_DB_USER)
                .withPassword(OPENNMS_DB_PASS)
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .waitingFor(Wait.forListeningPort());

        postgres.start();
        LOG.info("PostgreSQL started at {}:{}", postgres.getHost(), postgres.getMappedPort(5432));
    }

    @SuppressWarnings("resource")
    private void startOpenNMS() {
        LOG.info("Starting OpenNMS ({})...", opennmsImage);

        opennms = new GenericContainer<>(DockerImageName.parse(opennmsImage))
                .withNetwork(network)
                .withNetworkAliases("opennms")
                .withExposedPorts(OPENNMS_WEB_PORT, OPENNMS_KARAF_PORT)
                // Expose trap port as UDP
                .withCreateContainerCmdModifier(cmd -> {
                    cmd.getHostConfig().withPortBindings(
                            new com.github.dockerjava.api.model.PortBinding(
                                    com.github.dockerjava.api.model.Ports.Binding.empty(),
                                    new com.github.dockerjava.api.model.ExposedPort(
                                            OPENNMS_TRAP_PORT,
                                            com.github.dockerjava.api.model.InternetProtocol.UDP
                                    )
                            )
                    );
                })
                // ── Environment ──
                .withEnv("POSTGRES_HOST", "postgres")
                .withEnv("POSTGRES_PORT", "5432")
                .withEnv("POSTGRES_USER", OPENNMS_DB_USER)
                .withEnv("POSTGRES_PASSWORD", OPENNMS_DB_PASS)
                .withEnv("OPENNMS_DBNAME", OPENNMS_DB_NAME)
                .withEnv("TZ", "UTC")
                .withEnv("JAVA_OPTS", "-Xms512m -Xmx1024m")
                // ── Health check: wait for REST API ──
                .waitingFor(
                        new HttpWaitStrategy()
                                .forPort(OPENNMS_WEB_PORT)
                                .forPath("/opennms/rest/info")
                                .withBasicCredentials(OPENNMS_ADMIN_USER, OPENNMS_ADMIN_PASS)
                                .forStatusCode(HttpURLConnection.HTTP_OK)
                                .withStartupTimeout(startupTimeout)
                )
                .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("opennms"));

        // ── Mount KAR plugin ──
        if (karFile != null && Files.exists(karFile)) {
            LOG.info("Deploying KAR file: {}", karFile);
            opennms.withCopyFileToContainer(
                    MountableFile.forHostPath(karFile),
                    "/usr/share/opennms/deploy/" + karFile.getFileName()
            );
        } else {
            LOG.warn("No KAR file specified or file not found. Skipping plugin deployment.");
        }

        // ── Mount event configuration overlay ──
        if (eventConfigFile != null && Files.exists(eventConfigFile)) {
            LOG.info("Mounting event config overlay: {}", eventConfigFile);
            opennms.withCopyFileToContainer(
                    MountableFile.forHostPath(eventConfigFile),
                    "/opt/opennms-overlay/etc/events/" + eventConfigFile.getFileName()
            );
        }

        opennms.start();
        LOG.info("OpenNMS started. REST API at: {}", getRestBaseUrl());
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static class Builder {

        private String opennmsImage = System.getProperty("opennms.image", DEFAULT_OPENNMS_IMAGE);
        private String postgresImage = System.getProperty("postgres.image", DEFAULT_POSTGRES_IMAGE);
        private Path karFile;
        private Path eventConfigFile;
        private Duration startupTimeout = Duration.ofMinutes(5);

        public Builder withOpenNMSImage(String image) {
            this.opennmsImage = image;
            return this;
        }

        public Builder withPostgresImage(String image) {
            this.postgresImage = image;
            return this;
        }

        /** Path to the .kar file to deploy into OpenNMS. */
        public Builder withKarFile(Path karFile) {
            this.karFile = karFile;
            return this;
        }

        /** Path to an event configuration XML to overlay. */
        public Builder withEventConfig(Path eventConfigFile) {
            this.eventConfigFile = eventConfigFile;
            return this;
        }

        /** Maximum time to wait for OpenNMS to become healthy. */
        public Builder withStartupTimeout(Duration timeout) {
            this.startupTimeout = timeout;
            return this;
        }

        public OpenNMSEnvironment build() {
            return new OpenNMSEnvironment(this);
        }
    }
}
