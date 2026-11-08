package skt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Permission and authorisation side of the factory organisation.
 *
 * Single source of truth, one fact one place:
 *   - WHO PLAYS WHICH ROLE            -> the Moise group (read live through a RoleSource; never copied here)
 *   - WHAT A ROLE MAY DO              -> the permission table below
 *   - WHO MAY ADOPT WHICH ROLE (now)  -> pending authorisations issued by org_manager, each with an expiry
 *   - WHAT HAPPENED                   -> an append-only audit trail; replaying it must give the live Moise state
 *
 * Because role membership is not duplicated, it cannot drift from Moise; audit() proves that by replay.
 */
final class OrgLedger {

    static final OrgLedger INSTANCE = new OrgLedger();
    private OrgLedger() {}

    // ---- Roles ------------------------------------------------------------
    static final String ORG_MANAGER       = "org_manager";
    static final String INTAKE_CLERK      = "intake_clerk";
    static final String ORDER_BUILDER     = "order_builder";
    static final String QUALITY_INSPECTOR = "quality_inspector";
    static final String REGULATOR         = "regulator";

    // ---- Permissions ------------------------------------------------------
    static final String ASSIGN_ROLE       = "assign_role";
    static final String REQUEST_ADMISSION = "request_admission";
    static final String TAKE_WEB_ORDER    = "take_web_order";
    static final String START_ORDER       = "start_order";
    static final String ENTER_STATION     = "enter_station";
    static final String EXIT_STATION      = "exit_station";
    static final String FINISH_ORDER      = "finish_order";
    static final String ASSERT_NORM_FACT  = "assert_norm_fact";
    static final String ASSERT_ORG_FACT   = "assert_org_fact";
    static final String COMMIT_MISSION    = "commit_mission";
    static final String AMEND_REGULATION  = "amend_regulation";
    static final String APPLY_SANCTION    = "apply_sanction";

    private static final Map<String, Set<String>> PERMISSIONS = Map.of(
            ORG_MANAGER,       Set.of(ASSIGN_ROLE, ASSERT_ORG_FACT),
            INTAKE_CLERK,      Set.of(REQUEST_ADMISSION, TAKE_WEB_ORDER),
            ORDER_BUILDER,     Set.of(START_ORDER, ENTER_STATION, EXIT_STATION, FINISH_ORDER, COMMIT_MISSION),
            QUALITY_INSPECTOR, Set.of(ASSERT_NORM_FACT, COMMIT_MISSION, APPLY_SANCTION),
            REGULATOR,         Set.of(AMEND_REGULATION));

    static boolean isAssignable(String role) { return PERMISSIONS.containsKey(role) && !ORG_MANAGER.equals(role); }

    // ---- Role state lives in Moise -------------------------------------------
    interface RoleSource { boolean plays(String agent, String role); }
    private volatile RoleSource roles = (a, r) -> false;
    void registerRoleSource(RoleSource rs) { roles = rs; }

    /** Authorisation lifetime [ms, real time]. */
    static final long AUTH_TTL_MS = Long.getLong("skt.authttl", 15_000L);

    private final Map<String, Long> authorised = new ConcurrentHashMap<>();   // "agent|role" -> expiry (epoch ms)
    private final List<JSONObject> trail = new CopyOnWriteArrayList<>();      // append-only
    private final List<JSONObject> denials = new CopyOnWriteArrayList<>();
    private volatile String manager = null;
    private volatile boolean bootstrapping = true;

    synchronized void bootstrap(String managerName) {
        authorised.clear(); trail.clear(); denials.clear();
        manager = managerName; bootstrapping = true;
        // the manager adopts its own role like everyone else; this one authorisation never expires (trust root)
        authorised.put(managerName + "|" + ORG_MANAGER, Long.MAX_VALUE);
        log("role_authorised", managerName, ORG_MANAGER);
    }
    String manager() { return manager; }
    void endBootstrap() { bootstrapping = false; }

    /** The bootstrap manager may build the organisation before it holds the Moise role. */
    boolean isBootstrapManager(String agent) { return bootstrapping && agent != null && agent.equals(manager); }

    // ---- audit trail ---------------------------------------------------------
    void log(String type, String agent, String role) {
        JSONObject e = new JSONObject().put("seq", trail.size() + 1).put("t", System.currentTimeMillis())
                .put("type", type).put("agent", agent).put("role", role);
        trail.add(e);
        FactoryState.INSTANCE.appendEvent(new JSONObject(e.toString()).put("event_type", type));
    }

    // ---- authorisations (with expiry) ------------------------------------------
    void authorise(String agent, String role) {
        authorised.put(agent + "|" + role, System.currentTimeMillis() + AUTH_TTL_MS);
        log("role_authorised", agent, role);
    }
    void withdraw(String agent, String role) { authorised.remove(agent + "|" + role); }

