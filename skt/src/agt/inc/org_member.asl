// org_member.asl -- how any agent takes a role in the Moise group.
//
// adoptRole is self-service in Moise: the agent itself calls it. To keep the
// manager in control, the plan below only exists for requests that come from
// org_manager (source annotation is set by the MAS infrastructure, it cannot be
// forged by another agent). On top of that, the group board (SecureGroupBoard)
// refuses an adoptRole the manager did not authorise first.

adopt_tries(order_builder,0).
adopt_tries(quality_inspector,0).
adopt_tries(intake_clerk,0).
adopt_tries(regulator,0).
adopt_failed(Role,25) :- adopt_tries(Role,N) & N >= 25.

+!adopt_role(Role)[source(org_manager)] <-
   lookupArtifact("factory_group",GroupId);
   adoptRole(Role)[artifact_id(GroupId)];
   +admitted_role(Role);
   .print("Adopted role ",Role," (asked by org_manager)").

// a failed adoption is retried (the authorisation is only used up by a successful one)
-!adopt_role(Role)[source(org_manager)] : not adopt_failed(Role,25) <-
   ?adopt_tries(Role,N);
   N1 = N + 1;
   -+adopt_tries(Role,N1);
   .wait(200);
   !adopt_role(Role)[source(org_manager)].

+!leave_role(Role)[source(org_manager)] <-
   lookupArtifact("factory_group",GroupId);
   leaveRole(Role)[artifact_id(GroupId)];
   -admitted_role(Role).

// Mission commitment: same pattern as role adoption. The scheme board checks role and ownership.
+!commit_mission(Mission,Sch,OrderId)[source(org_manager)] <-
   lookupArtifact(Sch,SchId);
   commitMission(Mission)[artifact_id(SchId)];
   +my_scheme(OrderId,Sch,SchId).

+!achieve_goal(OrderId,Goal) : my_scheme(OrderId,_,SchId) <-
   goalAchieved(Goal)[artifact_id(SchId)].
+!achieve_goal(_,_) <- true.
-!achieve_goal(OrderId,Goal) <- .print("Could not achieve goal ",Goal," of order ",OrderId).

// Anybody else asking is ignored.
+!adopt_role(Role)[source(Other)] <- .print("Ignoring adopt_role(",Role,") asked by ",Other).
+!leave_role(Role)[source(Other)] <- .print("Ignoring leave_role(",Role,") asked by ",Other).
