package skt;

public class WheelsStation extends AbstractWorkstationArtifact {

    void init(String instanceId, int capacity) {
        setup(instanceId, capacity);
    }
    @Override
    protected String stationName() {
        return "Wheels";
    }

    @Override
    protected int defaultMinMs() {
        return 3000;
    }

    @Override
    protected int defaultMaxMs() {
        return 4000;
    }

    @Override
    protected double defaultFaultProbability() {
        return 0.25;
    }
}