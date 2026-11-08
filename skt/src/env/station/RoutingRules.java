package skt;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RoutingRules extends Artifact {

    private final Map<String, List<String>> requiredPredecessors = new ConcurrentHashMap<>();

    void init() { }

    @OPERATION
    void setRequiredPredecessors(String station, Object[] predecessors) {
        List<String> list = new ArrayList<>();
        for (Object p : predecessors) list.add(String.valueOf(p));
        requiredPredecessors.put(station, list);
    }

    @OPERATION
    void getRequiredPredecessors(String station, OpFeedbackParam<Object[]> predecessorsOut) {
        List<String> list = requiredPredecessors.getOrDefault(station, Collections.emptyList());
        predecessorsOut.set(list.toArray());
    }
}