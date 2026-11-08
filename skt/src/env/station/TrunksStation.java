package skt;

public class TrunksStation extends AbstractWorkstationArtifact {

    void init(String instanceId, int capacity) {
        setup(instanceId, capacity);
    }

    @Override
    protected int defaultMinMs() {
        return 3000;
    }

    @Override
    protected int defaultMaxMs() {
        return 8000;
    }

    @Override
    protected String stationName() {
        return "Trunks";
    }
}