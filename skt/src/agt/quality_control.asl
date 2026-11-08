
batch_size(10).
// energy_budget(B) is observed from the quality_policy artifact (single source, shared with the skateboards).
base_stations(["Trunks", "Wheels", "Torque", "Optional"]).

norm_for_aim(all_stations_visited,n_complete_assembly).
norm_for_aim(finished_within_time,n_max_duration).
norm_for_aim(content_correct,n_content_correct).
norm_for_aim(energy_within_budget,n_energy_budget).

//!start. started by twin_runner
+!start <-
   !loadNorms;
   !lookupFactory;
   !regulation_setup;
   .print("Quality control online.").

// The norm board is created and loaded by org_manager. Asserting facts on it
// (all_stations_visited, finished, ...) needs the quality_inspector role.
{ include("org_member.asl") }
{ include("regulation_aware.asl") }
{ include("workspace.asl") }

+!loadNorms <-
   lookupArtifact("factory_norms",NormId);
   focus(NormId).

+!lookupFactory <-
   !join_main;   // inc/workspace.asl
   lookupArtifact("quality_policy",PolicyId);
   focus(PolicyId);
   +qc_policy_id(PolicyId);
   lookupArtifact("empty_storage",EmptyId);
   focus(EmptyId);
   lookupArtifact("station_registry",RegistryId);
   for(.member(Type, ["Trunks","Wheels","Torque","Optional"])){
      getInstances(Type,Names)[artifact_id(RegistryId)];
      for(.member(N, Names)){
         lookupArtifact(N, StationID);
         focus(StationID);
         .print("Focused on ",Type," ",N);
      }
   }
   lookupArtifact("finished_storage",FinishedId);
   focus(FinishedId).

// who owns the order (needed to attribute a violation to the right skateboard)
+order_registered(OrderId,Owner,_) <- +qc_owner(OrderId,Owner).

+order_created(OrderId,ClientName,ColorFront,ColorRear,Torque,Deadline) <-
   .print("Order ",OrderId," - Created. ",ClientName," [F-",ColorFront," R-",ColorRear,"] T-",Torque," Deadline-",Deadline);
   +qc_spec(OrderId,ClientName,ColorFront,ColorRear,Torque);
   +qc_deadline(OrderId,Deadline);
   +qc_visited(OrderId,[]);
   +qc_content_ok(OrderId,true);
   if (Torque > 60) { addFact(high_torque(OrderId)); }
   cartago.invoke_obj("java.lang.System",currentTimeMillis,StartTime);
   +qc_started_at(OrderId,StartTime).

+station_done(Station,OrderId,TorqueApplied,ColorApplied,RequirementMet,EnergyUsed) : qc_visited(OrderId,V) <-
   -qc_visited(OrderId,V);
   +qc_visited(OrderId,[Station|V]);
   if (qc_energy(OrderId,E)) { -qc_energy(OrderId,E); OldE = E; } else { OldE = 0; }
   +qc_energy(OrderId,OldE+EnergyUsed);
   .print("Order ",OrderId," - Completed ",Station," -> Current Path: ",[Station|V]);
   !qc_check_content(OrderId,Station,TorqueApplied,ColorApplied).

+!qc_check_content(OrderId,Station,TorqueApplied,ColorApplied) <-
   ?qc_visited(OrderId,V);
   .length(V,N);
   ?qc_spec(OrderId,_,ColorFrontExpected,ColorRearExpected,TorqueExpected);
   if (N == 1) {
      ExpectedColor = ColorFrontExpected;
   } else {
      ExpectedColor = ColorRearExpected;
   }
   +qc_applied(OrderId, ColorApplied, TorqueApplied);
   if (TorqueApplied \== TorqueExpected | ColorApplied \== ExpectedColor) {
      ?qc_content_ok(OrderId,OldContentOk);
      -qc_content_ok(OrderId,OldContentOk);
      +qc_content_ok(OrderId,false);
   }.

// The order's own energy budget (derived from the customer's price) replaces the policy-wide one.
+order_terms(OrderId,_,Budget) <- +qc_budget(OrderId,Budget).

