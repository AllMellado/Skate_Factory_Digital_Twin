package ora4mas.nopl;

import cartago.OPERATION;
import skt.OrgGuard;

/**
 * One scheme board per order. Committing to a mission needs the role the mission belongs to
 * (mBuild: order_builder and the owner of the order; mInspect: quality_inspector); administration is manager-only.
 */
public class SecureSchemeBoard extends SchemeBoard {

    @OPERATION
    @Override
    public void commitMission(String mission) throws cartago.CartagoException {
        String denied = OrgGuard.checkCommit(getOpUserName(), getId().getName(), mission);
        if (denied != null) { failed(denied); return; }
        super.commitMission(mission);
    }

    /** Regimentation point: "build" cannot be declared achieved for an incomplete assembly when the regulation regiments it. */
    @OPERATION
    @Override
    public void goalAchieved(String goal) throws cartago.CartagoException {
        String blocked = OrgGuard.checkGoal(getOpUserName(), getId().getName(), goal);
        if (blocked != null) { failed(blocked); return; }
        super.goalAchieved(goal);
    }

    private boolean denied(String op) {
        String d = OrgGuard.checkAdmin(getOpUserName(), "SchemeBoard." + op);
        if (d != null) { failed(d); return true; }
        return false;
    }

    @OPERATION @Override
    public void admCommand(String cmd) throws cartago.CartagoException, jason.asSyntax.parser.ParseException,
            jason.NoValueException, moise.common.MoiseException, npl.parser.ParseException {
        if (!denied("admCommand")) super.admCommand(cmd);
    }

    @OPERATION @Override
    public void addMission(String m) throws moise.common.MoiseException, npl.parser.ParseException {
        if (!denied("addMission")) super.addMission(m);
    }

    @OPERATION @Override
    public void setCardinality(String type, String m, int min, int max) throws moise.common.MoiseException, npl.parser.ParseException {
        if (!denied("setCardinality")) super.setCardinality(type, m, min, max);
    }

    @OPERATION @Override
    public void resetGoal(String goal) throws cartago.CartagoException {
        if (!denied("resetGoal")) super.resetGoal(goal);
    }
}
