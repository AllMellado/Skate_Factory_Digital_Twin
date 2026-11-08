// regulator: the agent that may amend the regulation (Moise role "regulator", permission amend_regulation,
// enforced by RegulationBoard). It watches what the regulation board publishes about the regulation in force and,
// when the evidence says the regulation does not work, enacts the next version.
//
// Evidence it needs before judging version V of a norm:
// (only when -Dskt.adapt is not 0)
//   - at least min_sample orders inspected under V
//   - at least min_sanctions sanctions actually applied (so the regulation was enforced...)
//   - a violation rate still >= trigger_rate (...and it still did not deter)
// Response (the adaptation policy):
//   - raise the fine: new fine = old fine x escalation, capped at max_fine
//   - regiment the norm when it is guardable, i.e. when it can be checked at the moment of the action
// Violations committed under V keep the fine of V (the board never applies a new fine retroactively).

{ include("org_member.asl") }
{ include("regulation_aware.asl") }

trigger_rate(0.2).
min_sample(20).
min_sanctions(3).
escalation(3).
max_fine(150).
guardable("n_complete_assembly").

// -Dskt.adapt=0: control run. The regulator still observes but never amends (R1 stays in force).
+!start <-
   !regulation_setup;
   jia.sysprop("skt.adapt",1,Adapt);
   if (Adapt > 0) { +adapt_on; }
   ?regulation(V);
   .print("Regulator online. Watching regulation ",V).

+compliance(V,Norm,_,_)[artifact_id(_)] <- !review(V,Norm).
+sanctions(V,Norm,_,_)[artifact_id(_)]  <- !review(V,Norm).

@review[atomic]
+!review(V,Norm) :
      adapt_on & regulation(V) & not amended(V,Norm) &
      compliance(V,Norm,I,Viol) & sanctions(V,Norm,S,_) &
      min_sample(MinI) & I >= MinI & min_sanctions(MinS) & S >= MinS &
      trigger_rate(Rate) & Viol / I >= Rate <-
   +amended(V,Norm);
   !amend(V,Norm,I,Viol,S).
+!review(_,_) <- true.

+!amend(V,Norm,I,Viol,S) <-
   ?fine(Norm,Fine);
   ?escalation(K);
   ?max_fine(Max);
   F0 = Fine * K;
   if (F0 > Max) { NewFine = Max; } else { NewFine = F0; }
   if (guardable(Norm)) { Regiment = true; } else { Regiment = false; }
   Pct = math.round(100 * Viol / I);
   .concat(V," inspected ",I," orders: ",Viol," violated (",Pct,"%) although ",S," were sanctioned; the sanction alone does not deter",Reason);
   ?regulation_id(RegId);
   amendRegulation(Norm,NewFine,Regiment,Reason)[artifact_id(RegId)];
   .print("REGULATION ADAPTED: ",Reason," -> fine ",Fine," -> ",NewFine,", regimented: ",Regiment).