+order_finished(OrderId) : qc_spec(OrderId,ClientName,_,_,_) <-
   cartago.invoke_obj("java.lang.System",currentTimeMillis,Now);

   ?qc_started_at(OrderId,StartTime);
   Elapsed = Now - StartTime;
   +qc_elapsed(OrderId,Elapsed);
   ?qc_deadline(OrderId,MaxMs);
   if (qc_visited(OrderId,VV)) { 
      V = VV; 
   } else {
      .print("VISITED PATH FOR ORDER ",OrderId," LOST"); 
      V = []; 
   }

   ?base_stations(Base);
   if ( not (.member(S,Base) & not .member(S,V)) ) {
      AllVisited = true;
   } else {
      AllVisited = false;
   }
   if (Elapsed <= MaxMs) { WithinTime = true; } else { WithinTime = false; }
   if (qc_energy(OrderId,TotalE)) { TE = TotalE; } else { TE = 0; }
   if (qc_budget(OrderId,OrderBudget)) { Budget = OrderBudget; } else { ?energy_budget(Budget); }
   if (TE <= Budget) { EnergyOk = true; } else { EnergyOk = false; }
   -qc_energy(OrderId,_);

   ?qc_content_ok(OrderId,ContentOk);

   // regulation board: QC's verdict on n_complete_assembly (the sanction itself comes later, see apply_sanction)
   if (regulation_on & qc_owner(OrderId,Owner)) {
      ?regulation_id(RegId);
      recordInspection(OrderId,Owner,n_complete_assembly,AllVisited)[artifact_id(RegId)];
   }

   if (AllVisited == false) { +qc_pending(OrderId,n_complete_assembly); }
   if (WithinTime == false) { +qc_pending(OrderId,n_max_duration); }
   if (ContentOk == false)  { +qc_pending(OrderId,n_content_correct); }
   if (EnergyOk == false)   { +qc_pending(OrderId,n_energy_budget); }

   .print("Order ",OrderId," by ",ClientName," Finished");
   addFact(finished(OrderId));

   if (AllVisited) { addFact(all_stations_visited(OrderId)) }
   if (WithinTime) { addFact(finished_within_time(OrderId)) }
   if (ContentOk) { addFact(content_correct(OrderId)) }
   if (EnergyOk) { addFact(energy_within_budget(OrderId)) }

   removeFact(finished(OrderId));
   removeFact(all_stations_visited(OrderId));
   removeFact(finished_within_time(OrderId));
   removeFact(content_correct(OrderId));
   removeFact(energy_within_budget(OrderId));

   if (qc_pending(OrderId,_)) {
      !!qc_cleanup_timeout(OrderId); // safety net: reset anyway if a sanction never comes
   } else {
      .print("*** Order ",OrderId," by ",ClientName," fulfilled all norms ***");
      !qc_cleanup(OrderId);   // no violation: nothing will ever ask for these beliefs
   }
   !achieve_goal(OrderId,inspect);
   .

// Forget everything QC keeps about one order.
+!qc_cleanup(OrderId) <-
   -qc_spec(OrderId,_,_,_,_);
   -qc_visited(OrderId,_);
   -qc_content_ok(OrderId,_);
   -qc_started_at(OrderId,_);
   -qc_deadline(OrderId,_);
   -qc_elapsed(OrderId,_);
   .abolish(qc_applied(OrderId,_,_));
   .abolish(qc_budget(OrderId,_));
   .abolish(qc_owner(OrderId,_)).

// A sanction of this order is over (applied, or it could not be): once none is left the order's beliefs can go.
+!qc_sanction_done(NormId,OrderId) <-
   -qc_pending(OrderId,NormId);
   if (not qc_pending(OrderId,_)) { !qc_cleanup(OrderId); }.

+!qc_cleanup_timeout(OrderId) <-
   .wait(60000);
   if (qc_pending(OrderId,_)) {
      .print("No sanction came for order ",OrderId," - resetting its beliefs anyway");
      .abolish(qc_pending(OrderId,_));
      !qc_cleanup(OrderId);
   }.

// apply_sanction, then release the order's beliefs; also when the sanction itself fails
+!sanction(NormId,OrderId) <-
   !apply_sanction(NormId,OrderId);
   !qc_sanction_done(NormId,OrderId).
   
-!sanction(NormId,OrderId) <-
   .print("Sanction ",NormId," for order ",OrderId," could not be applied");
   !qc_sanction_done(NormId,OrderId).

+order_finished(OrderId) <-
   if (qc_spec(OrderId, ClientName, ColorFront, ColorRear, Torque)){
      .print("Problem with finished Order ",OrderId, " - unkown problem");
   }else{
      .print("Problem with finished Order ",OrderId," - qc_spec missing");
   }.

