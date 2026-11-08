// Package ora4mas.nopl on purpose (same reason as SecureNormativeBoard): it needs
// the protected orgState of OrgArt to check what Moise decided.
package ora4mas.nopl;

import cartago.OPERATION;
import skt.OrgGuard;

/**
 * Moise group board where adoptRole is still self-service (the agent calls it), but only works for roles
 * org_manager authorised for that agent and whose authorisation has not expired. Moise's own checks
 * (role exists, cardinality, compatibility) still apply. This board is also the single source of truth
 * for role membership: the permission checks of every artifact ask it, through registerRoleSource.
 */
public class SecureGroupBoard extends GroupBoard {

    @Override
    public void init(String osFile, String grType) throws npl.parser.ParseException, moise.common.MoiseException, cartago.OperationException {
        super.init(osFile, grType);
        OrgGuard.registerRoleSource(this::playsRole);
    }

    /**
     * Role lookup used by every permission check. Moise's group state is a plain HashMap, so a lookup that happens while
     * a role is being adopted or evicted can throw ConcurrentModificationException, which a permission check would turn
     * into a spurious refusal. It takes no lock (a check must never wait for a group update): it just reads again.
     * (Changes to the group are serialised by org_manager, see the group mutex in org_manager.asl.)
     */
    private boolean playsRole(String agent, String role) {
        for (int i = 0; ; i++) {
            try { return orgState.hasPlayer(agent, role); }
            catch (java.util.ConcurrentModificationException e) { if (i >= 50) throw e; Thread.onSpinWait(); }
        }
    }

    /**
     * GroupBoard.notifyObservers walks the live `schemes` and `listeners` sets (and passes the live player collection) while
     * calling the schemes back; a change to any of them in the meantime made the iterator throw
     * ConcurrentModificationException out of adoptRole ("Error adopting role ... ConcurrentModificationException"), so the
     * agent's first attempt failed and a skateboard that failed 6 times in a row was never admitted. Same notification, but over
     * snapshots; a scheme that cannot be updated is reported and skipped instead of aborting the others.
     */
    @Override
    protected void notifyObservers() throws cartago.CartagoException {
        tell(snapshot(schemes));
        tell(snapshot(listeners));
    }

    private void tell(java.util.List<cartago.ArtifactId> targets) {
        for (cartago.ArtifactId target : targets) {
            try {
                execLinkedOp(target, "updateRolePlayers", orgState.getId(), snapshot(orgState.getPlayers()));
            } catch (Exception e) {
                System.err.println("[GroupBoard] could not update role players of " + target.getName() + ": " + e);
            }
        }
    }

    private static <T> java.util.List<T> snapshot(java.util.Collection<T> c) {
        for (int i = 0; ; i++) {
            try { return new java.util.ArrayList<>(c); }
            catch (java.util.ConcurrentModificationException e) { if (i >= 50) throw e; Thread.onSpinWait(); }
        }
    }

    /**
     * Manager-only: drop a scheme that has been destroyed from the group's list of schemes. destroyScheme leaves it there and
     * GroupBoard.removeScheme cannot be used once the scheme is gone (and is refused by the scheme while it still has
     * commitments), so without this the list grows with every order ever made and every adoptRole/evictRole walks all of it:
     * adoptions get slower the longer the simulation runs.
     */
    @OPERATION
    public void forgetScheme(String schemeName) {
        if (denied("forgetScheme")) return;
        schemes.removeIf(id -> schemeName.equals(id.getName()));
        listeners.removeIf(id -> schemeName.equals(id.getName()));      // notifyObservers walks this set too
    }

    @OPERATION
    @Override
    public void adoptRole(String role) {
        String agent = getOpUserName();
        String denied = OrgGuard.checkAdoption(agent, role);
        if (denied != null) { failed(denied); return; }
        super.adoptRole(role);
        if (orgState.hasPlayer(agent, role)) OrgGuard.confirmAdoption(agent, role);
    }

    @OPERATION
    @Override
    public void leaveRole(String role) {
        String agent = getOpUserName();
        boolean had = orgState.hasPlayer(agent, role);
        super.leaveRole(role);
        if (had && !orgState.hasPlayer(agent, role)) OrgGuard.confirmLeft(agent, role, false);
    }

    /** Manager-only: take a role away at once (the agent does not have to cooperate). */
    @OPERATION
    public void evictRole(String agent, String role) throws Exception {
        if (denied("evictRole")) return;
        boolean had = orgState.hasPlayer(agent, role);
        leaveRoleWithoutVerify(agent, role, true);
        if (had && !orgState.hasPlayer(agent, role)) OrgGuard.confirmLeft(agent, role, true);
    }

    // ---- administrative operations: the stock board lets any agent call them ----
    private boolean denied(String op) {
        String d = OrgGuard.checkAdmin(getOpUserName(), "GroupBoard." + op);
        if (d != null) { failed(d); return true; }
        return false;
    }

    @OPERATION @Override
    public void addRole(String role) throws moise.common.MoiseException, npl.parser.ParseException {
        if (!denied("addRole")) super.addRole(role);
    }

    @OPERATION @Override
    public void setCardinality(String type, String role, int min, int max) throws moise.common.MoiseException, npl.parser.ParseException {
        if (!denied("setCardinality")) super.setCardinality(type, role, min, max);
    }

    @OPERATION @Override
    public void admCommand(String cmd) throws cartago.CartagoException, jason.asSyntax.parser.ParseException,
            jason.NoValueException, moise.common.MoiseException, npl.parser.ParseException {
        if (!denied("admCommand")) super.admCommand(cmd);
    }

    @OPERATION @Override
    public void setParentGroup(String g) throws cartago.OperationException {
        if (!denied("setParentGroup")) super.setParentGroup(g);
    }

    @OPERATION @Override public void addScheme(String s)                { if (!denied("addScheme")) super.addScheme(s); }
    @OPERATION @Override public void addSchemeWhenFormationOk(String s) { if (!denied("addSchemeWhenFormationOk")) super.addSchemeWhenFormationOk(s); }
    @OPERATION @Override public void removeScheme(String s)             { if (!denied("removeScheme")) super.removeScheme(s); }
}