    /** null = valid; otherwise "missing" or "expired". */
    String authorisationState(String agent, String role) {
        Long exp = authorised.get(agent + "|" + role);
        if (exp == null) return "missing";
        if (System.currentTimeMillis() > exp) {
            if (authorised.remove(agent + "|" + role, exp)) log("authorisation_expired", agent, role);
            return "expired";
        }
        return null;
    }
    boolean isAuthorised(String agent, String role) { return authorisationState(agent, role) == null; }

    /** Drops expired authorisations and records them; returns how many. */
    int sweep() {
        int n = 0;
        for (String k : new ArrayList<>(authorised.keySet())) {
            String[] ar = k.split("\\|");
            if ("expired".equals(authorisationState(ar[0], ar[1]))) n++;
        }
        return n;
    }

    // ---- reading roles from Moise ---------------------------------------------
    boolean hasRole(String agent, String role) { return agent != null && roles.plays(agent, role); }
    Set<String> rolesOf(String agent) {
        Set<String> r = new TreeSet<>();
        for (String role : PERMISSIONS.keySet()) if (hasRole(agent, role)) r.add(role);
        return r;
    }
    boolean can(String agent, String permission) {
        for (String role : rolesOf(agent)) if (PERMISSIONS.get(role).contains(permission)) return true;
        return false;
    }
    static String roleFor(String permission) {
        for (Map.Entry<String, Set<String>> e : PERMISSIONS.entrySet()) if (e.getValue().contains(permission)) return e.getKey();
        return "unknown";
    }

    /** Gate used by artifacts: null when allowed, otherwise the failure reason (denial is audited). */
    String check(String agent, String permission, int orderId, String where) {
        boolean boot = ASSIGN_ROLE.equals(permission) && isBootstrapManager(agent);
        if (!boot && (agent == null || !can(agent, permission))) return deny(agent, permission, where, "role_required:" + roleFor(permission));
        if (orderId >= 0) {
            JSONObject order = FactoryState.INSTANCE.orders.get(orderId);
            String owner = order == null ? null : order.optString("owner_agent", null);
            if (owner == null || !owner.equals(agent)) return deny(agent, permission, where, "not_order_owner:" + orderId);
        }
        return null;
    }

    String deny(String agent, String permission, String where, String reason) {
        JSONObject ev = new JSONObject().put("event_type", "access_denied").put("agent", String.valueOf(agent))
                .put("permission", permission).put("where", where).put("reason", reason);
        denials.add(ev);
        if (denials.size() > 200) denials.remove(0);
        FactoryState.INSTANCE.appendEvent(new JSONObject(ev.toString()));
        System.err.println("[org] DENIED " + agent + " -> " + permission + " @" + where + " (" + reason + ")");
        return reason;
    }

    // ---- audit: replay the trail and compare with Moise -------------------------
    /** Roles implied by the trail: adoptions minus departures. */
    Map<String, Set<String>> replayedRoles() {
        Map<String, Set<String>> m = new TreeMap<>();
        for (JSONObject e : trail) {
            String t = e.getString("type"), a = e.getString("agent"), r = e.getString("role");
            if ("role_adopted".equals(t)) m.computeIfAbsent(a, k -> new TreeSet<>()).add(r);
            else if ("role_left".equals(t) || "role_evicted".equals(t)) { Set<String> s = m.get(a); if (s != null) s.remove(r); }
        }
        return m;
    }

    JSONObject audit() {
        Map<String, Set<String>> replay = replayedRoles();
        JSONArray mismatches = new JSONArray();
        int checked = 0;
        for (Map.Entry<String, Set<String>> e : replay.entrySet())
            for (String r : PERMISSIONS.keySet()) {
                boolean inTrail = e.getValue().contains(r), inMoise = hasRole(e.getKey(), r);
                checked++;
                if (inTrail != inMoise) mismatches.put(new JSONObject().put("agent", e.getKey()).put("role", r).put("trail", inTrail).put("moise", inMoise));
            }
        return new JSONObject().put("agents_checked", replay.size()).put("pairs_checked", checked)
                .put("mismatches", mismatches).put("consistent", mismatches.length() == 0).put("trail_events", trail.size());
    }

    JSONObject toJson() {
        JSONObject out = new JSONObject();
        JSONObject r = new JSONObject();
        new TreeMap<>(replayedRoles()).forEach((a, rs) -> { Set<String> live = rolesOf(a); if (!live.isEmpty()) r.put(a, new JSONArray(live)); });
        out.put("manager", manager == null ? JSONObject.NULL : manager);
        out.put("roles_from_moise", r);
        JSONObject perms = new JSONObject();
        new TreeMap<>(PERMISSIONS).forEach((role, ps) -> perms.put(role, new JSONArray(new TreeSet<>(ps))));
        out.put("permissions", perms);
        JSONArray pending = new JSONArray();
        long now = System.currentTimeMillis();
        authorised.forEach((k, exp) -> pending.put(new JSONObject().put("authorisation", k).put("expires_in_ms", exp - now)));
        out.put("authorisations_pending", pending);
        out.put("audit", audit());
        out.put("recent_denials", new JSONArray(denials));
        out.put("customer_model", CustomerModel.snapshot());
        return out;
    }
}
