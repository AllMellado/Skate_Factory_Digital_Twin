package skt;

import com.sun.net.httpserver.HttpExchange;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

/**
 * HTTP side of the website integration. Routes (all under /api/):
 *   GET  /api/options          allowed colours, torques, deadline range, focus presets
 *   POST /api/orders           place an order (JSON, or a plain HTML form post)
 *   GET  /api/orders/{ticket}  follow an order
 *
 * This class only validates and queues. The order_intake agent drains the queue
 * through OrderGateway, so a web order goes through exactly the same admission
 * (role assignment) as an order from skate_creator.
 *
 * Config (environment variables):
 *   SKT_API_KEY         if set, POST needs header X-Api-Key with this value
 *   SKT_ALLOWED_ORIGIN  CORS origin of your website (default "*")
 */
final class WebOrderApi {

    // Keep in sync with colors/torque_options in skateboard.asl.
    static final List<String> COLORS = List.of("White", "Blue", "Green", "Orange");
    static final List<Integer> TORQUES = List.of(20, 30, 40, 50, 60, 70, 80, 90);
    static final int MIN_DEADLINE_MS = FactoryConfig.ms(10_000), MAX_DEADLINE_MS = FactoryConfig.ms(120_000), DEFAULT_DEADLINE_MS = FactoryConfig.ms(60_000);
    static final int MAX_PENDING = 200, MAX_BODY_BYTES = 4096, MAX_TICKETS_KEPT = 5000;
    static final List<String> FOCUS_PRESETS = List.of("balanced", "time", "energy", "quality");

    private static final Pattern CUSTOMER = Pattern.compile("[\\p{L}\\p{N} .,'_@#-]{1,60}");
    private static final SecureRandom RNG = new SecureRandom();
    private static final String API_KEY = System.getenv("SKT_API_KEY");
    private static final String ALLOWED_ORIGIN = System.getenv().getOrDefault("SKT_ALLOWED_ORIGIN", "*");

    static final class WebOrder {
        String ticket, customer, front, rear;
        int torque, deadlineMs;
        double wTime, wEnergy, wQuality;   // all 0 = "let the advisor / agent decide"
        double price = 100;                 // fixes the energy budget
    }

    static final class Ticket {
        final String id, customer;
        final long createdAt = System.currentTimeMillis();
        volatile String agent, rejected;
        Ticket(String id, String customer) { this.id = id; this.customer = customer; }
    }

