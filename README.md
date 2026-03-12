# OpenNMS Plugin Integration Tests (Testcontainers)

Java-based integration tests using [Testcontainers](https://testcontainers.com/) to verify OpenNMS plugin behavior.

## Project Structure

```
opennms-testcontainers/
├── pom.xml
└── src/test/
    ├── java/org/opennms/integrationtest/
    │   ├── OpenNMSEnvironment.java       # Container lifecycle manager
    │   ├── OpenNMSRestClient.java        # REST API client helper
    │   ├── SnmpTrapSender.java           # SNMP4J trap sender
    │   └── OpenNMSPluginAlarmIT.java     # The integration test
    └── resources/
        ├── config/events/
        │   └── my-plugin-events.xml      # Event/alarm definition overlay
        ├── plugin/                       # ← Place your .kar file here
        └── logback-test.xml              # Logging configuration
```

## Prerequisites

- Java 17+
- Maven 3.8+
- Docker running locally (Testcontainers needs it)

## Quick Start

### 1. Place your plugin

```bash
cp /path/to/your-plugin.kar src/test/resources/plugin/my-plugin.kar
```

### 2. Customize configuration

Edit `OpenNMSPluginAlarmIT.java` or pass system properties:

| Property               | Default                                                  | Description                       |
|------------------------|----------------------------------------------------------|-----------------------------------|
| `plugin.kar.path`      | `src/test/resources/plugin/my-plugin.kar`                | Path to your KAR file             |
| `event.config.path`    | `src/test/resources/config/events/my-plugin-events.xml`  | Event definition overlay          |
| `expected.alarm.uei`   | `uei.opennms.org/vendor/myPlugin/traps/testTrap`         | UEI to look for in alarms         |
| `trap.oid`             | `1.3.6.1.4.1.99999.1.1`                                 | SNMP trap OID                     |
| `trap.varbind.oid`     | `1.3.6.1.4.1.99999.1.1.1`                               | Varbind OID                       |
| `trap.count`           | `3`                                                      | Number of traps to send           |
| `trap.interval.ms`     | `2000`                                                   | Delay between traps (ms)          |
| `alarm.timeout.seconds`| `120`                                                    | Max wait for alarms to appear     |

### 3. Run the tests

```bash
# Run with Maven Failsafe
mvn verify -Pintegration

# With custom properties
mvn verify -Pintegration \
    -Dplugin.kar.path=../my-project/target/my-plugin.kar \
    -Dexpected.alarm.uei=uei.opennms.org/vendor/myPlugin/traps/myTrap \
    -Dtrap.oid=1.3.6.1.4.1.12345.1.1
```

## Test Flow

The test class `OpenNMSPluginAlarmIT` runs tests in order:

1. **Health checks** (Order 1-2): Verify OpenNMS REST API is up
2. **Send traps** (Order 10): Fire SNMP traps via SNMP4J
3. **Event verification** (Order 20-21): Confirm events were created
4. **Alarm verification** (Order 30-35): Check alarm UEI, severity, log message, count
5. **Alarm lifecycle** (Order 40-41): Test acknowledge and clear operations

## Key Design Decisions

**Why SNMP4J instead of a trap-sender container?**
Sending traps directly from Java via SNMP4J avoids the need for an extra Docker container and gives you full control over trap content, timing, and error handling in your test code.

**Why Awaitility for polling?**
OpenNMS processes traps asynchronously. Awaitility provides clean, readable polling with configurable timeouts and intervals — much cleaner than manual `Thread.sleep` loops.

**Why `@TestInstance(PER_CLASS)`?**
The OpenNMS environment is expensive to start (~2-4 minutes). `PER_CLASS` lifecycle means we start it once in `@BeforeAll` and share it across all test methods.

**Why `@Order` on tests?**
Tests are sequenced logically: health → traps → events → alarms → lifecycle. Later tests depend on earlier ones having succeeded (e.g., alarm lifecycle tests need alarms to exist).

## Integrating with Your Plugin Build

To run these tests as part of your plugin's Maven build, add this module and wire the KAR artifact:

```xml
<!-- In your parent pom.xml -->
<modules>
    <module>my-plugin</module>
    <module>my-plugin-integration-test</module>
</modules>
```

```xml
<!-- In the integration test module pom.xml -->
<dependencies>
    <dependency>
        <groupId>com.mycompany</groupId>
        <artifactId>my-plugin</artifactId>
        <version>${project.version}</version>
        <type>kar</type>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Then use `maven-dependency-plugin` to copy the KAR to `target/plugin/` and point the test at it:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions>
        <execution>
            <id>copy-kar</id>
            <phase>pre-integration-test</phase>
            <goals><goal>copy-dependencies</goal></goals>
            <configuration>
                <includeTypes>kar</includeTypes>
                <outputDirectory>${project.build.directory}/plugin</outputDirectory>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Debugging

```bash
# Run a single test
mvn verify -Pintegration -Dit.test=OpenNMSPluginAlarmIT#testAlarmsCreated

# Enable verbose Testcontainers logging
mvn verify -Pintegration -Dorg.slf4j.simpleLogger.defaultLogLevel=debug

# Keep containers alive on failure (add to test code):
#   .withReuse(true) on the container
```
