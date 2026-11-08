package skt;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;

/** Agent-facing access to the customer model. Only intake clerks may ask for a profile. */
public class ProfileAdvisor extends Artifact {
    void init() { log("profile advisor ready, mode = " + CustomerModel.MODE); }

    /** Focus weights for a new order (all zero: no advice) and the order's energy budget. */
    @OPERATION
    void advise(String customer, double price, int deadlineMs,
                OpFeedbackParam<Double> wTime, OpFeedbackParam<Double> wEnergy, OpFeedbackParam<Double> wQuality,
                OpFeedbackParam<Double> energyBudget) {
        String denied = OrgLedger.INSTANCE.check(getCurrentOpAgentId().getAgentName(), OrgLedger.TAKE_WEB_ORDER, -1, "ProfileAdvisor.advise");
        if (denied != null) { failed(denied); return; }
        double[] w = CustomerModel.weights(customer, price, deadlineMs);
        wTime.set(w[0]); wEnergy.set(w[1]); wQuality.set(w[2]);
        energyBudget.set(CustomerModel.energyBudget(price));
    }
}
