package skt;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StationRegistry extends Artifact {

    private final Map<String, List<String>> instancesByType = new ConcurrentHashMap<>();

    void init() { }

    @OPERATION
    void registerInstance(String stationType, String artifactName) {
        instancesByType.computeIfAbsent(stationType, k -> new ArrayList<>()).add(artifactName);
    }

    @OPERATION
    void getInstances(String stationType, OpFeedbackParam<Object[]> namesOut) {
        namesOut.set(instancesByType.getOrDefault(stationType, new ArrayList<>()).toArray());
    }
}