package skt;

import cartago.Artifact;
import cartago.OPERATION;
import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class FinishedStorage extends Artifact {

    static final List<String> REQUIRED_WORK_CENTERS =
            Arrays.asList("Trunks", "Wheels", "Torque", "Optional");

    void init() { }

    @OPERATION
    void finishOrder(int orderId) {
        String denied = OrgLedger.INSTANCE.check(getCurrentOpAgentId().getAgentName(), OrgLedger.FINISH_ORDER, orderId,
                "FinishedStorage.finishOrder");
        if (denied != null) { failed(denied); return; }
        JSONObject order = FactoryState.INSTANCE.orders.get(orderId);
        if (order == null) { failed("no_such_order:" + orderId); return; }
        // regimentation: under a regulation that regiments n_complete_assembly an incomplete order cannot be completed
        String blocked = Regulation.INSTANCE.guardCompletion(getCurrentOpAgentId().getAgentName(), orderId, "FinishedStorage.finishOrder");
        if (blocked != null) { failed(blocked); return; }
        Regulation.INSTANCE.stampCompletion(orderId);

        String startTime = order.getString("created_at");
        String endTime = Instant.now().toString();
        order.put("station_status", "COMPLETED");
        order.put("finished_at", endTime);

        JSONObject summary = buildWorkOrderSummary(order, startTime, endTime);
        FactoryState.INSTANCE.appendWorkOrderSummary(summary);

        FactoryState.INSTANCE.appendEvent(new JSONObject()
                .put("event_type", "work_order_completed")
                .put("work_order_id", orderId)
                .put("cycle_time_ms", summary.getLong("cycle_time_ms")));

        JSONObject outcome = CustomerModel.evaluate(order, summary.getLong("cycle_time_ms"));
        order.put("outcome", outcome);
        CustomerModel.record(order.optString("client_name"), outcome.getBoolean("deadline_ok"), outcome.getBoolean("energy_ok"), outcome.getBoolean("content_ok"));
        signal("order_finished", orderId);
    }

    private JSONObject buildWorkOrderSummary(JSONObject order, String startTime, String endTime) {
        JSONArray logs = order.getJSONArray("production_logs");
        long start = Instant.parse(startTime).toEpochMilli();
        long end = Instant.parse(endTime).toEpochMilli();
        long cycleTimeMs = end - start;

        Set<String> visitedWorkCenters = new LinkedHashSet<>();
        int targetTorque = order.getInt("torque_setting");
        String frontWheelColor = order.getString("wheel_color_front");
        String rearWheelColor = order.getString("wheel_color_rear");

        boolean specificationMet = true, qualityChecksPassed = true;
        String firstWorkCenter = null;
        JSONArray operations = new JSONArray();

        for (int i = 0; i < logs.length(); i++) {
            JSONObject entry = logs.getJSONObject(i);
            String workCenter = entry.getString("station");
            if (firstWorkCenter == null && "IN_PROGRESS".equals(entry.getString("new_status"))) {
                firstWorkCenter = workCenter;
            }
            if (!"COMPLETED".equals(entry.getString("new_status"))) continue;

            visitedWorkCenters.add(workCenter);
            JSONObject payload = entry.optJSONObject("data_payload");
            if (payload == null) continue;

            if (!payload.optBoolean("requirement_met", true)) qualityChecksPassed = false;
            if (payload.has("torque_applied") && payload.getInt("torque_applied") != targetTorque) specificationMet = false;
            if (payload.has("color_applied")) {
                String expected = workCenter.equals(firstWorkCenter) ? frontWheelColor : rearWheelColor;
                if (!payload.getString("color_applied").equals(expected)) specificationMet = false;
            }

            JSONObject operation = new JSONObject();
            operation.put("work_center", workCenter);
            operation.put("actual_torque", payload.opt("torque_applied"));
            operation.put("actual_color", payload.opt("color_applied"));
            operation.put("quality_check_passed", payload.optBoolean("requirement_met", true));
            operation.put("timestamp", entry.getString("timestamp"));
            operations.put(operation);
        }

        boolean routingComplete = visitedWorkCenters.containsAll(REQUIRED_WORK_CENTERS);
        int targetCycleTimeMs = FactoryState.INSTANCE.maxDurationMs;
        boolean highPriorityOrder = targetTorque > 60;

        JSONObject summary = new JSONObject();
        summary.put("work_order_id", order.getInt("id"));
        summary.put("customer_name", order.getString("client_name"));
        summary.put("front_wheel_color", frontWheelColor);
        summary.put("rear_wheel_color", rearWheelColor);
        summary.put("target_torque", targetTorque);
        summary.put("start_time", startTime);
        summary.put("end_time", endTime);
        summary.put("cycle_time_ms", cycleTimeMs);
        summary.put("work_centers_visited", new JSONArray(visitedWorkCenters));
        summary.put("required_work_centers", new JSONArray(REQUIRED_WORK_CENTERS));
        summary.put("high_priority_order", highPriorityOrder);
        summary.put("quality_compliance", new JSONObject()
                .put("routing_complete", routingComplete)
                .put("cycle_time_within_target", cycleTimeMs <= targetCycleTimeMs)
                .put("cycle_time_within_expedited_target", cycleTimeMs <= 5000)
                .put("specification_met", specificationMet)
                .put("quality_checks_passed", qualityChecksPassed)
                .put("target_cycle_time_ms", targetCycleTimeMs));
        summary.put("operations", operations);
        return summary;
    }
}