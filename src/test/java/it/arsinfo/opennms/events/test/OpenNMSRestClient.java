package it.arsinfo.opennms.events.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.restassured.RestAssured.given;

/**
 * Helper client for the OpenNMS REST API.
 * Provides typed access to alarms, events, nodes, and system info.
 */
public class OpenNMSRestClient {

    private static final Logger LOG = LoggerFactory.getLogger(OpenNMSRestClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String username;
    private final String password;

    public OpenNMSRestClient(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
    }

    // ── System ───────────────────────────────────────────────────────────────

    /**
     * Get OpenNMS system information.
     */
    public JsonNode getInfo() {
        Response resp = request()
                .get(baseUrl + "/rest/info");
        resp.then().statusCode(200);
        return parseJson(resp.getBody().asString());
    }

    /**
     * Check if the REST API is reachable.
     */
    public boolean isHealthy() {
        try {
            Response resp = request()
                    .get(baseUrl + "/rest/info");
            return resp.getStatusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Events ───────────────────────────────────────────────────────────────

    /**
     * Get recent events, optionally filtered by UEI.
     */
    public List<JsonNode> getEvents(String uei, int limit) {
        RequestSpecification req = request()
                .queryParam("limit", limit)
                .queryParam("orderBy", "id")
                .queryParam("order", "desc");

        if (uei != null && !uei.isEmpty()) {
            req.queryParam("comparator", "eq")
               .queryParam("uei", uei);
        }

        Response resp = req.get(baseUrl + "/rest/events");

        if (resp.getStatusCode() != 200) {
            LOG.warn("Events request returned {}", resp.getStatusCode());
            return Collections.emptyList();
        }

        return parseArray(resp.getBody().asString(), "event");
    }

    public List<JsonNode> getEvents(int limit) {
        return getEvents(null, limit);
    }

    // ── Alarms ───────────────────────────────────────────────────────────────

    /**
     * Get alarms, optionally filtered by UEI.
     */
    public List<JsonNode> getAlarms(String uei, int limit) {
        RequestSpecification req = request()
                .queryParam("limit", limit)
                .queryParam("orderBy", "id")
                .queryParam("order", "desc");

        if (uei != null && !uei.isEmpty()) {
            req.queryParam("comparator", "eq")
               .queryParam("uei", uei);
        }

        Response resp = req.get(baseUrl + "/rest/alarms");

        if (resp.getStatusCode() != 200) {
            LOG.warn("Alarms request returned {}", resp.getStatusCode());
            return Collections.emptyList();
        }

        return parseArray(resp.getBody().asString(), "alarm");
    }

    public List<JsonNode> getAlarms(String uei) {
        return getAlarms(uei, 100);
    }

    public List<JsonNode> getAllAlarms(int limit) {
        return getAlarms(null, limit);
    }

    /**
     * Get a single alarm by ID.
     */
    public JsonNode getAlarm(int alarmId) {
        Response resp = request()
                .get(baseUrl + "/rest/alarms/" + alarmId);
        resp.then().statusCode(200);
        return parseJson(resp.getBody().asString());
    }

    /**
     * Acknowledge an alarm.
     */
    public void acknowledgeAlarm(int alarmId) {
        request()
                .contentType("application/x-www-form-urlencoded")
                .body("ack=true")
                .put(baseUrl + "/rest/alarms/" + alarmId)
                .then()
                .statusCode(204);

        LOG.info("Acknowledged alarm #{}", alarmId);
    }

    /**
     * Clear an alarm.
     */
    public void clearAlarm(int alarmId) {
        request()
                .contentType("application/x-www-form-urlencoded")
                .body("clear=true")
                .put(baseUrl + "/rest/alarms/" + alarmId)
                .then()
                .statusCode(204);

        LOG.info("Cleared alarm #{}", alarmId);
    }

    // ── Nodes ────────────────────────────────────────────────────────────────

    /**
     * Get all nodes.
     */
    public List<JsonNode> getNodes(int limit) {
        Response resp = request()
                .queryParam("limit", limit)
                .get(baseUrl + "/rest/nodes");

        if (resp.getStatusCode() != 200) {
            return Collections.emptyList();
        }

        return parseArray(resp.getBody().asString(), "node");
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private RequestSpecification request() {
        return given()
                .auth().preemptive().basic(username, password)
                .accept("application/json");
    }

    /**
     * Parse a JSON response that may be a single object or array wrapped
     * in a named field (OpenNMS REST convention).
     */
    private List<JsonNode> parseArray(String json, String fieldName) {
        try {
            JsonNode root = MAPPER.readTree(json);

            // Response may have the count and totalCount fields with empty content
            JsonNode items = root.get(fieldName);
            if (items == null) {
                return Collections.emptyList();
            }

            List<JsonNode> result = new ArrayList<>();
            if (items.isArray()) {
                items.forEach(result::add);
            } else {
                // Single item returned as object, not array
                result.add(items);
            }
            return result;

        } catch (Exception e) {
            LOG.error("Failed to parse JSON response: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private JsonNode parseJson(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }
}