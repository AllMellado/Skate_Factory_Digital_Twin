package skt;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class StationStats extends Artifact {

    private final ConcurrentHashMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> violations = new ConcurrentHashMap<>();

    void init() { }

    private String key(String station, boolean hasMotor) {
        return station + "|" + hasMotor;
    }

    @OPERATION
    void recordStationOutcome(String station, boolean hasMotor, boolean violation) {
        String k = key(station, hasMotor);
        attempts.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet();
        if (violation) violations.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet();
    }

    @OPERATION
    void getStationRisk(String station, boolean hasMotor, OpFeedbackParam<Double> riskOut) {
        String k = key(station, hasMotor);
        int a = attempts.getOrDefault(k, new AtomicInteger()).get();
        int v = violations.getOrDefault(k, new AtomicInteger()).get();
        riskOut.set((v + 1.0) / (a + 2.0));
    }
}