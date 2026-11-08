// org_manager: the only agent that decides who gets which role.
//   - loads the Moise specification src/org/factory-os.xml (SecureOrgBoard),
//     creates the group and the norm board from it
//   - for every agent that needs a role: AUTHORISES it, then ACTIVATES the agent's
//     own adopt_role plan (adoptRole is self-service in Moise, see inc/org_member.asl)
//   - admits new skateboard agents on request of an intake_clerk
//   - takes order_builder back when the order is finished
// Permissions are enforced in the artifacts (OrgLedger / SecureGroupBoard), not here.

staff([agent(quality_control, quality_inspector),
       agent(skate_creator,   intake_clerk),
       agent(order_intake,    intake_clerk)]).

!start.
+!start <-
   .my_name(Me);
   makeArtifact("factory_org","skt.FactoryOrg",[Me],LedgerId);
   focus(LedgerId);
   +ledger_id(LedgerId);

   makeArtifact("factory_os","ora4mas.nopl.SecureOrgBoard",["src/org/factory-os.xml"],OsId);
   focus(OsId);
   +os_id(OsId);
   createGroup("factory_group",factory_group,GroupId)[artifact_id(OsId)];
   focus(GroupId);
   +group_id(GroupId);
   // the manager takes its own Moise role first: from here on the Moise state is what counts
   adoptRole(org_manager)[artifact_id(GroupId)];
   // norms come from src/org/factory-os.npl; role facts are mirrored from the group (see below)
   createNormativeBoard("factory_norms",NormId)[artifact_id(OsId)];
   focus(NormId);
   +norms_id(NormId);
   loadNormsFromFile("src/org/factory-os.npl")[artifact_id(NormId)];

   ?staff(Staff0);
   jia.sysprop("skt.regulation",0,RegMode);      // scenario "regulation adaptation": one more role to hand out
   if (RegMode > 0) { .concat(Staff0,[agent(regulator,regulator)],Staff); } else { Staff = Staff0; }
   !assign_staff(Staff);
   markReady[artifact_id(LedgerId)];
   .print("Organisation ready.").

+!assign_staff([]) <- true.
+!assign_staff([agent(Name,Role)|Rest]) <-
   !give_role(Name,Role);
   !assign_staff(Rest).

// authorise, then ask the agent to adopt; wait until Moise accepted it
+!give_role(Name,Role) <-
   ?ledger_id(LedgerId);
   authoriseRole(Name,Role)[artifact_id(LedgerId)];
   !ask_adopt(Name,Role,0);
   !wait_role(Name,Role,0).

+!ask_adopt(Name,Role,_) <- .send(Name,achieve,adopt_role(Role)).
-!ask_adopt(Name,Role,N) : N < 50 <- .wait(200); N1 = N + 1; !ask_adopt(Name,Role,N1).   // agent not created yet

+!wait_role(Name,Role,_) <-
   ?ledger_id(LedgerId);
   hasRole(Name,Role,Has)[artifact_id(LedgerId)];
   Has == true.
-!wait_role(Name,Role,N) : N < 100 <- .wait(100); N1 = N + 1; !wait_role(Name,Role,N1).
-!wait_role(Name,Role,_) <- .print("WARNING: ",Name," never adopted ",Role).

// A clerk (skate_creator / order_intake) announces a new skateboard agent.
+!admit(Name)[source(Requester)] <-
   ?ledger_id(LedgerId);
   hasRole(Requester,intake_clerk,IsClerk)[artifact_id(LedgerId)];
   hasRole(Name,order_builder,Already)[artifact_id(LedgerId)];
   if (IsClerk == true & Already == false) {
      !locked_admit(Name,Requester);
   } else {
      .print("Admission of ",Name," denied (requested by ",Requester,")");
      .send(Requester,tell,admission_denied(Name));
   }.

-!admit(Name)[source(Requester)] <-
   .print("Admission of ",Name," failed");
   .send(Requester,tell,admission_denied(Name)).

// The skateboard adopts its role itself and that must never overlap with another change of the group
// authorise, ask, and wait until the role is really held.
+!locked_admit(Name,Requester) <-
   !take_lock;
   ?ledger_id(LedgerId);
   authoriseRole(Name,order_builder)[artifact_id(LedgerId)];
   .send(Name,achieve,adopt_role(order_builder));
   !wait_adopted(Name,order_builder,0);
   -scheme_lock.