//----------------- OBLIGATION PLANS -----------------------------

+obligation(Ag,Norm,Aim,Deadline) : .my_name(Ag) <-
  .print("My Obligation activated: ", Aim);
  .

+obligation(Ag,Norm,Aim,Deadline) : .my_name(Me) & Ag \== Me <-
  .print("Sending Obligation: ", obligation(Ag,Norm,Aim,Deadline));
  .send(Ag, achieve, Aim, Deadline);
  .

+sanction(n_complete_assembly, fulfilled, OrderId, Sanction) <-
   ?qc_spec(OrderId, Agent, ColorFront, ColorRear, Torque);
   ?qc_visited(OrderId, Path);
   ?base_stations(CompletePath);
   ?qc_policy_id(PolicyId);
   .print("*** Stations Visits Norm Fulfilled in Order ", OrderId, " - ",Path," of ",CompletePath," - Executing (",Sanction,") ***").

+sanction(n_complete_assembly, unfulfilled, OrderId, Sanction) <-
   ?qc_spec(OrderId, Agent, ColorFront, ColorRear, Torque);
   ?qc_visited(OrderId, Path);
   ?base_stations(CompletePath);
   ?qc_policy_id(PolicyId);
   .print("*** Stations Visits Norm Violated in Order ", OrderId, " - ",Path," of ",CompletePath," - Executing (",Sanction,") ***").

+sanction(n_content_correct, fulfilled, OrderId, Sanction) <-
   ?qc_spec(OrderId, Agent, ColorFront, ColorRear, Torque);
   ?qc_applied(OrderId, ColorApplied, TorqueApplied);
   ?qc_policy_id(PolicyId);
   .print("*** Skate Content Norm Fulfilled in Order ", OrderId, " Wanted [",ColorFront, ColorRear, Torque,"] Applied [",ColorApplied, TorqueApplied,"] - Executing (",Sanction,")  ***").

+sanction(n_content_correct, unfulfilled, OrderId, Sanction) <-
   ?qc_spec(OrderId, Agent, ColorFront, ColorRear, Torque);
   ?qc_applied(OrderId, ColorApplied, TorqueApplied);
   ?qc_policy_id(PolicyId);
   .print("*** Skate Content Norm Violated in Order ", OrderId, " Wanted [",ColorFront, ColorRear, Torque,"] Applied [",ColorApplied, TorqueApplied,"] - Executing (",Sanction,")  ***").

+!sanction(n_max_duration, fulfilled, OrderId, Sanction) <-
   ?qc_spec(OrderId, Agent, ColorFront, ColorRear, Torque);
   ?qc_deadline(OrderId,Deadline);
   ?qc_elapsed(OrderId,Elapsed);
   ?qc_policy_id(PolicyId);
   .print("*** Deadline Violation Norm Fulfilled in Order ", OrderId, " [",Elapsed," / ",Deadline,"] - Executing (",Sanction,") ***").

+!sanction(n_max_duration, unfulfilled, OrderId, Sanction) <-
   ?qc_spec(OrderId, Agent, ColorFront, ColorRear, Torque);
   ?qc_deadline(OrderId,Deadline);
   ?qc_elapsed(OrderId,Elapsed);
   ?qc_policy_id(PolicyId);
   .print("*** Deadline Violation Norm Violated in Order ", OrderId, " [",Elapsed," / ",Deadline,"] - Executing (",Sanction,") ***").

+sanction(n_energy_budget, fulfilled, OrderId, Sanction) <-
   ?qc_spec(OrderId, Agent, ColorFront, ColorRear, Torque);
   ?qc_policy_id(PolicyId);
   .print("*** Energy Budget Norm Fulfilled in Order ", OrderId," - Executing (",Sanction,") ***").

+sanction(n_energy_budget, unfulfilled, OrderId, Sanction) <-
   ?qc_spec(OrderId, Agent, ColorFront, ColorRear, Torque);
   ?qc_policy_id(PolicyId);
   .print("*** Energy Budget Norm Violated in Order ", OrderId," - Executing (",Sanction,") ***").


{ include("$jacamoJar/templates/common-cartago.asl") }
{ include("$jacamo/templates/common-moise.asl") }
{ include("$moise/asl/org-obedient.asl") }