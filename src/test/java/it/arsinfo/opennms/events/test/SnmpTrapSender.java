package it.arsinfo.opennms.events.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;

/**
 * Sends SNMP v2c traps to OpenNMS for integration testing.
 *
 * <p>Uses SNMP4J to construct and send trap PDUs. All configuration
 * (target host, port, OIDs, etc.) is provided via the builder.
 *
 * <p>Example:
 * <pre>
 *   SnmpTrapSender sender = new SnmpTrapSender.Builder()
 *       .withTarget("localhost", 1162)
 *       .withTrapOid("1.3.6.1.4.1.99999.1.1")
 *       .build();
 *
 *   sender.sendTraps(3, Duration.ofSeconds(2));
 * </pre>
 */
public class SnmpTrapSender {

    private static final Logger LOG = LoggerFactory.getLogger(SnmpTrapSender.class);

    private final String targetHost;
    private final int targetPort;
    private final String community;
    private final OID trapOid;
    private final OID varbindOid;
    private final String varbindValue;

    private SnmpTrapSender(Builder builder) {
        this.targetHost = builder.targetHost;
        this.targetPort = builder.targetPort;
        this.community = builder.community;
        this.trapOid = new OID(builder.trapOid);
        this.varbindOid = new OID(builder.varbindOid);
        this.varbindValue = builder.varbindValue;
    }

    /**
     * Send multiple SNMP v2c traps with a delay between each.
     *
     * @param count           Number of traps to send.
     * @param intervalMillis  Milliseconds between each trap.
     */
    public void sendTraps(int count, long intervalMillis) throws IOException, InterruptedException {
        LOG.info("Sending {} SNMPv2c trap(s) to {}:{} (OID: {})",
                count, targetHost, targetPort, trapOid);

        try (DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping();
             Snmp snmp = new Snmp(transport)) {

            transport.listen();

            CommunityTarget<UdpAddress> target = new CommunityTarget<>();
            target.setCommunity(new OctetString(community));
            target.setAddress(new UdpAddress(targetHost + "/" + targetPort));
            target.setVersion(SnmpConstants.version2c);
            target.setRetries(2);
            target.setTimeout(5000);

            for (int i = 1; i <= count; i++) {
                PDU pdu = new PDU();
                pdu.setType(PDU.NOTIFICATION);

                // SNMPv2 trap requires sysUpTime and snmpTrapOID varbinds
                pdu.add(new VariableBinding(
                        SnmpConstants.sysUpTime,
                        new TimeTicks(System.currentTimeMillis() / 10)
                ));
                pdu.add(new VariableBinding(
                        SnmpConstants.snmpTrapOID,
                        trapOid
                ));

                // Custom varbind
                pdu.add(new VariableBinding(
                        varbindOid,
                        new OctetString(varbindValue + " #" + i)
                ));

                snmp.send(pdu, target);
                LOG.info("  Sent trap #{}/{}", i, count);

                if (i < count && intervalMillis > 0) {
                    Thread.sleep(intervalMillis);
                }
            }
        }

        LOG.info("All {} traps sent to {}:{}", count, targetHost, targetPort);
    }

    /**
     * Send a single trap.
     */
    public void sendTrap() throws IOException, InterruptedException {
        sendTraps(1, 0);
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static class Builder {

        private String targetHost = "localhost";
        private int targetPort = 1162;
        private String community = "public";
        private String trapOid = "1.3.6.1.4.1.99999.1.1";
        private String varbindOid = "1.3.6.1.4.1.99999.1.1.1";
        private String varbindValue = "Integration test alarm trigger";

        public Builder withTarget(String host, int port) {
            this.targetHost = host;
            this.targetPort = port;
            return this;
        }

        public Builder withCommunity(String community) {
            this.community = community;
            return this;
        }

        public Builder withTrapOid(String oid) {
            this.trapOid = oid;
            return this;
        }

        public Builder withVarbind(String oid, String value) {
            this.varbindOid = oid;
            this.varbindValue = value;
            return this;
        }

        public SnmpTrapSender build() {
            return new SnmpTrapSender(this);
        }
    }
}