-!locked_admit(Name,Requester) <-
   -scheme_lock;
   .print("Admission of ",Name," failed");
   .send(Requester,tell,admission_denied(Name)).

+!wait_adopted(Name,Role,N) <-
   ?ledger_id(LedgerId);
   hasRole(Name,Role,Has)[artifact_id(LedgerId)];
   if (Has \== true) {
      if (N < 120) {
         .wait(50);
         N1 = N + 1;
         !wait_adopted(Name,Role,N1);
      } else {
         .print("WARNING: ",Name," did not adopt ",Role," within 6 s");
      }
   }.

// Moise -> norms bridge. The group board publishes play(Agent,Role,Group) whenever an
// agent adopts/leaves a role. This Moise version does not hand those facts to the
// norm board by itself, so the manager asserts them: norms such as
//   finished(O) & play(Inspector, quality_inspector, Group)
// then follow the real Moise state. (Only org_manager may assert play facts.)
+play(Agent,Role,Group)[artifact_id(_)] : norms_id(NormId) <-
   .concat("play(",Agent,",",Role,",",Group,")",Fact);
   addFact(Fact)[artifact_id(NormId)].

-play(Agent,Role,Group)[artifact_id(_)] : norms_id(NormId) <-
   .concat("play(",Agent,",",Role,",",Group,")",Fact);
   removeFact(Fact)[artifact_id(NormId)].

// Started by twin_runner once the factory artifacts exist.
+!watch_factory <-
   lookupArtifact("finished_storage",FinishedId);
   focus(FinishedId);
   lookupArtifact("empty_storage",EmptyId);
   focus(EmptyId);
   !!sweep_loop.

// Order done: the builder loses its role at once (evicted in the Moise group, so every permission check
// fails from this moment); the order's scheme is removed when it is satisfied.
+order_finished(OrderId) <-
   ?ledger_id(LedgerId);
   orderOwner(OrderId,Owner)[artifact_id(LedgerId)];
   if (Owner \== "") {
      !locked_revoke(Owner);
   }.

// evictRole changes the group too: same mutex as admissions and scheme setup
+!locked_revoke(Owner) <-
   !take_lock;
   ?ledger_id(LedgerId);
   ?group_id(GroupId);
   revokeRole(Owner,order_builder)[artifact_id(LedgerId)];
   evictRole(Owner,order_builder)[artifact_id(GroupId)];
   -scheme_lock;
   .print("Role order_builder revoked from ",Owner).

-!locked_revoke(Owner) <-
   -scheme_lock;
   .print("Could not revoke order_builder from ",Owner).

// ---- one Moise scheme per order -------------------------------------------------------
// The builder commits to mBuild and the inspector to mInspect (each on the manager's request, using
// its own commitMission). Scheme state is mirrored onto the norm board, where the obligations on
// builder and inspector are monitored.
// Scheme setup is serialised with a mutex (Moise's group board is not thread-safe), not with an atomic plan:
// an atomic plan would freeze the whole manager, admissions included, if one Moise call ever hung.
+order_registered(OrderId,Owner,_)[artifact_id(_)] <-
   !take_lock;
   !run_scheme(OrderId,Owner).

@take_lock[atomic]
+!take_lock : not scheme_lock <- +scheme_lock.
+!take_lock <- .wait(50); !take_lock.

+!run_scheme(OrderId,Owner) <- !open_scheme(OrderId,Owner); -scheme_lock.
-!run_scheme(OrderId,_) <- -scheme_lock; .print("Scheme setup failed for order ",OrderId).

+!open_scheme(OrderId,Owner) : os_id(OsId) & norms_id(NormId) & group_id(GroupId) <-
   .concat("order_",OrderId,SchName);
   .term2string(Sch,SchName);          // atom, as the scheme board reports it
   createScheme(Sch,order_scheme,SchId)[artifact_id(OsId)];
   addScheme(Sch)[artifact_id(GroupId)];      // the group's role players become the scheme's potential committers
   focus(SchId);
   +scheme_order(Sch,OrderId);
   .concat("scheme_builder(",OrderId,",",Owner,")",F);
   addFact(F)[artifact_id(NormId)];
   +scheme_builder_fact(OrderId,F);
   jia.scaled_ms(120000,BuildWindow);
   !!deadline(OrderId,build,BuildWindow);
   .send(Owner,achieve,commit_mission(mBuild,Sch,OrderId));
   .send(quality_control,achieve,commit_mission(mInspect,Sch,OrderId)).

