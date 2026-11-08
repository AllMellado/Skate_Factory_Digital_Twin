package skt;

public class OptionalStation extends AbstractWorkstationArtifact {

    void init(String instanceId, int capacity) {
        setup(instanceId, capacity);
    }

    @Override
    protected String stationName() {
        return "Optional";
    }

    @Override
    protected int defaultMinMs() {
        return 3000;
    }

    @Override
    protected int defaultMaxMs() {
        return 5000;
    }

    @Override
    protected double defaultFaultProbability() {
        return 0.05;
    }

    @Override
    protected boolean computeRequirementMet() {
        return rnd.nextDouble() < 0.98;
    }
}