    static final BlockingQueue<WebOrder> PENDING = new LinkedBlockingQueue<>(MAX_PENDING);
    static final Map<String, Ticket> TICKETS = java.util.Collections.synchronizedMap(
            new LinkedHashMap<String, Ticket>() {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Ticket> e) {
                    return size() > MAX_TICKETS_KEPT;
                }
            });

    private WebOrderApi() {}

    // ------------------------------------------------------------------ routing
    static void handle(HttpExchange ex) throws IOException {
        try {
            cors(ex);
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if ("OPTIONS".equals(method)) { ex.sendResponseHeaders(204, -1); return; }

            if (path.equals("/api/options") && "GET".equals(method)) {
                send(ex, 200, options());
            } else if (path.equals("/api/orders") && "POST".equals(method)) {
                postOrder(ex);
            } else if (path.startsWith("/api/orders/") && "GET".equals(method)) {
                getOrder(ex, path.substring("/api/orders/".length()));
            } else {
                send(ex, 404, err("not_found"));
            }
        } catch (Exception e) {
            send(ex, 500, err("internal_error"));
        } finally {
            ex.close();
        }
    }

    private static JSONObject options() {
        return new JSONObject()
                .put("colors", new org.json.JSONArray(COLORS)).put("torques", new org.json.JSONArray(TORQUES))
                .put("price", new JSONObject().put("min", CustomerModel.PRICE_MIN).put("max", CustomerModel.PRICE_MAX).put("default", 100))
                .put("time_scale", FactoryConfig.SCALE)
                .put("deadline_ms", new JSONObject().put("min", MIN_DEADLINE_MS)
                        .put("max", MAX_DEADLINE_MS).put("default", DEFAULT_DEADLINE_MS))
                .put("focus_presets", new org.json.JSONArray(FOCUS_PRESETS))
                .put("api_key_required", API_KEY != null && !API_KEY.isEmpty());
    }

    // ------------------------------------------------------------------ POST
    private static void postOrder(HttpExchange ex) throws IOException {
        if (API_KEY != null && !API_KEY.isEmpty()) {
            String given = ex.getRequestHeaders().getFirst("X-Api-Key");
            if (given == null || !MessageDigest.isEqual(
                    given.getBytes(StandardCharsets.UTF_8), API_KEY.getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, err("invalid_api_key"));
                return;
            }
        }
        byte[] raw = readLimited(ex.getRequestBody(), MAX_BODY_BYTES);
        if (raw == null) { send(ex, 413, err("body_too_large")); return; }
        String body = new String(raw, StandardCharsets.UTF_8);
        String ctype = String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")).toLowerCase(Locale.ROOT);

        JSONObject in;
        try {
            if (ctype.startsWith("application/json")) in = new JSONObject(body);
            else if (ctype.startsWith("application/x-www-form-urlencoded")) in = fromForm(body);
            else { send(ex, 415, err("use_json_or_form_encoding")); return; }
        } catch (JSONException e) {
            send(ex, 400, err("malformed_json"));
            return;
        }

        WebOrder o = new WebOrder();
        String problem = validate(in, o);
        if (problem != null) { send(ex, 400, err(problem)); return; }

        o.ticket = "T" + Long.toHexString(RNG.nextLong() & 0xFFFFFFFFFFFFL);
        if (!PENDING.offer(o)) { send(ex, 429, err("factory_busy_try_later")); return; }
        TICKETS.put(o.ticket, new Ticket(o.ticket, o.customer));
        FactoryState.INSTANCE.appendEvent(new JSONObject()
                .put("event_type", "web_order_received").put("ticket", o.ticket).put("customer_name", o.customer));
        send(ex, 202, new JSONObject().put("ticket", o.ticket).put("status", "queued")
                .put("status_url", "/api/orders/" + o.ticket));
    }

    private static String validate(JSONObject in, WebOrder o) {
        String customer = in.optString("customer", "").trim();
        if (!CUSTOMER.matcher(customer).matches()) return "invalid_customer";
        o.customer = customer;

        o.front = normaliseColor(in.optString("front_color", ""));
        o.rear = normaliseColor(in.optString("rear_color", ""));
        if (o.front == null) return "invalid_front_color";
        if (o.rear == null) return "invalid_rear_color";

        Integer torque = asInt(in.opt("torque"));
        if (torque == null || !TORQUES.contains(torque)) return "invalid_torque";
        o.torque = torque;

        if (in.has("deadline_ms") && !in.isNull("deadline_ms") && !"".equals(in.opt("deadline_ms"))) {
            Integer d = asInt(in.opt("deadline_ms"));
            if (d == null || d < MIN_DEADLINE_MS || d > MAX_DEADLINE_MS) return "invalid_deadline_ms";
            o.deadlineMs = d;
        } else {
            o.deadlineMs = DEFAULT_DEADLINE_MS;
        }
        if (in.has("price") && !in.isNull("price") && !"".equals(in.opt("price"))) {
            double pr = num(in.opt("price"));
            if (Double.isNaN(pr) || pr < CustomerModel.PRICE_MIN || pr > CustomerModel.PRICE_MAX) return "invalid_price";
            o.price = pr;
        }
        return parseFocus(in, o);
    }

    /** focus: preset name, or focus_weights {time,energy,quality} (any non-negative numbers). */
    private static String parseFocus(JSONObject in, WebOrder o) {
        Object preset = in.opt("focus");
        Object weights = in.opt("focus_weights");
        if (weights instanceof JSONObject) {
            JSONObject w = (JSONObject) weights;
            double t = num(w.opt("time")), e = num(w.opt("energy")), q = num(w.opt("quality"));
            if (t < 0 || e < 0 || q < 0 || Double.isNaN(t + e + q) || t + e + q <= 0) return "invalid_focus_weights";
            o.wTime = t; o.wEnergy = e; o.wQuality = q;
        } else if (preset instanceof String && !((String) preset).isBlank()) {
            switch (((String) preset).toLowerCase(Locale.ROOT)) {
                case "balanced": o.wTime = 1; o.wEnergy = 1; o.wQuality = 1; break;
                case "time":     o.wTime = 6; o.wEnergy = 2; o.wQuality = 2; break;
                case "energy":   o.wTime = 2; o.wEnergy = 6; o.wQuality = 2; break;
                case "quality":  o.wTime = 2; o.wEnergy = 2; o.wQuality = 6; break;
                default: return "invalid_focus";
            }
        }
        return null;
    }

    private static double num(Object v) {
        if (v == null || v == JSONObject.NULL) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return d == Math.rint(d) ? (int) d : null;
        }
        if (v instanceof String) {
            try { return Integer.parseInt(((String) v).trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String normaliseColor(String c) {
        for (String known : COLORS) if (known.equalsIgnoreCase(c.trim())) return known;
        return null;
    }

    private static JSONObject fromForm(String body) {
        JSONObject out = new JSONObject();
        JSONObject weights = new JSONObject();
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            String k = URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            if (k.startsWith("weight_")) weights.put(k.substring(7), v); else out.put(k, v);
        }
        if (weights.length() > 0) out.put("focus_weights", weights);
        return out;
    }

    // ------------------------------------------------------------------ GET
    private static void getOrder(HttpExchange ex, String id) throws IOException {
        Ticket t = TICKETS.get(id);
        if (t == null) { send(ex, 404, err("unknown_ticket")); return; }
        JSONObject out = new JSONObject().put("ticket", t.id).put("customer", t.customer);
        if (t.rejected != null) {
            out.put("status", "rejected").put("reason", t.rejected);
        } else if (t.agent == null) {
            out.put("status", "queued");
        } else {
            out.put("status", "admitted").put("agent", t.agent);
            for (JSONObject order : FactoryState.INSTANCE.orders.values()) {
                if (t.agent.equals(order.optString("owner_agent", ""))) {
                    boolean done = !order.isNull("finished_at");
                    out.put("status", done ? "finished" : "in_production")
                       .put("order_id", order.getInt("id"))
                       .put("current_station", order.opt("current_station"))
                       .put("finished_at", order.opt("finished_at"));
                    break;
                }
            }
        }
        send(ex, 200, out);
    }

    // ------------------------------------------------------------------ helpers
    private static void cors(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.add("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
        h.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        h.add("Access-Control-Allow-Headers", "Content-Type, X-Api-Key");
        h.add("Vary", "Origin");
    }

    private static JSONObject err(String code) { return new JSONObject().put("error", code); }

    private static void send(HttpExchange ex, int status, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    /** Reads at most limit bytes; null if the body is larger. */
    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > limit) return null;
        }
        return buf.toByteArray();
    }
}
