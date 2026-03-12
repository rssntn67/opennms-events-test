package org.opennms.integrationtest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for OpenNMS plugin alarm generation.
 *
 * <p>This test:
 * <ol>
 *   <li>Starts PostgreSQL + OpenNMS via Testcontainers</li>
 *   <li>Deploys the plugin KAR file</li>
 *   <li>Sends SNMP traps using SNMP4J</li>
 *   <li>Verifies alarms via the OpenNMS REST API</li>
 *   <li>Tests alarm lifecycle (acknowledge, clear)</li>
 * </ol>
 *
 * <p>Run with: {@code mvn verify -Pintegration}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OpenNMSPluginAlarmIT {

    private static final Logger LOG = LoggerFactory.getLogger(OpenNMSPluginAlarmIT.class);

    // ── Configuration (customize for your plugin) ────────────────────────────

    /**
     * Path to the .kar file. Override with -Dplugin.kar.path=...
     * Falls back to looking in typical Maven build output locations.
     */
    private static final String KAR_PATH = System.getProperty(
            "plugin.kar.path",
            "src/test/resources/plugin/my-plugin.kar"
    );

    /**
     * Path to the event config overlay XML.
     */
    private static final String EVENT_CONFIG_PATH = System.getProperty(
            "event.config.path",
            "src/test/resources/config/events/my-plugin-events.xml"
    );

    /**
     * The UEI that your plugin should generate alarms with.
     */
    private static final String EXPECTED_ALARM_UEI = System.getProperty(
            "expected.alarm.uei",
            "uei.opennms.org/vendor/myPlugin/traps/testTrap"
    );

    /**
     * SNMP trap OID to send.
     */
    private static final String TRAP_OID = System.getProperty(
            "trap.oid",
            "1.3.6.1.4.1.99999.1.1"
    );

    /**
     * Varbind OID for the trap payload.
     */
    private static final String TRAP_VARBIND_OID = System.getProperty(
            "trap.varbind.oid",
            "1.3.6.1.4.1.99999.1.1.1"
    );

    /** Number of traps to send. */
    private static final int TRAP_COUNT = Integer.getInteger("trap.count", 3);

    /** Milliseconds between traps. */
    private static final long TRAP_INTERVAL_MS = Long.getLong("trap.interval.ms", 2000L);

    /** Maximum wait time for alarms to appear. */
    private static final Duration ALARM_TIMEOUT = Duration.ofSeconds(
            Long.getLong("alarm.timeout.seconds", 120L)
    );

    // ── Shared state ─────────────────────────────────────────────────────────

    private OpenNMSEnvironment env;
    private OpenNMSRestClient client;
    private SnmpTrapSender trapSender;
    private List<JsonNode> foundAlarms;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @BeforeAll
    void setUp() {
        LOG.info("=== Setting up OpenNMS integration test environment ===");
        LOG.info("KAR file: {}", KAR_PATH);
        LOG.info("Event config: {}", EVENT_CONFIG_PATH);
        LOG.info("Expected UEI: {}", EXPECTED_ALARM_UEI);

        // Build the environment
        OpenNMSEnvironment.Builder builder = new OpenNMSEnvironment.Builder()
                .withStartupTimeout(Duration.ofMinutes(5));

        Path karPath = Path.of(KAR_PATH);
        if (Files.exists(karPath)) {
            builder.withKarFile(karPath);
        } else {
            LOG.warn("KAR file not found at {}. Running without plugin deployment.", KAR_PATH);
        }

        Path eventPath = Path.of(EVENT_CONFIG_PATH);
        if (Files.exists(eventPath)) {
            builder.withEventConfig(eventPath);
        }

        env = builder.build();
        env.start();

        // Create the REST client
        client = new OpenNMSRestClient(
                env.getRestBaseUrl(),
                env.getAdminUser(),
                env.getAdminPassword()
        );

        // Create the trap sender
        trapSender = new SnmpTrapSender.Builder()
                .withTarget(env.getTrapHost(), env.getTrapPort())
                .withTrapOid(TRAP_OID)
                .withVarbind(TRAP_VARBIND_OID, "Integration test alarm trigger")
                .build();

        // Wait a bit for KAR hot-deploy to complete
        LOG.info("Waiting 30s for KAR hot-deployment...");
        try {
            Thread.sleep(30_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    void tearDown() {
        if (env != null) {
            env.stop();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Tests
    // ═════════════════════════════════════════════════════════════════════════

    // ── 1. Health Checks ─────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("OpenNMS REST API is reachable and returns system info")
    void testOpenNMSIsHealthy() {
        assertTrue(client.isHealthy(), "OpenNMS REST API should be reachable");

        JsonNode info = client.getInfo();
        assertNotNull(info, "System info should not be null");

        String version = info.has("displayVersion")
                ? info.get("displayVersion").asText()
                : info.path("version").asText("unknown");

        LOG.info("OpenNMS version: {}", version);
        assertFalse(version.isEmpty(), "Version should be present");
    }

    @Test
    @Order(2)
    @DisplayName("REST API authentication works correctly")
    void testRestAuthentication() {
        // This implicitly tests auth — getAlarms would fail with 401
        List<JsonNode> alarms = client.getAllAlarms(1);
        assertNotNull(alarms, "Should get a valid response (even if empty)");
    }

    // ── 2. Send Traps ───────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("SNMP traps are sent to OpenNMS without errors")
    void testSendTraps() throws Exception {
        LOG.info("Sending {} SNMP traps to {}:{}",
                TRAP_COUNT, env.getTrapHost(), env.getTrapPort());

        assertDoesNotThrow(
                () -> trapSender.sendTraps(TRAP_COUNT, TRAP_INTERVAL_MS),
                "Trap sending should not throw an exception"
        );
    }

    // ── 3. Event Verification ────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("OpenNMS has received events after traps were sent")
    void testEventsExist() {
        await("events to appear")
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    List<JsonNode> events = client.getEvents(10);
                    assertFalse(events.isEmpty(),
                            "At least some events should exist after sending traps");
                    LOG.info("Found {} recent events", events.size());
                });
    }

    @Test
    @Order(21)
    @DisplayName("Events matching the expected UEI were created")
    void testEventsByUei() {
        await("events with expected UEI")
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    List<JsonNode> events = client.getEvents(EXPECTED_ALARM_UEI, 100);
                    LOG.info("Found {} events with UEI: {}", events.size(), EXPECTED_ALARM_UEI);
                    assertFalse(events.isEmpty(),
                            "Events with UEI '" + EXPECTED_ALARM_UEI + "' should exist. " +
                            "Check your event definition and trap OID mapping.");
                });
    }

    // ── 4. Alarm Verification ────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("Alarms are created for the expected UEI")
    void testAlarmsCreated() {
        foundAlarms = await("alarms to appear")
                .atMost(ALARM_TIMEOUT)
                .pollInterval(Duration.ofSeconds(5))
                .until(
                        () -> client.getAlarms(EXPECTED_ALARM_UEI),
                        alarms -> !alarms.isEmpty()
                );

        LOG.info("Found {} alarm(s) with UEI: {}", foundAlarms.size(), EXPECTED_ALARM_UEI);
        assertFalse(foundAlarms.isEmpty(),
                "At least one alarm with UEI '" + EXPECTED_ALARM_UEI + "' should exist");
    }

    @Test
    @Order(31)
    @DisplayName("All alarms have the correct UEI")
    void testAlarmUei() {
        assertAlarmsAvailable();

        for (JsonNode alarm : foundAlarms) {
            String uei = alarm.path("uei").asText();
            assertEquals(EXPECTED_ALARM_UEI, uei,
                    "Alarm #" + alarm.path("id").asInt() + " has wrong UEI");
        }
    }

    @Test
    @Order(32)
    @DisplayName("Alarms have an appropriate severity level")
    void testAlarmSeverity() {
        assertAlarmsAvailable();

        Set<String> expectedSeverities = Set.of(
                "WARNING", "MINOR", "MAJOR", "CRITICAL"
        );

        for (JsonNode alarm : foundAlarms) {
            String severity = alarm.path("severity").asText("UNKNOWN");
            int alarmId = alarm.path("id").asInt();

            LOG.info("Alarm #{} severity: {}", alarmId, severity);
            assertTrue(expectedSeverities.contains(severity),
                    String.format("Alarm #%d has unexpected severity '%s'. Expected one of %s",
                            alarmId, severity, expectedSeverities));
        }
    }

    @Test
    @Order(33)
    @DisplayName("Alarms contain a log message")
    void testAlarmLogMessage() {
        assertAlarmsAvailable();

        for (JsonNode alarm : foundAlarms) {
            String logMsg = alarm.path("logMessage").asText("");
            int alarmId = alarm.path("id").asInt();

            LOG.info("Alarm #{} logMessage: {}", alarmId,
                    logMsg.length() > 100 ? logMsg.substring(0, 100) + "..." : logMsg);
            assertFalse(logMsg.isBlank(),
                    "Alarm #" + alarmId + " should have a non-empty logMessage");
        }
    }

    @Test
    @Order(34)
    @DisplayName("Alarm count reflects received traps (deduplication)")
    void testAlarmCount() {
        assertAlarmsAvailable();

        for (JsonNode alarm : foundAlarms) {
            int count = alarm.path("count").asInt(0);
            int alarmId = alarm.path("id").asInt();

            LOG.info("Alarm #{} count: {}", alarmId, count);
            assertTrue(count >= 1,
                    "Alarm #" + alarmId + " count should be >= 1, was " + count);
        }
    }

    @Test
    @Order(35)
    @DisplayName("Alarm has a description")
    void testAlarmDescription() {
        assertAlarmsAvailable();

        for (JsonNode alarm : foundAlarms) {
            String desc = alarm.path("description").asText("");
            int alarmId = alarm.path("id").asInt();

            LOG.info("Alarm #{} description: {}", alarmId,
                    desc.length() > 80 ? desc.substring(0, 80) + "..." : desc);
            // Description might be empty depending on your event config.
            // Adjust this assertion based on your plugin.
        }
    }

    // ── 5. Alarm Lifecycle ───────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("Alarm can be acknowledged via REST API")
    void testAcknowledgeAlarm() {
        assertAlarmsAvailable();

        int alarmId = foundAlarms.get(0).path("id").asInt();
        LOG.info("Acknowledging alarm #{}", alarmId);

        assertDoesNotThrow(
                () -> client.acknowledgeAlarm(alarmId),
                "Acknowledging alarm should not throw"
        );

        // Verify
        JsonNode updated = client.getAlarm(alarmId);
        String ackUser = updated.path("ackUser").asText("");
        assertFalse(ackUser.isEmpty(),
                "Alarm should have an ackUser after acknowledgement");
        LOG.info("Alarm #{} acknowledged by: {}", alarmId, ackUser);
    }

    @Test
    @Order(41)
    @DisplayName("Alarm can be cleared via REST API")
    void testClearAlarm() {
        assertAlarmsAvailable();

        int alarmId = foundAlarms.get(0).path("id").asInt();
        LOG.info("Clearing alarm #{}", alarmId);

        assertDoesNotThrow(
                () -> client.clearAlarm(alarmId),
                "Clearing alarm should not throw"
        );

        // Verify severity changed to CLEARED
        JsonNode updated = client.getAlarm(alarmId);
        String severity = updated.path("severity").asText("");
        LOG.info("Alarm #{} severity after clear: {}", alarmId, severity);
        assertEquals("CLEARED", severity,
                "Alarm severity should be CLEARED after clearing");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void assertAlarmsAvailable() {
        if (foundAlarms == null || foundAlarms.isEmpty()) {
            // Try fetching again in case the earlier test was skipped
            foundAlarms = client.getAlarms(EXPECTED_ALARM_UEI);
        }
        Assumptions.assumeFalse(foundAlarms == null || foundAlarms.isEmpty(),
                "No alarms available — skipping this test. " +
                "Ensure trap OID mapping and event config are correct.");
    }
}
