package skt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class FactoryState {

    static final FactoryState INSTANCE = new FactoryState();
    private FactoryState() {}

    private static final Path ARCHIVE_DIR = Paths.get("factory-archive");
    private static final Path EVENTS_LOG = ARCHIVE_DIR.resolve("production_events.jsonl");
    private static final Path WORK_ORDERS_LOG = ARCHIVE_DIR.resolve("work_orders.jsonl");

    static {
        try { Files.createDirectories(ARCHIVE_DIR); } catch (IOException ignored) { }
    }

    final Map<Integer, JSONObject> orders = new ConcurrentHashMap<>();
    final AtomicInteger orderIdSeq = new AtomicInteger(1);
    final AtomicInteger logIdSeq = new AtomicInteger(1);
    final Map<String, AbstractWorkstationArtifact> stations = new ConcurrentHashMap<>();
    volatile int maxDurationMs = 20000;

    void registerStation(String name, AbstractWorkstationArtifact ws) {
        stations.put(name, ws);
    }

    JSONObject createOrder(String clientName, String colorFront, String colorRear, int torqueSetting) {
        int id = orderIdSeq.getAndIncrement();
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("client_name", clientName);
        o.put("priority", 1);
        o.put("wheel_color_front", colorFront);
        o.put("wheel_color_rear", colorRear);
        o.put("torque_setting", torqueSetting);
        o.put("current_station", JSONObject.NULL);
        o.put("station_status", "PENDING");
        o.put("created_at", Instant.now().toString());
        o.put("finished_at", JSONObject.NULL);
        o.put("production_logs", new JSONArray());
        orders.put(id, o);
        return o;
    }

    void appendLog(int orderId, String station, String prev, String next, String result, JSONObject payload) {
        JSONObject order = orders.get(orderId);
        if (order == null) return;
        JSONObject entry = new JSONObject();
        entry.put("id", logIdSeq.getAndIncrement());
        entry.put("order_id", orderId);
        entry.put("station", station);
        entry.put("operator_name", "SimOperator");
        entry.put("previous_status", prev);
        entry.put("new_status", next);
        entry.put("result", result);
        entry.put("data_payload", payload);
        entry.put("timestamp", Instant.now().toString());
        order.getJSONArray("production_logs").put(entry);
    }

    JSONArray ordersJson() {
        JSONArray arr = new JSONArray();
        orders.values().forEach(arr::put);
        return arr;
    }

    JSONArray stationsJson() {
        JSONArray arr = new JSONArray();
        new TreeMap<>(stations).forEach((name, ws) -> arr.put(ws.toStatusJson()));
        return arr;
    }

    synchronized void appendEvent(JSONObject event) {
        event.put("recorded_at", Instant.now().toString());
        appendLine(EVENTS_LOG, event.toString());
    }

    synchronized void appendWorkOrderSummary(JSONObject summary) {
        appendLine(WORK_ORDERS_LOG, summary.toString());
    }

    private void appendLine(Path path, String json) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path.toFile(), true))) {
            pw.println(json);
        } catch (IOException e) {
            System.err.println("archive_write_failed: " + e.getMessage());
        }
    }
}