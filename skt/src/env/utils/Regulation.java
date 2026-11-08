package skt;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The factory's regulation as a versioned object (R1, R2, ...).
 *
 * A regulation says, per norm: what breaking it costs (a fine, in the same units as QualityPolicy's
 * severity, so a skateboard can weigh it against its own deadline/energy/content risks) and whether the norm
 * is REGIMENTED, i.e. whether the environment makes the violating action impossible instead of merely
 * punishing it afterwards.
 *
 *   sanction   : the agent may break the norm; QC detects it and the fine is applied later (see RegulationBoard)
 *   regimented : guardCompletion() refuses the action, no matter what the agent wants (FinishedStorage.finishOrder
 *                and the scheme board's goalAchieved(build) call it)
 *
 * R1 is read from an XML file in the Gavel regulative-spec style; every amendment is written next to the
 * other archives as XML too, so each version can be read and diffed as a document.
 */
final class Regulation {

    static final Regulation INSTANCE = new Regulation();
    private Regulation() {}

    static final String NORM_ASSEMBLY = "n_complete_assembly";
    private static final Path ARCHIVE_DIR = Paths.get("factory-archive");
    private static final String SKT_NS = "https://skt.local/regimentation";

    /** What one version says about one norm. */
    static final class Rule {
        final String norm, sanctionId, issuer, content;
        final double fine;
        final boolean regimented;
        Rule(String norm, String sanctionId, String issuer, String content, double fine, boolean regimented) {
            this.norm = norm; this.sanctionId = sanctionId; this.issuer = issuer; this.content = content;
            this.fine = fine; this.regimented = regimented;
        }
        Rule with(double newFine, boolean newRegimented) { return new Rule(norm, sanctionId, issuer, content, newFine, newRegimented); }
    }

    private final Object lock = new Object();
    private volatile String version = "R0";
    private final Map<String, Rule> rules = new LinkedHashMap<>();
    private final List<JSONObject> history = new CopyOnWriteArrayList<>();      // one entry per version
    private final List<JSONObject> events = new CopyOnWriteArrayList<>();       // everything that happened, in order
    private final Map<Integer, JSONObject> inspections = new ConcurrentHashMap<>();   // order -> what QC found
    private final Map<String, long[]> stats = new ConcurrentHashMap<>();        // version|norm -> inspected, violations, sanctions, blocked
    private final Map<String, Double> fined = new ConcurrentHashMap<>();        // version|norm -> total fines
    private final Set<Integer> sanctionedOrders = ConcurrentHashMap.newKeySet();
    private final Map<Integer, String> completedUnder = new ConcurrentHashMap<>();  // order -> version in force when it was completed

    // ---- reading -------------------------------------------------------------------------------

    String version() { return version; }

    Rule rule(String norm) { synchronized (lock) { return rules.get(norm); } }

    List<Rule> rulesNow() { synchronized (lock) { return new ArrayList<>(rules.values()); } }

    /** Loads the first regulation (R1) from its XML document. */
    void load(String file) throws Exception {
        var dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document doc = dbf.newDocumentBuilder().parse(new File(file));
        Element root = doc.getDocumentElement();
        String id = root.getAttribute("id");
        Map<String, String> issuer = new LinkedHashMap<>(), content = new LinkedHashMap<>();
        for (Element n : elements(doc, "norm")) {
            issuer.put(n.getAttribute("id"), text(n, "issuer"));
            content.put(n.getAttribute("id"), text(n, "content"));
        }
        Map<String, Double> amount = new LinkedHashMap<>();
        Pattern fine = Pattern.compile("fine\\(\\s*\\w+\\s*,\\s*([0-9.]+)\\s*\\)");
        for (Element s : elements(doc, "sanction")) {
            Matcher m = fine.matcher(text(s, "content"));
            if (m.find()) amount.put(s.getAttribute("id"), Double.parseDouble(m.group(1)));
        }
        Set<String> regimented = new LinkedHashSet<>();
        for (Element r : elements(doc, "regimentation")) regimented.add(r.getAttribute("norm"));
        synchronized (lock) {
            completedUnder.clear(); rules.clear(); history.clear(); events.clear(); inspections.clear(); stats.clear(); fined.clear(); sanctionedOrders.clear();
            for (Element l : elements(doc, "ns-link")) {
                String norm = text(l, "nid"), sid = text(l, "sid");
                if (!amount.containsKey(sid)) continue;
                rules.put(norm, new Rule(norm, sid, issuer.getOrDefault(norm, ""), content.getOrDefault(norm, ""),
                        amount.get(sid), regimented.contains(norm)));
            }
            version = id.isEmpty() ? "R1" : id;
            snapshot("loaded from " + file, "startup");
            writeXml(version);
        }
    }

    // ---- amendment -------------------------------------------------------------------------------

    /** Derives the next version from the current one: new fine and/or regimentation for one norm. Returns the new version's name. */
    String amend(String by, String norm, double newFine, boolean regiment, String reason) {
        synchronized (lock) {
            Rule old = rules.get(norm);
            if (old == null) return null;
            String previous = version;
            version = "R" + (history.size() + 1);
            rules.put(norm, old.with(newFine, regiment));
            JSONObject h = snapshot(reason, by);
            h.put("amended_from", previous).put("norm", norm)
                    .put("fine_before", old.fine).put("fine_after", newFine)
                    .put("regimented_before", old.regimented).put("regimented_after", regiment);
            event("regulation_amended", new JSONObject().put("by", by).put("from", previous).put("to", version).put("norm", norm)
                    .put("fine_before", old.fine).put("fine_after", newFine).put("regimented_after", regiment).put("reason", reason));
            writeXml(version);
            return version;
        }
    }

    private JSONObject snapshot(String reason, String by) {
        JSONObject rs = new JSONObject();
        for (Rule r : rules.values()) rs.put(r.norm, new JSONObject().put("fine", r.fine).put("regimented", r.regimented).put("sanction", r.sanctionId));
        JSONObject h = new JSONObject().put("version", version).put("since_ms", System.currentTimeMillis())
                .put("since", Instant.now().toString()).put("by", by).put("reason", reason).put("rules", rs);
        history.add(h);
        return h;
    }

    // ---- regimentation ---------------------------------------------------------------------------

    /**
     * Gate used by the artifacts that complete an order. Returns null when the completion is allowed, otherwise the
     * reason. It only bites when the norm is regimented in the version in force: under R1 it always lets the
     * agent through (and QC will notice afterwards), under R2 an incomplete assembly cannot be completed.
     */
    String guardCompletion(String agent, int orderId, String where) {
        Rule r;
        synchronized (lock) { r = rules.get(NORM_ASSEMBLY); }
        if (r == null || !r.regimented) return null;
        Set<String> missing = missingStations(orderId);
        if (missing.isEmpty()) return null;
        String v = version;
        stats.computeIfAbsent(v + "|" + NORM_ASSEMBLY, k -> new long[4])[3]++;
        event("regimentation_blocked", new JSONObject().put("agent", agent).put("order", orderId).put("norm", NORM_ASSEMBLY)
                .put("version", v).put("where", where).put("missing", new JSONArray(missing)));
        return "regimented:" + NORM_ASSEMBLY + ":missing=" + String.join(",", missing);
    }

    /** Called by FinishedStorage when an order is completed: the violation (if any) belongs to this version. */
    void stampCompletion(int orderId) { completedUnder.put(orderId, version); }

    String completedUnder(int orderId) { return completedUnder.getOrDefault(orderId, version); }

    /** Stations of the required list with no COMPLETED pass in the order's production log (ground truth, not what the agent says). */
    static Set<String> missingStations(int orderId) {
        Set<String> missing = new LinkedHashSet<>(FinishedStorage.REQUIRED_WORK_CENTERS);
        JSONObject order = FactoryState.INSTANCE.orders.get(orderId);
        if (order == null) return missing;
        JSONArray logs = order.getJSONArray("production_logs");
        for (int i = 0; i < logs.length(); i++) {
            JSONObject e = logs.getJSONObject(i);
            if ("COMPLETED".equals(e.optString("new_status"))) missing.remove(e.optString("station"));
        }
        return missing;
    }

    // ---- inspection, sanction, decisions (called through RegulationBoard) ---------------------------

    /** QC's verdict on one finished order. Returns {inspected, violations} of the version in force. */
    long[] recordInspection(int orderId, String owner, String norm, boolean compliant, String by) {
        String v = completedUnder.getOrDefault(orderId, version);   // the version in force when the agent acted, not when QC got to it
        long[] s = stats.computeIfAbsent(v + "|" + norm, k -> new long[4]);
        synchronized (s) {
            s[0]++;
            if (!compliant) s[1]++;
            inspections.put(orderId, new JSONObject().put("order", orderId).put("owner", owner).put("norm", norm)
                    .put("version", v).put("compliant", compliant));
            event("inspection", new JSONObject().put("order", orderId).put("agent", owner).put("norm", norm)
                    .put("version", v).put("compliant", compliant).put("by", by));
            return new long[]{s[0], s[1]};
        }
    }

    /**
     * The sanction for a violation, applied when the obligation lapses. The fine is the one of the version in force
     * when the violation was committed (no retroactive escalation). Returns {fine, count, total} or null.
     */
    JSONObject applySanction(int orderId, String norm, String by) {
        JSONObject insp = inspections.get(orderId);
        if (insp == null || insp.getBoolean("compliant") || !norm.equals(insp.getString("norm"))) return null;
        if (!sanctionedOrders.add(orderId)) return null;                   // once per order
        String v = insp.getString("version");
        double amount = fineOf(v, norm);
        long[] s = stats.computeIfAbsent(v + "|" + norm, k -> new long[4]);
        double total = fined.merge(v + "|" + norm, amount, Double::sum);
        long count;
        synchronized (s) { count = ++s[2]; }
        event("sanction_applied", new JSONObject().put("order", orderId).put("agent", insp.getString("owner")).put("norm", norm)
                .put("committed_under", v).put("applied_under", version).put("fine", amount).put("by", by));
        return new JSONObject().put("version", v).put("fine", amount).put("count", count).put("total", total)
                .put("agent", insp.getString("owner"));
    }

    private double fineOf(String v, String norm) {
        for (JSONObject h : history) if (v.equals(h.getString("version"))) return h.getJSONObject("rules").getJSONObject(norm).getDouble("fine");
        return 0;
    }

    long[] statsOf(String v, String norm) { return stats.computeIfAbsent(v + "|" + norm, k -> new long[4]).clone(); }
    double finedOf(String v, String norm) { return fined.getOrDefault(v + "|" + norm, 0.0); }

    void logDecision(int orderId, String agent, String norm, String decision, double gain, double penalty) {
        event("decision", new JSONObject().put("order", orderId).put("agent", agent).put("norm", norm).put("version", version)
                .put("decision", decision).put("gain", Math.round(gain * 10) / 10.0).put("penalty", penalty));
    }

    // ---- audit ---------------------------------------------------------------------------------------

    void event(String type, JSONObject e) {
        e.put("event_type", type).put("t", System.currentTimeMillis()).put("seq", events.size() + 1);
        events.add(e);
        FactoryState.INSTANCE.appendEvent(new JSONObject(e.toString()));
    }

    JSONObject toJson() {
        JSONObject out = new JSONObject();
        out.put("version", version);
        out.put("history", new JSONArray(history));
        JSONArray st = new JSONArray();
        new java.util.TreeMap<>(stats).forEach((k, s) -> {
            String[] vn = k.split("\\|");
            st.put(new JSONObject().put("version", vn[0]).put("norm", vn[1]).put("inspected", s[0]).put("violations", s[1])
                    .put("sanctions", s[2]).put("blocked_attempts", s[3]).put("fines_total", fined.getOrDefault(k, 0.0)));
        });
        out.put("stats", st);
        out.put("events", new JSONArray(events));
        return out;
    }

    // ---- XML (Gavel regulative-spec style) -----------------------------------------------------------

    private void writeXml(String v) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!-- ").append(v).append(", written by the regulation board when it was enacted -->\n");
        sb.append("<regulative-spec id=\"").append(v).append("\" xmlns=\"https://github.com/gavelproject/gavel\" xmlns:skt=\"")
                .append(SKT_NS).append("\">\n  <norms>\n");
        for (Rule r : rules.values())
            sb.append("    <norm id=\"").append(r.norm).append("\">\n      <issuer>").append(esc(r.issuer)).append("</issuer>\n      <content>")
                    .append(esc(r.content)).append("</content>\n    </norm>\n");
        sb.append("  </norms>\n  <sanctions>\n");
        for (Rule r : rules.values())
            sb.append("    <sanction id=\"").append(r.sanctionId).append("\">\n      <content>fine(Target,").append(trim(r.fine)).append(")</content>\n    </sanction>\n");
        sb.append("  </sanctions>\n  <ns-links>\n");
        for (Rule r : rules.values())
            sb.append("    <ns-link><nid>").append(r.norm).append("</nid><sid>").append(r.sanctionId).append("</sid></ns-link>\n");
        sb.append("  </ns-links>\n  <skt:regimentations>\n");
        for (Rule r : rules.values())
            if (r.regimented) sb.append("    <skt:regimentation norm=\"").append(r.norm).append("\" guard=\"all_stations_visited\"/>\n");
        sb.append("  </skt:regimentations>\n</regulative-spec>\n");
        try {
            Files.createDirectories(ARCHIVE_DIR);
            Files.writeString(ARCHIVE_DIR.resolve("regulation-" + v + ".xml"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) { System.err.println("regulation_archive_failed: " + e.getMessage()); }
    }

    private static String trim(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }
    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static List<Element> elements(Document doc, String local) {
        List<Element> out = new ArrayList<>();
        NodeList nl = doc.getElementsByTagNameNS("*", local);
        for (int i = 0; i < nl.getLength(); i++) if (nl.item(i).getNodeType() == Node.ELEMENT_NODE) out.add((Element) nl.item(i));
        return out;
    }
    private static String text(Element parent, String local) {
        NodeList nl = parent.getElementsByTagNameNS("*", local);
        return nl.getLength() == 0 ? "" : nl.item(0).getTextContent().trim();
    }
}
