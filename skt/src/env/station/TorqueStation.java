package skt;

import org.json.JSONObject;

public class TorqueStation extends AbstractWorkstationArtifact {

    @Override protected double defaultEnergyCost() { return 20.0; }

    void init(String instanceId, int capacity) {
        setup(instanceId, capacity);
    }

    @Override
    protected String stationName() {
        return "Torque";
    }

    @Override
    protected int defaultMinMs() {
        return 2000;
    }

    @Override
    protected int defaultMaxMs() {
        return 6000;
    }

    @Override
    protected JSONObject computeResult(JSONObject order, boolean isFirst, boolean fast) {
        boolean fault = rnd.nextDouble() < effectiveFault(fast);
        JSONObject payload = new JSONObject();
        int expectedTorque = order.getInt("torque_setting");
        String expectedColor = isFirst ? order.getString("wheel_color_front") : order.getString("wheel_color_rear");
        payload.put("color_applied", expectedColor);
        payload.put("torque_applied", fault ? expectedTorque + torqueError() : expectedTorque);
        payload.put("requirement_met", computeRequirementMet());
        return payload;
    }
}