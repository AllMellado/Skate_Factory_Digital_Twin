// shortcut.asl -- a skateboard that is running late can finish early by skipping the Optional station.
// That breaks n_complete_assembly. Whether it does so is a cost/benefit decision made with the same
// machinery the skateboard already uses to choose stations:
//
//   gain    = cost of the best way to finish WITH the station  -  cost of finishing WITHOUT it
//             (weighted deadline / energy / content risk + soft cost, from skateboard.asl and skate_focus.asl)
//   penalty = fine(n_complete_assembly) published by the regulation board (QC inspects every order)
//   skip    iff gain > penalty
//
// Regimentation is not announced, so an agent only learns about it by trying: if the factory refuses the
// completion it remembers (shortcut_blocked) and goes back to the line.

skippable("Optional").

+!consider_shortcut(_,_,false) : not regulation_on <- true.
+!consider_shortcut(_,_,false) : shortcut_blocked <- true.
+!consider_shortcut(Remaining,Bests,Skip) <-
   .findall(T,(.member(T,Remaining) & skippable(T)),Skippable);
   .findall(T,(.member(T,Remaining) & not skippable(T)),Others);
   // finishing early skips EVERYTHING still ahead, so it is only a "skip the Optional station" shortcut
   // when nothing but skippable stations is left
   if (Skippable == [] | Others \== []) {
      Skip = false;
   } else {
      !evaluate_shortcut(Skippable,Bests,Skip);
   }.

+!evaluate_shortcut(Skippable,Bests,Skip) <-
   !future_sum(Bests,[],SvAll,EAll,PAll);
   !future_sum(Bests,Skippable,SvNo,ENo,PNo);
   !shortcut_cost(SvAll,EAll,PAll,CostAll);
   !shortcut_cost(SvNo,ENo,PNo,CostNo);
   Gain = CostAll - CostNo;
   ?fine("n_complete_assembly",Penalty);
   ?regulation_id(RegId);
   ?order_id(OrderId);
   if (Gain > Penalty) {
      Skip = true;
      +shortcut_pending;
      Decision = "skip";
   } else {
      Skip = false;
      Decision = "comply";
   }
   logDecision(OrderId,n_complete_assembly,Decision,Gain,Penalty)[artifact_id(RegId)];
   if (Gain > 0) {
      RG = math.round(Gain*10)/10;
      .print("Shortcut check: skipping ",Skippable," would be worth ",RG," to me, the sanction is ",Penalty," -> ",Decision);
   }.

// sums over the best instance of each remaining station, leaving out the excluded types
+!future_sum([],_,0,0,1) <- true.
+!future_sum([best(T,_,_,_)|Rest],Excl,Sv,E,P) : .member(T,Excl) <- !future_sum(Rest,Excl,Sv,E,P).
+!future_sum([best(_,Sv0,E0,F0)|Rest],Excl,Sv,E,P) <-
   !future_sum(Rest,Excl,Sv1,E1,P1);
   Sv = Sv1 + Sv0;
   E = E1 + E0;
   P = P1 * (1 - F0).

// weighted risk of finishing with this much work still ahead (no candidate of its own: wait, service, energy, fault = 0)
+!shortcut_cost(FSv,FE,FP,Cost) <-
   .findall(N,norm_weight(N,_),Norms);
   !norm_costs(Norms,ctx(0,0,0,0,FSv,FE,FP),NormCost,_);
   !soft_cost(ctx(0,0,0,0,FSv,FE,FP),Soft);
   Cost = NormCost + Soft.

// ---- finishing ----------------------------------------------------------------------------
// Normal case: same as before. Shortcut: declare "build" achieved and finish with a station missing.
// Under a regulation that regiments n_complete_assembly the scheme board / FinishedStorage refuse this.
+!finish_order : shortcut_pending <-
   ?order_id(OrderId);
   ?finished_id(FinishedId);
   .concat("order_",OrderId,SchName);
   lookupArtifact(SchName,SchId);
   .print("SHORTCUT: declaring the order built without all stations");
   goalAchieved(build)[artifact_id(SchId)];
   finishOrder(OrderId)[artifact_id(FinishedId)];
   -shortcut_pending.
+!finish_order <-
   ?order_id(OrderId);
   ?finished_id(FinishedId);
   !achieve_goal(OrderId,build);
   finishOrder(OrderId)[artifact_id(FinishedId)].

-!finish_order[error_msg(M)] : shortcut_pending <-
   -shortcut_pending;
   ?order_id(OrderId);
   ?regulation_id(RegId);
   ?fine("n_complete_assembly",Penalty);
   if (.substring("regimented",M)) {
      +shortcut_blocked;
      logDecision(OrderId,n_complete_assembly,"blocked",0,Penalty)[artifact_id(RegId)];
      .print("BLOCKED: the factory does not let me finish with stations missing (",M,") - back to the line");
   } else {
      +shortcut_blocked;
      logDecision(OrderId,n_complete_assembly,"failed",0,Penalty)[artifact_id(RegId)];
      .print("Shortcut failed for another reason: ",M);
   }
   !enter_next_station.
