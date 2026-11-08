package skt;

import java.util.Set;

/** Public bridge so the guarded boards (package ora4mas.nopl) can use the package-private OrgLedger. */
public final class OrgGuard {
    private OrgGuard() {}


    /** Facts that describe organisational state: only org_manager may assert them. */
    private static final Set<String> ORG_FACTS = Set.of("play", "scheme_builder", "goal_done", "due");

    public static String checkFact(String agent, String fact, String op) {
        int i = fact.indexOf('(');
        String functor = (i < 0 ? fact : fact.substring(0, i)).trim();
        String perm = ORG_FACTS.contains(functor) ? OrgLedger.ASSERT_ORG_FACT : OrgLedger.ASSERT_NORM_FACT;
        return OrgLedger.INSTANCE.check(agent, perm, -1, "NormativeBoard." + op + "(" + functor + ")");
    }

    /** The group board tells the ledger how to ask Moise who plays what. */
    public static void registerRoleSource(RoleSource rs) { OrgLedger.INSTANCE.registerRoleSource(rs::plays); }
    public interface RoleSource { boolean plays(String agent, String role); }

    /** Before Moise's adoptRole: null if org_manager authorised this agent for this role and the authorisation is still valid. */
    public static String checkAdoption(String agent, String role) {
        String state = OrgLedger.INSTANCE.authorisationState(agent, role);
        if (state == null) return null;
        return OrgLedger.INSTANCE.deny(agent, "adopt_role", "GroupBoard.adoptRole(" + role + ")",
                "expired".equals(state) ? "authorisation_expired" : "not_authorised_by_org_manager");
    }

    /** Moise accepted the adoption: the authorisation is used up. */
    public static void confirmAdoption(String agent, String role) {
        OrgLedger.INSTANCE.withdraw(agent, role);
        OrgLedger.INSTANCE.log("role_adopted", agent, role);
    }
    public static void confirmLeft(String agent, String role, boolean evicted) {
        OrgLedger.INSTANCE.log(evicted ? "role_evicted" : "role_left", agent, role);
    }

    /**
     * Regimentation at the scheme board: the goal "build" of order_<id> cannot be declared achieved while the regulation in
     * force regiments n_complete_assembly and a station is missing. Any other goal passes.
     */
    public static String checkGoal(String agent, String scheme, String goal) {
        if (!"build".equals(goal)) return null;
        int id;
        try { id = Integer.parseInt(scheme.substring(scheme.indexOf('_') + 1)); } catch (RuntimeException e) { return null; }
        return Regulation.INSTANCE.guardCompletion(agent, id, "SchemeBoard.goalAchieved(build)");
    }

    public static String checkAdmin(String agent, String op) {
        return OrgLedger.INSTANCE.check(agent, OrgLedger.ASSIGN_ROLE, -1, op);
    }
    /** Mission commitment: mBuild needs order_builder and the owner of that order; mInspect needs quality_inspector. */
    public static String checkCommit(String agent, String scheme, String mission) {
        String where = "SchemeBoard.commitMission(" + scheme + "," + mission + ")";
        if ("mBuild".equals(mission)) {
            int id; try { id = Integer.parseInt(scheme.substring(scheme.indexOf('_') + 1)); } catch (RuntimeException e) { id = -1; }
            return OrgLedger.INSTANCE.check(agent, OrgLedger.START_ORDER, id, where);
        }
        if ("mInspect".equals(mission)) return OrgLedger.INSTANCE.check(agent, OrgLedger.ASSERT_NORM_FACT, -1, where);
        return OrgLedger.INSTANCE.deny(agent, "commit_mission", where, "unknown_mission");
    }
}