+goalState(Sch,Goal,_,Ach,satisfied)[artifact_id(_)] : scheme_order(Sch,OrderId) & (Goal == build | Goal == inspect) & norms_id(NormId) & ledger_id(LedgerId) <-
   // achieved inside the window: the obligation was fulfilled (NPL emits no signal for that case)
   if (not closed(OrderId,Goal)) {
      .nth(0,Ach,Who);
      .concat(Goal,"_of_",OrderId,Aim);
      recordObligation(Who,Aim,"fulfilled")[artifact_id(LedgerId)];
   }
   .concat("goal_done(",OrderId,",",Goal,")",F);
   addFact(F)[artifact_id(NormId)];
   if (Goal == build) { jia.scaled_ms(30000,InspectWindow); !!deadline(OrderId,inspect,InspectWindow); }
   !close(OrderId,Goal);
   .wait(500);
   removeFact(F)[artifact_id(NormId)].

// Obligation clock: when the window closes, evaluate the obligation once (see factory-os.xml).
+!deadline(OrderId,Goal,Ms) <- .wait(Ms); !close(OrderId,Goal).

+!close(OrderId,Goal) : closed(OrderId,Goal) <- true.
+!close(OrderId,Goal) : norms_id(NormId) <-
   +closed(OrderId,Goal);
   .concat("due(",OrderId,",",Goal,")",F);
   addFact(F)[artifact_id(NormId)];
   .wait(500);
   removeFact(F)[artifact_id(NormId)];
   if (Goal == build & scheme_builder_fact(OrderId,BF)) { removeFact(BF)[artifact_id(NormId)]; }.

+goalState(Sch,fulfil_order,_,_,satisfied)[artifact_id(_)] : scheme_order(Sch,OrderId) <-
   .wait(500);
   -scheme_order(Sch,OrderId);
   !locked_destroy(Sch).

// A destroyed scheme must also be dropped from the group: addScheme made it one of the group's observers and destroyScheme
// alone leaves it there. Left in, the group's list of schemes grows with every order ever made and each adoptRole/evictRole
// walks all of them (failing on the destroyed ones), so adoptions get slower the longer the simulation runs.
// Both calls change the group, so: same mutex as admissions and scheme setup.
+!locked_destroy(Sch) <-
   !take_lock;
   ?os_id(OsId);
   ?group_id(GroupId);
   forgetScheme(Sch)[artifact_id(GroupId)];      // first: nothing notifies a scheme that is about to disappear
   destroyScheme(Sch)[artifact_id(OsId)];
   -scheme_lock.

-!locked_destroy(Sch) <-
   -scheme_lock;
   .print("Could not destroy scheme ",Sch).

// ---- obligation monitoring: builders and inspectors ------------------------------------
+oblFulfilled(obligation(Ag,_,goal_done(Order,Goal),_)) : ledger_id(LedgerId) & not reported(Ag,Order,Goal) <-
   +reported(Ag,Order,Goal);
   .concat(Goal,"_of_",Order,Aim);
   recordObligation(Ag,Aim,"fulfilled")[artifact_id(LedgerId)].

+oblUnfulfilled(obligation(Ag,_,goal_done(Order,Goal),_)) : ledger_id(LedgerId) & not reported(Ag,Order,Goal) <-
   +reported(Ag,Order,Goal);
   .concat(Goal,"_of_",Order,Aim);
   .print("OBLIGATION VIOLATED: ",Ag," did not achieve ",Goal," of order ",Order);
   recordObligation(Ag,Aim,"violated")[artifact_id(LedgerId)].

// Expired authorisations are dropped (and audited) every few seconds.
+!sweep_loop <-
   ?ledger_id(LedgerId);
   while (true) {
      .wait(3000);
      sweepAuthorisations(_)[artifact_id(LedgerId)];
   }.
