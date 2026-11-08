package skt;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;

public class EmptyStorage extends Artifact {

    private HttpServer http;

    void init(int httpPort) {
        startHttp(httpPort);
        log("Factory running: http://localhost:" + httpPort);
    }

    /** Original signature, kept so existing callers keep working. */
    @OPERATION
    void startSkateboardOrder(String clientName, String colorFront, String colorRear, int torqueSetting, int deadlineMs,
                            OpFeedbackParam<Integer> orderIdOut) {
        release(clientName, colorFront, colorRear, torqueSetting, deadlineMs, "creator", 0, 0, 0, 100, orderIdOut, null);
    }

    /**
     * Full signature: also records where the order came from ("creator" or "web")
     * and the focus weights (time, energy, quality) the skateboard works with.
     * Needs role order_builder; the caller becomes the order's owner.
     */
    @OPERATION
    void startSkateboardOrder(String clientName, String colorFront, String colorRear, int torqueSetting, int deadlineMs,
                            String source, double wTime, double wEnergy, double wQuality,
                            OpFeedbackParam<Integer> orderIdOut) {
        release(clientName, colorFront, colorRear, torqueSetting, deadlineMs, source, wTime, wEnergy, wQuality, 100, orderIdOut, null);
    }

    /** Customer terms: price fixes the order's energy budget, returned together with the order id. */
    @OPERATION
    void startSkateboardOrder(String clientName, String colorFront, String colorRear, int torqueSetting, int deadlineMs,
                            String source, double wTime, double wEnergy, double wQuality, double price,
                            OpFeedbackParam<Integer> orderIdOut, OpFeedbackParam<Double> energyBudgetOut) {
        release(clientName, colorFront, colorRear, torqueSetting, deadlineMs, source, wTime, wEnergy, wQuality, price, orderIdOut, energyBudgetOut);
    }

    private void release(String clientName, String colorFront, String colorRear, int torqueSetting, int deadlineMs,
                         String source, double wTime, double wEnergy, double wQuality, double price,
                         OpFeedbackParam<Integer> orderIdOut, OpFeedbackParam<Double> energyBudgetOut) {
        String owner = getCurrentOpAgentId().getAgentName();
        String denied = OrgLedger.INSTANCE.check(owner, OrgLedger.START_ORDER, -1, "EmptyStorage.startSkateboardOrder");
        if (denied != null) { failed(denied); return; }
        String ref = clientName;                                   // "customer#ref" names one order of a customer
        clientName = CustomerModel.identity(clientName);
        JSONObject o = FactoryState.INSTANCE.createOrder(clientName, colorFront, colorRear, torqueSetting);
        o.put("order_ref", ref);
        int id = o.getInt("id");
        o.put("deadline_ms", deadlineMs);
        o.put("owner_agent", owner);
        double budget = CustomerModel.energyBudget(price);
        o.put("price", price);
        o.put("energy_budget", budget);
        if (energyBudgetOut != null) energyBudgetOut.set(budget);
        o.put("source", source);
        o.put("focus", new JSONObject().put("time", wTime).put("energy", wEnergy).put("quality", wQuality));
        orderIdOut.set(id);
        signal("order_created", id, clientName, colorFront, colorRear, torqueSetting, deadlineMs);
        signal("order_registered", id, owner, source);
        signal("order_terms", id, price, budget);
        FactoryState.INSTANCE.appendEvent(new JSONObject()
                .put("event_type", "work_order_released")
                .put("work_order_id", id)
                .put("customer_name", clientName)
                .put("front_wheel_color", colorFront)
                .put("rear_wheel_color", colorRear)
                .put("target_torque", torqueSetting)
                .put("owner_agent", owner)
                .put("source", source));
    }

    @OPERATION
    void setMaxDurationMs(int ms) {
        FactoryState.INSTANCE.maxDurationMs = ms;
    }

    private void startHttp(int port) {
        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
            http.createContext("/orders/", ex -> sendJson(ex, FactoryState.INSTANCE.ordersJson().toString()));
            http.createContext("/stations/", ex -> sendJson(ex, FactoryState.INSTANCE.stationsJson().toString()));
            http.createContext("/policy/", ex -> {
                JSONObject p = new JSONObject();
                p.put("max_duration_ms", FactoryState.INSTANCE.maxDurationMs);
                p.put("energy_budget", 120);
                p.put("time_scale", FactoryConfig.SCALE);
                sendJson(ex, p.toString());
            });
            http.createContext("/org/", ex -> sendJson(ex, OrgLedger.INSTANCE.toJson().toString()));
            http.createContext("/regulation/", ex -> sendJson(ex, Regulation.INSTANCE.toJson().toString()));
            http.createContext("/api/", WebOrderApi::handle);   // website integration, see WebOrderApi
            http.createContext("/", this::serveStatic);
            http.setExecutor(Executors.newCachedThreadPool());
            http.start();
        } catch (IOException e) {
            failed("http_server_error: " + e.getMessage());
        }
    }

    private void sendJson(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path root = Paths.get("../UI/gui").toAbsolutePath().normalize();
        Path file = root.resolve("." + path).normalize();
        // the server is reachable from a website now: never serve outside the UI folder
        if (!file.startsWith(root) || !Files.isRegularFile(file)) { ex.sendResponseHeaders(404, -1); return; }
        byte[] bytes = Files.readAllBytes(file);
        String contentType = path.endsWith(".html") ? "text/html"
                : path.endsWith(".js") ? "application/javascript"
                : path.endsWith(".css") ? "text/css"
                : "application/octet-stream";
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}