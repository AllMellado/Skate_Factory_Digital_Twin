package skt;

import cartago.Artifact;
import cartago.ObsProperty;
import cartago.OPERATION;
import org.json.JSONObject;

/**
 * Artifact "regulation": what the agents can see of the regulation in force, and the guarded operations that
 * change or apply it.
 *
 * Observable properties (agents that focus this artifact get them as beliefs):
 *   regulation(Version)                                   the version in force, e.g. "R1"
 *   fine(Norm, Amount)                                    what breaking Norm costs now (units of QualityPolicy.severity)
 *   compliance(Version, Norm, Inspected, Violations)      QC's verdicts so far under that version
 *   sanctions(Version, Norm, Applied, TotalFines)         sanctions applied for violations committed under that version
 * Regimentation is deliberately NOT an observable property: it is a technical constraint of the environment
 * (see Regulation.guardCompletion). An agent finds out about it the way it would in a real plant: by trying.
 *
 * Who may do what (OrgLedger permissions, i.e. Moise roles):
 *   amendRegulation            regulator
 *   recordInspection / applySanction   quality_inspector
 *   logDecision                the order_builder that owns the order (instrumentation of its own choices)
 */
public class RegulationBoard extends Artifact {

    void init(String r1File) throws Exception {
        Regulation reg = Regulation.INSTANCE;
        reg.load(r1File);
        defineObsProperty("regulation", reg.version());
        for (Regulation.Rule r : reg.rulesNow()) {
            defineObsProperty("fine", r.norm, r.fine);
            defineObsProperty("compliance", reg.version(), r.norm, 0, 0);
            defineObsProperty("sanctions", reg.version(), r.norm, 0, 0.0);
        }
        log("regulation " + reg.version() + " in force: " + reg.rulesNow().size() + " norm(s)");
    }

    @OPERATION
    void amendRegulation(String norm, double newFine, boolean regiment, String reason) {
        String who = getCurrentOpAgentId().getAgentName();
        String denied = OrgLedger.INSTANCE.check(who, OrgLedger.AMEND_REGULATION, -1, "RegulationBoard.amendRegulation");
        if (denied != null) { failed(denied); return; }
        Regulation reg = Regulation.INSTANCE;
        String from = reg.version();
        String to = reg.amend(who, norm, newFine, regiment, reason);
        if (to == null) { failed("unknown_norm:" + norm); return; }
        getObsProperty("regulation").updateValue(to);
        getObsPropertyByTemplate("fine", norm, null).updateValue(1, newFine);
        for (Regulation.Rule r : reg.rulesNow()) {
            defineObsProperty("compliance", to, r.norm, 0, 0);
            defineObsProperty("sanctions", to, r.norm, 0, 0.0);
        }
        signal("regulation_amended", from, to, norm, newFine, regiment);
        log("REGULATION " + from + " -> " + to + ": " + norm + " fine " + newFine + (regiment ? ", regimented" : "") + " (" + reason + ")");
    }

    @OPERATION
    void recordInspection(int orderId, String owner, String norm, boolean compliant) {
        String who = getCurrentOpAgentId().getAgentName();
        String denied = OrgLedger.INSTANCE.check(who, OrgLedger.APPLY_SANCTION, -1, "RegulationBoard.recordInspection");
        if (denied != null) { failed(denied); return; }
        Regulation reg = Regulation.INSTANCE;
        long[] s = reg.recordInspection(orderId, owner, norm, compliant, who);
        String v = reg.completedUnder(orderId);
        ObsProperty p = getObsPropertyByTemplate("compliance", v, norm, null, null);
        if (p != null) p.updateValues(v, norm, (int) s[0], (int) s[1]);
    }

    @OPERATION
    void applySanction(int orderId, String norm) {
        String who = getCurrentOpAgentId().getAgentName();
        String denied = OrgLedger.INSTANCE.check(who, OrgLedger.APPLY_SANCTION, -1, "RegulationBoard.applySanction");
        if (denied != null) { failed(denied); return; }
        JSONObject r = Regulation.INSTANCE.applySanction(orderId, norm, who);
        if (r == null) return;                                       // nothing to sanction (compliant, unknown or already done)
        String v = r.getString("version");
        ObsProperty p = getObsPropertyByTemplate("sanctions", v, norm, null, null);
        if (p != null) p.updateValues(v, norm, (int) r.getLong("count"), r.getDouble("total"));
        signal("sanction_applied", orderId, r.getString("agent"), norm, r.getDouble("fine"));
        log("SANCTION " + r.getString("agent") + " fined " + r.getDouble("fine") + " for " + norm + " (order " + orderId + ", committed under " + v + ")");
    }

    @OPERATION
    void logDecision(int orderId, String norm, String decision, double gain, double penalty) {
        String who = getCurrentOpAgentId().getAgentName();
        String denied = OrgLedger.INSTANCE.check(who, OrgLedger.START_ORDER, orderId, "RegulationBoard.logDecision");
        if (denied != null) { failed(denied); return; }
        Regulation.INSTANCE.logDecision(orderId, who, norm, decision, gain, penalty);
    }
}
