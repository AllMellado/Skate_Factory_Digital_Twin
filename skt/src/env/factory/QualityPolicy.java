package skt;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;

public class QualityPolicy extends Artifact {

    void init() {
        defineObsProperty("policy", "max_duration_ms", 40000);
        // Threshold of norm n_energy_budget. Observable, so every agent that
        // focuses this artifact (quality_control, skateboards) sees the same value.
        defineObsProperty("energy_budget", 120);
        // Declared penalty per norm, in order: n_complete_assembly, n_max_duration,
        // n_content_correct, n_energy_budget. Set these to whatever "cost" each
        // sanction should carry -- bigger = worse, skateboards weigh it more.
        defineObsProperty("severity", 100, 100, 100, 100);
    }

    @OPERATION
    void setEnergyBudget(int newValue) {
        getObsProperty("energy_budget").updateValue(newValue);
        signal("policy_changed", "energy_budget", newValue);
        log("policy updated: energy_budget = " + newValue);
    }

    @OPERATION
    void setSeverity(String normId, int penalty) {
        int idx = switch (normId) {
            case "n_complete_assembly" -> 0;
            case "n_max_duration" -> 1;
            case "n_content_correct" -> 2;
            case "n_energy_budget" -> 3;
            default -> -1;
        };
        if (idx < 0) return;
        getObsProperty("severity").updateValue(idx, penalty);
        signal("policy_changed", "severity_" + normId, penalty);
    }

    @OPERATION
    void addSeverity(String normId, int delta) {
        int idx = switch (normId) {
            case "n_complete_assembly" -> 0;
            case "n_max_duration" -> 1;
            case "n_content_correct" -> 2;
            case "n_energy_budget" -> 3;
            default -> -1;
        };
        if (idx < 0) return;
        int updated = (int) getObsProperty("severity").getValue(idx) + delta;
        getObsProperty("severity").updateValue(idx, updated);
        signal("policy_changed", "severity_" + normId, updated);
    }

    @OPERATION
    void getPolicy(String key, OpFeedbackParam<Object> value) {
        value.set(getObsProperty("policy").getValue(1));
    }

    @OPERATION
    void setPolicy(String key, int newValue) {
        // Single-key policy set for now (max_duration_ms). Extend with more
        // defineObsProperty calls in init() if you add more tunable values,
        // and switch on `key` here to route to the right one.
        getObsProperty("policy").updateValue(1, newValue);
        signal("policy_changed", key, newValue);
        log("policy updated: " + key + " = " + newValue);
    }
}
