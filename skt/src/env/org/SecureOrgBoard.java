// Package ora4mas.nopl: the board-class hooks of OrgBoard are protected.
package ora4mas.nopl;

import cartago.ArtifactId;
import cartago.OPERATION;
import cartago.OpFeedbackParam;
import skt.OrgGuard;

/** OrgBoard that builds its groups, schemes and norm board from the guarded classes, and only obeys org_manager. */
public class SecureOrgBoard extends OrgBoard {
    @Override protected String getGroupBoardClass()      { return SecureGroupBoard.class.getName(); }
    @Override protected String getSchemeBoardClass()     { return SecureSchemeBoard.class.getName(); }
    @Override protected String getNormativeBoardClass()  { return SecureNormativeBoard.class.getName(); }

    private boolean denied(String op) {
        String d = OrgGuard.checkAdmin(getOpUserName(), "OrgBoard." + op);
        if (d != null) { failed(d); return true; }
        return false;
    }

    @OPERATION @Override
    public void createGroup(String name, String type, OpFeedbackParam<ArtifactId> id) throws cartago.OperationException {
        if (!denied("createGroup")) super.createGroup(name, type, id);
    }
    @OPERATION @Override
    public void createScheme(String name, String type, OpFeedbackParam<ArtifactId> id) throws cartago.OperationException {
        if (!denied("createScheme")) super.createScheme(name, type, id);
    }
    @OPERATION @Override
    public void createNormativeBoard(String name, OpFeedbackParam<ArtifactId> id) throws cartago.OperationException {
        if (!denied("createNormativeBoard")) super.createNormativeBoard(name, id);
    }
    @OPERATION @Override public void destroyGroup(String name)  { if (!denied("destroyGroup")) super.destroyGroup(name); }
    @OPERATION @Override public void destroyScheme(String name) { if (!denied("destroyScheme")) super.destroyScheme(name); }
}
