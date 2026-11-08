package skt;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;
import org.json.JSONObject;

/**
 * Authorisation ledger facade. Only org_manager can authorise/revoke roles; a role
 * becomes effective when the agent adopts it in the Moise group (SecureGroupBoard).
 * Anyone may ask whether an agent has a role.
 */
public class FactoryOrg extends Artifact {

    void init(String managerName) {
        OrgLedger.INSTANCE.bootstrap(managerName);
        defineObsProperty("org_ready", false);
        log("organisation created, manager = " + managerName);
    }

    private String caller() { return getCurrentOpAgentId().getAgentName(); }

    /** Manager says: this agent may adopt this role. The agent must then adopt it itself (Moise adoptRole). */
    @OPERATION
    void authoriseRole(String agent, String role) {
        String denied = OrgLedger.INSTANCE.check(caller(), OrgLedger.ASSIGN_ROLE, -1, "FactoryOrg.authoriseRole");
        if (denied != null) { failed(denied); return; }
        if (!OrgLedger.isAssignable(role)) { failed("role_not_assignable:" + role); return; }
        OrgLedger.INSTANCE.authorise(agent, role);
    }

    /** Takes the role (and any pending authorisation) away at once; the agent is then asked to leave it in Moise. */
    @OPERATION
    void revokeRole(String agent, String role) {
        String denied = OrgLedger.INSTANCE.check(caller(), OrgLedger.ASSIGN_ROLE, -1, "FactoryOrg.revokeRole");
        if (denied != null) { failed(denied); return; }
        OrgLedger.INSTANCE.withdraw(agent, role);   // pending authorisation only; the role itself is Moise's (see evictRole)
        OrgLedger.INSTANCE.log("role_revoked", agent, role);
    }

    @OPERATION
    void hasRole(String agent, String role, OpFeedbackParam<Boolean> out) {
        out.set(OrgLedger.INSTANCE.hasRole(agent, role));
    }

    /** Which agent owns an order (the skateboard agent that built it), "" if unknown. */
    @OPERATION
    void orderOwner(int orderId, OpFeedbackParam<String> out) {
        JSONObject o = FactoryState.INSTANCE.orders.get(orderId);
        out.set(o == null ? "" : o.optString("owner_agent", ""));
    }

    /** The manager flips this once the bootstrap roles are in place. */
    @OPERATION
    void markReady() {
        String denied = OrgLedger.INSTANCE.check(caller(), OrgLedger.ASSIGN_ROLE, -1, "FactoryOrg.markReady");
        if (denied != null) { failed(denied); return; }
        OrgLedger.INSTANCE.endBootstrap();
        getObsProperty("org_ready").updateValue(true);
    }

    /** Manager: record that an obligation of an agent was fulfilled or violated (builders and inspectors alike). */
    @OPERATION
    void recordObligation(String agent, String aim, String status) {
        String denied = OrgLedger.INSTANCE.check(caller(), OrgLedger.ASSIGN_ROLE, -1, "FactoryOrg.recordObligation");
        if (denied != null) { failed(denied); return; }
        OrgLedger.INSTANCE.log("obligation_" + status, agent, aim);
    }

    /** Drops expired authorisations (they are also dropped lazily when used). */
    @OPERATION
    void sweepAuthorisations(OpFeedbackParam<Integer> expired) {
        String denied = OrgLedger.INSTANCE.check(caller(), OrgLedger.ASSIGN_ROLE, -1, "FactoryOrg.sweepAuthorisations");
        if (denied != null) { failed(denied); return; }
        expired.set(OrgLedger.INSTANCE.sweep());
    }

    /** True when the audit trail replays to exactly the role state Moise reports. */
    @OPERATION
    void auditConsistent(OpFeedbackParam<Boolean> ok) { ok.set(OrgLedger.INSTANCE.audit().getBoolean("consistent")); }
}
