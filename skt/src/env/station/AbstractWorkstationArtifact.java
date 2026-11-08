package skt;

import cartago.Artifact;
import cartago.INTERNAL_OPERATION;
import cartago.OPERATION;
import cartago.OpFeedbackParam;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public abstract class AbstractWorkstationArtifact extends Artifact {

    protected static final String[] COLORS = {"White", "Blue", "Green", "Orange"};
    protected volatile Random rnd = new Random();

    /** Seeded runs: draw from a source that depends only on (purpose, station, order, pass), not on thread timing. */
    protected void reseed(String purpose, int orderId) {
        if (!FactoryConfig.SEEDED) return;
        JSONObject o = FactoryState.INSTANCE.orders.get(orderId);
        int pass = o == null ? 0 : o.getJSONArray("production_logs").length();
        rnd = FactoryConfig.rng(purpose, instanceLabel(), o == null ? orderId : o.optString("order_ref", String.valueOf(orderId)), pass);
    }

    private String instanceId;
    private int capacity;
    protected volatile int minMs;
    protected volatile int maxMs;
    protected volatile double faultProbability;
    protected volatile double requirementPassRate;
    protected volatile int enterDwellMs;
    protected volatile int exitDwellMs;

    protected volatile boolean degraded = false;
    protected volatile int degradedOpsRemaining = 0;

    // Degradation has three levels (1 mild, 2 moderate, 3 severe) and hurts the station in several ways at once:
    // more faults, bigger errors when a fault happens, a lower chance of meeting the station's requirement,
    // slower processing and more energy per pass. Wear (passes since the last recovery) makes the next
    // degradation more likely; an episode ends after a few passes and the station recovers.
    protected volatile int degradationLevel = 0;   // 0 = healthy
    protected volatile double wear = 0;
    protected static final double[] DEG_FAULT  = {1.0, 1.5, 2.2, 3.0};
    protected static final double[] DEG_TIME   = {1.0, 1.25, 1.6, 2.2};
    protected static final double[] DEG_ENERGY = {1.0, 1.25, 1.6, 2.2};
    protected static final double[] DEG_ERROR  = {1.0, 1.4, 2.0, 3.0}; 
    protected static final double PASS_RATE_PENALTY_PER_LEVEL = 0.06;
    protected static final double WEAR_PER_PASS = 0.006, MAX_DEGRADATION_CHANCE = 0.35;

    /** Size of a wrong torque value (5..14, larger while degraded). */
    protected int torqueError() { return (int) Math.round((5 + rnd.nextInt(10)) * DEG_ERROR[degradationLevel]); }
    protected static final double FAST_FAULT_MULTIPLIER = 1.6;

    /** Speed-energy trade-off: fast mode draws more power per pass, slow mode less. */
    protected static final double FAST_ENERGY_MULTIPLIER = 1.7;
    protected static final double SLOW_ENERGY_MULTIPLIER = 0.8;

    private final Map<String, String> tablePhase = new ConcurrentHashMap<>();
    private final BlockingQueue<StationRequest> queue = new LinkedBlockingQueue<>();
    private final Map<String, Integer> tableOccupant = new ConcurrentHashMap<>();
    private final Map<Integer, BlockingQueue<Boolean>> exitSignals = new ConcurrentHashMap<>();
    private final List<Thread> workerThreads = new ArrayList<>();
    private final Map<Integer, Long> enqueuedAt = new ConcurrentHashMap<>();
    private final Map<String, Boolean> tableSpeed = new ConcurrentHashMap<>();

    private static final int MIN_VISIBLE_PHASE_MS = FactoryConfig.ms(1200);

    /** Each concrete station fixes this, e.g. "Trunks". */
    protected abstract String stationName();

    // --- Extension points: override any of these for station-specific behavior. ---

    protected double defaultFaultProbability() { return 0.15; }
    protected double defaultRequirementPassRate() { return 0.9; }
    protected double defaultEnergyCost() { return 10.0; }
    protected volatile double energyCostValue;
    protected double energyCost() { return energyCostValue; }
    protected volatile double degradationChance = 0.08;

    protected int defaultMinMs() { return 400; }
    protected int defaultMaxMs() { return 1500; }
    protected int defaultEnterDwellMs() { return FactoryConfig.ms(2000); }
    protected int defaultExitDwellMs() { return FactoryConfig.ms(2000); }

    protected int fastMinMs() { return minMs / 3; }
    protected int fastMaxMs() { return maxMs / 3; }
    protected int slowMinMs() { return minMs * 2; }
    protected int slowMaxMs() { return maxMs * 2; }
    

    /**
     * Computes what actually got applied at this station and whether the
     * station-level requirement was met. Default: standard torque/color
     * fault injection. Override entirely for a station whose job doesn't
     * fit that model (see TorqueStation, OptionalStation).
     */
    protected JSONObject computeResult(JSONObject order, boolean isFirst, boolean fast) {
        boolean fault = rnd.nextDouble() < effectiveFault(fast);
        JSONObject payload = new JSONObject();
        int expectedTorque = order.getInt("torque_setting");
        String expectedColor = isFirst ? order.getString("wheel_color_front") : order.getString("wheel_color_rear");
        payload.put("fault_injected", fault);
        if (fault) {
            payload.put("torque_applied", expectedTorque + torqueError());
            payload.put("color_applied", COLORS[rnd.nextInt(COLORS.length)]);
        } else {
            payload.put("torque_applied", expectedTorque);
            payload.put("color_applied", expectedColor);
        }
        payload.put("requirement_met", computeRequirementMet());
        return payload;
    }

    /** Default: random pass/fail against requirementPassRate. Override for different logic. */
    protected boolean computeRequirementMet() {
        return rnd.nextDouble() < Math.max(0.05, requirementPassRate - PASS_RATE_PENALTY_PER_LEVEL * degradationLevel);
    }

    //===========  Shared mechanics below; not meant to be overridden. ============//
    
    private static final class StationRequest {
        final int orderId;
        final boolean isFirst;
        final boolean fast;
        StationRequest(int orderId, boolean isFirst, boolean fast) {
            this.orderId = orderId;
            this.isFirst = isFirst;
            this.fast = fast;
        }
    }

    /** Human-readable, unique id of this instance, e.g. "Wheels B". */
    protected String instanceLabel() {
        return stationName() + " " + instanceId;
    }

    /** Effective probability that a pass injects a wrong torque/color (used by computeResult and by getEstimate). */
    protected double effectiveFault(boolean fast) {
        double p = faultProbability;
        p *= DEG_FAULT[degradationLevel];
        if (fast) p *= FAST_FAULT_MULTIPLIER;
        return Math.min(0.95, p);
    }

    /** Energy one pass costs right now (degradation raises it). */
    protected double currentEnergyCost(boolean fast) {
        double base = energyCost() * (fast ? FAST_ENERGY_MULTIPLIER : SLOW_ENERGY_MULTIPLIER);
        return base * DEG_ENERGY[degradationLevel];
    }

    /** Mean of randomProcessingTime(fast); kept next to it so the two stay in sync. */
    private double meanProcessingMs(boolean fast) {
        int lo = fast ? fastMinMs() : slowMinMs();
        int hi = fast ? fastMaxMs() : slowMaxMs();
        double t = (lo + Math.max(lo + 1, hi)) / 2.0;
        t *= DEG_TIME[degradationLevel];
        return Math.max(t, MIN_VISIBLE_PHASE_MS);
    }

    protected void setup(String instanceId, int capacity) {
        this.instanceId = instanceId;
        this.capacity = Math.max(1, capacity);
        this.minMs = FactoryConfig.ms(defaultMinMs());
        this.maxMs = FactoryConfig.ms(defaultMaxMs());
        this.energyCostValue = defaultEnergyCost();
        this.faultProbability = defaultFaultProbability();
        this.requirementPassRate = defaultRequirementPassRate();
        this.enterDwellMs = defaultEnterDwellMs();
        this.exitDwellMs = defaultExitDwellMs();
        FactoryState.INSTANCE.registerStation(instanceLabel(), this);
        for (int i = 1; i <= this.capacity; i++) {
            String tableId = instanceLabel() + " #" + i;
            Thread t = new Thread(() -> workerLoop(tableId), "station-worker-" + tableId);
            t.setDaemon(true);
            workerThreads.add(t);
            t.start();
        }
        log(stationName() + " ready (capacity " + this.capacity + ")");
    }

    @OPERATION void setProcessingTimeBounds(int minMs, int maxMs) { this.minMs = FactoryConfig.ms(minMs); this.maxMs = FactoryConfig.ms(maxMs); }
    @OPERATION void setFaultProbability(double p) { this.faultProbability = p; }
    @OPERATION void setRequirementPassRate(double p) { this.requirementPassRate = p; }
    @OPERATION void setEnergyCost(double v) { this.energyCostValue = v; }
    @OPERATION void setDegradationChance(double p) { this.degradationChance = p; }
    @OPERATION void setEnterDwellMs(int ms) { this.enterDwellMs = ms; }
    @OPERATION void setExitDwellMs(int ms) { this.exitDwellMs = ms; }

    @OPERATION
    void enter(int orderId, String speed) {
        String denied = OrgLedger.INSTANCE.check(getCurrentOpAgentId().getAgentName(), OrgLedger.ENTER_STATION, orderId,
                instanceLabel() + ".enter");
        if (denied != null) { failed(denied); return; }
        enqueuedAt.put(orderId, System.currentTimeMillis());
        JSONObject order = FactoryState.INSTANCE.orders.get(orderId);
        if (order == null) { failed("no_such_order:" + orderId); return; }
        boolean isFirst = order.getJSONArray("production_logs").length() == 0;
        boolean fast = "fast".equals(speed);
        queue.offer(new StationRequest(orderId, isFirst, fast));
        signal("station_queued", stationName(), orderId, queue.size());
    }

    @OPERATION
    void exit(int orderId) {
        String denied = OrgLedger.INSTANCE.check(getCurrentOpAgentId().getAgentName(), OrgLedger.EXIT_STATION, orderId,
                instanceLabel() + ".exit");
        if (denied != null) { failed(denied); return; }
        BlockingQueue<Boolean> sig = exitSignals.get(orderId);
        if (sig != null) sig.offer(true);
    }

    @OPERATION
    void getStatus(OpFeedbackParam<Boolean> busyOut, OpFeedbackParam<Integer> queueLenOut, OpFeedbackParam<Boolean> degradedOut) {
        busyOut.set(tableOccupant.size() >= capacity);
        queueLenOut.set(queue.size());
        degradedOut.set(degraded);
    }

    /**
     * Forecast for a skate that would join this instance now. Lets the skate
     * project its own norms (deadline, energy budget, content) per instance
     * before committing:
     *  waitMs    - expected time before its turn starts (queue + current occupant)
     *  serviceMs - expected time from claiming a table until the table is free again
     *  energy    - energy one pass costs right now
     *  fault     - probability that this pass applies a wrong torque/color
     */
    @OPERATION
    void getEstimate(String speed, OpFeedbackParam<Double> waitMsOut, OpFeedbackParam<Double> serviceMsOut,
                     OpFeedbackParam<Double> energyOut, OpFeedbackParam<Double> faultOut) {
        boolean fast = "fast".equals(speed);
        double service = enterDwellMs + meanProcessingMs(fast) + exitDwellMs;
        int occupied = tableOccupant.size();
        int waiting = queue.size();
        double wait = (occupied < capacity && waiting == 0) ? 0.0 : (waiting + 0.5 * occupied) * service / capacity;
        waitMsOut.set(wait);
        serviceMsOut.set(service);
        energyOut.set(currentEnergyCost(fast));
        faultOut.set(effectiveFault(fast));
    }

    private int randomProcessingTime(boolean fast) {
        int lo = fast ? fastMinMs() : slowMinMs();
        int hi = fast ? fastMaxMs() : slowMaxMs();
        int t = lo + rnd.nextInt(Math.max(1, hi - lo));
        t = (int) (t * DEG_TIME[degradationLevel]);
        return Math.max(t, MIN_VISIBLE_PHASE_MS);
    }

    private void updateDegradation() {
        if (degraded) {
            degradedOpsRemaining--;
            if (degradedOpsRemaining <= 0) {
                degraded = false; degradationLevel = 0; wear = 0;
                FactoryState.INSTANCE.appendEvent(new JSONObject().put("event_type", "station_recovered").put("station", instanceLabel()));
            }
        } else {
            wear += WEAR_PER_PASS;
            if (rnd.nextDouble() < Math.min(MAX_DEGRADATION_CHANCE, degradationChance + wear)) {
                double r = rnd.nextDouble();
                degradationLevel = r < 0.6 ? 1 : (r < 0.9 ? 2 : 3);
                degraded = true;
                degradedOpsRemaining = 2 + degradationLevel + rnd.nextInt(3);
                FactoryState.INSTANCE.appendEvent(new JSONObject().put("event_type", "station_degraded")
                        .put("station", instanceLabel()).put("level", degradationLevel));
            }
        }
    }

    /**
     * Order: enter dwell (visible "arriving") -> occupied+logged IN_PROGRESS
     * -> processing time -> WAIT for the agent's exit() call -> only then
     * is the result computed/logged and the table freed. This is what makes
     * the new part appear exactly at exit, not the moment processing ends.
     */
    private void workerLoop(String tableId) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                StationRequest req = queue.take();
                execInternalOp("doClaimed", tableId, req.orderId, req.fast);
                Thread.sleep(enterDwellMs);
                execInternalOp("doOccupied", tableId, req.orderId);
                reseed("proc", req.orderId);
                Thread.sleep(randomProcessingTime(req.fast));

                BlockingQueue<Boolean> exitSignal = new LinkedBlockingQueue<>(1);
                exitSignals.put(req.orderId, exitSignal);
                execInternalOp("doReadyToExit", tableId, req.orderId);
                exitSignal.take();
                exitSignals.remove(req.orderId);
                execInternalOp("doDone", req.orderId, req.isFirst, req.fast);
                Thread.sleep(exitDwellMs);
                execInternalOp("doFreed", tableId);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    @INTERNAL_OPERATION
    void doClaimed(String tableId, int orderId, boolean fast) {
        tableOccupant.put(tableId, orderId);
        tableSpeed.put(tableId, fast);
        tablePhase.put(tableId, "entrance");
    }

   @INTERNAL_OPERATION
    void doReadyToExit(String tableId, int orderId) {
        tablePhase.put(tableId, "exit");
        signal("station_ready_to_exit", stationName(), orderId);
    }

    @INTERNAL_OPERATION
    void doOccupied(String tableId, int orderId) {
        reseed("degr", orderId);
        updateDegradation();
        JSONObject order = FactoryState.INSTANCE.orders.get(orderId);
        if (order == null) return;
        tableOccupant.put(tableId, orderId);
        String prevStatus = order.getString("station_status");


        long waitMs = System.currentTimeMillis() - enqueuedAt.getOrDefault(orderId, System.currentTimeMillis());
        enqueuedAt.remove(orderId);
        JSONObject startPayload = new JSONObject();
        startPayload.put("queue_wait_ms", waitMs);
        startPayload.put("table_id", tableId);
        startPayload.put("instance", instanceId);
        FactoryState.INSTANCE.appendLog(orderId, stationName(), prevStatus, "IN_PROGRESS", "Assembly started", startPayload);

        order.put("station_status", "IN_PROGRESS");
        order.put("current_station", stationName());
        order.put("current_table", tableId);
        tablePhase.put(tableId, "processing");
        signal("station_started", stationName(), tableId, orderId);
        FactoryState.INSTANCE.appendEvent(new JSONObject()
                .put("event_type", "operation_started")
                .put("work_order_id", orderId)
                .put("work_center", stationName())
                .put("resource_id", tableId));
    }

    @INTERNAL_OPERATION
    void doDone(int orderId, boolean isFirst, boolean fast) {
        JSONObject order = FactoryState.INSTANCE.orders.get(orderId);
        if (order == null) return;

        reseed("res", orderId);
        JSONObject payload = computeResult(order, isFirst, fast);
        payload.put("energy_used", Math.round(currentEnergyCost(fast) * 10) / 10.0);
        payload.put("instance", instanceId);
        payload.put("speed", fast ? "fast" : "slow");
        boolean requirementMet = payload.optBoolean("requirement_met", true);

        FactoryState.INSTANCE.appendLog(orderId, stationName(), "IN_PROGRESS", "COMPLETED", "Assembly completed", payload);
        order.put("station_status", "PENDING");
        signal("station_done", stationName(), orderId, payload.get("torque_applied"), payload.get("color_applied"), requirementMet, payload.get("energy_used"));
    }

    @INTERNAL_OPERATION
    void doFreed(String tableId) {
        tableOccupant.remove(tableId);
        tablePhase.remove(tableId);
        signal("station_freed", stationName(), tableId);
    }

    public JSONObject toStatusJson() {
        JSONObject s = new JSONObject();
        s.put("station", stationName());
        s.put("capacity", capacity);
        s.put("degraded", degraded);
        s.put("degradation_level", degradationLevel);
        s.put("degraded_ops_remaining", degraded ? degradedOpsRemaining : 0);
        s.put("energy_nominal", energyCost());
        s.put("energy_slow", Math.round(currentEnergyCost(false) * 10) / 10.0);
        s.put("energy_fast", Math.round(currentEnergyCost(true) * 10) / 10.0);
        s.put("fault_chance_slow", Math.round(effectiveFault(false) * 1000) / 10.0);
        s.put("fault_chance_fast", Math.round(effectiveFault(true) * 1000) / 10.0);
        s.put("time_factor", DEG_TIME[degradationLevel]);
        s.put("instance", instanceId);
        s.put("instance_label", instanceLabel());
        s.put("artifact", getId().getName());
        JSONArray tables = new JSONArray();
        int occupiedCount = 0;
        for (int i = 1; i <= capacity; i++) {
            String tableId = instanceLabel() + " #" + i;
            Integer occ = tableOccupant.get(tableId);
            JSONObject t = new JSONObject();
            t.put("table", tableId);
            t.put("busy", occ != null);
            t.put("order_id", occ != null ? occ : JSONObject.NULL);
            t.put("phase", tablePhase.getOrDefault(tableId, "processing"));
            t.put("speed", occ != null ? (tableSpeed.getOrDefault(tableId, false) ? "fast" : "slow") : JSONObject.NULL);
            if (occ != null) occupiedCount++;
            tables.put(t);
        }
        s.put("tables", tables);
        s.put("busy", occupiedCount >= capacity);
        JSONArray waitingIds = new JSONArray();
        for (StationRequest r : new ArrayList<>(queue)) waitingIds.put(r.orderId);
        s.put("waiting", waitingIds.length());
        s.put("waiting_orders", waitingIds);
        return s;
    }
}