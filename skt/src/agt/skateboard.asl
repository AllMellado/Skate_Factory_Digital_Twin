
// Simulation Parameters
colors(["White", "Blue", "Green", "Orange"]).
torque_options([20, 30, 40, 50, 60, 70, 80, 90]).
base_stations(["Trunks", "Wheels", "Torque", "Optional"]).
deadline_options([20000, 40000, 60000, 80000, 100000]).

// Norms this skate is subject to (declared in src/org/factory-norms.npl).
// Each one is turned into a projected risk in [0,1] for every candidate
// station instance (see +!norm_risk); a candidate's score is the weighted sum.
//   n_complete_assembly -> structural: base_stations that are not visited yet are the only options
//   n_max_duration      -> projected finish time vs this order's own deadline
//   n_energy_budget     -> projected energy vs energy_budget (observed from quality_policy)
//   n_content_correct   -> chance that no pass (so far + still to come) applies a wrong torque/color
// Add another norm by giving it a norm_weight/2 and a +!norm_risk(<id>,Ctx,Risk) plan.
// Penalty declared on quality_policy's "severity" property; change the
// numbers there (or call setSeverity at runtime) to tell every skateboard
// directly which sanction is worse.
norm_weight(n_max_duration,      W) :- severity(_,W,_,_).
norm_weight(n_content_correct,   W) :- severity(_,_,W,_).
norm_weight(n_energy_budget,     W) :- severity(_,_,_,W).

norm_label(n_complete_assembly, "skipping a station").
norm_label(n_max_duration,      "missing the deadline").
norm_label(n_content_correct,   "getting the content wrong").
norm_label(n_energy_budget,     "going over the energy budget").

// Configuration
// Intake agents allowed to tell this skateboard what to build.
trusted_intake([skate_creator, order_intake]).

!start.
+!start <-
   // Nothing happens until org_manager has given this agent the order_builder
   // role and an intake agent has said what to build (order_mode).
   !await_admission(0);
   !regulation_setup;      // see inc/regulation_aware.asl (only active with -Dskt.regulation=1)
   !choose_focus;          // see inc/skate_focus.asl
   !choose_speed;
   !decide_spec;
   +visited([]);
   +energy_used(0);
   +content_ok(true);
   +done_count(0);
   !start_order.

-!start <-
   .my_name(Me);
   .print("Could not start the order (",Me,") - missing role or bad order?").

+!await_admission(_) :
      admitted_role(order_builder) & order_mode(_)[source(S)] & trusted_intake(L) & .member(S,L) <-
   .print("Admitted as order_builder").
+!await_admission(N) : N < 300 <-
   .wait(100);
   N1 = N + 1;
   !await_admission(N1).
+!await_admission(_) <-
   .my_name(Me);
   .print("Never admitted by org_manager - giving up");
   .kill_agent(Me).

// Web order: the customer already decided the specification.
+!decide_spec :
      order_mode(given(Customer,Front,Rear,Torque,Deadline,Price,_))[source(S)] & trusted_intake(L) & .member(S,L) <-
   +torque_setting(Torque);
   +wheel_color_front(Front);
   +wheel_color_rear(Rear);
   +client_name(Customer);
   +chosen_deadline(Deadline);
   +order_price(Price);
   +order_source("web").

// Original behaviour: the agent decides its own order specification.
+!decide_spec : colors(COLORS) & torque_options(TORQUE_OPTIONS) <-
   .my_name(Me);
   +client_name(Me);
   +order_price(100);
   +order_source("creator");
   .random(COLORS,Front);
   .random(COLORS,Rear);
   .random(TORQUE_OPTIONS,Torque);
   +torque_setting(Torque);
   +wheel_color_front(Front);
   +wheel_color_rear(Rear).

// head and starter of the order process
+!start_order <-
   .my_name(Me);
   ?torque_setting(Torque);
   ?wheel_color_front(Front);
   ?wheel_color_rear(Rear);
   ?client_name(Client);
   ?order_source(Source);
   ?order_price(Price);
   ?focus_weights(Wt,We,Wq);
   //joinWorkspace("main",MainWkspId);
   !join_main;  // inc/workspace.asl
   //!lookupFactory;
   lookupArtifact("station_registry",RegistryId);
   focus(RegistryId);
   -+registry_id(RegistryId);
   lookupArtifact("routing_rules",RoutingId);
   focus(RoutingId);
   -+routing_id(RoutingId);
   lookupArtifact("empty_storage",EmptyId);
   focus(EmptyId);
   lookupArtifact("finished_storage",FinishedId);
   focus(FinishedId);
   +finished_id(FinishedId);
   // observes energy_budget(B), the threshold of norm n_energy_budget
   lookupArtifact("quality_policy",PolicyId);
   focus(PolicyId);
   if (chosen_deadline(ChosenDeadline)) {
      Deadline = ChosenDeadline;
   } else {
      ?deadline_options(DeadlineOptions);
      .random(DeadlineOptions,Deadline0);
      jia.scaled_ms(Deadline0,Deadline);
   }
   cartago.invoke_obj("java.lang.System",currentTimeMillis,StartTime);
   -+order_start_time(StartTime);
   -+order_deadline_ms(Deadline);
   .print("Starting a New Order");
   startSkateboardOrder(Client,Front,Rear,Torque,Deadline,Source,Wt,We,Wq,Price,OrderId,Budget)[artifact_id(EmptyId)];
   +order_energy_budget(Budget);
   .random([1000,2000,3000,4000],X0);
   jia.scaled_ms(X0,X);
   .wait(X);
   -+order_id(OrderId);
   !enter_next_station.

+!lookupFactory <-
   lookupArtifact("trunks_station",TrunksId);
   focus(TrunksId);
   lookupArtifact("wheels_station",WheelsId);
   focus(WheelsId);
   lookupArtifact("torque_station",TorqueId);
   focus(TorqueId);
   lookupArtifact("optional_station",OptionalId);
   focus(OptionalId);
   +station_artifact("Trunks",TrunksId);
   +station_artifact("Wheels",WheelsId);
   +station_artifact("Torque",TorqueId);
   +station_artifact("Optional",OptionalId).


// Turns the raw scoring output into the skate explaining its own reasoning.
// e.g. "Choosing Wheels (Wheels_B) next, risk of missing the deadline 5%,
// getting the content wrong 20%, going over the energy budget 0%. Considered
// 7 other option(s); the next best was Torque_B at score 14.2."
+!explain_choice(pair(Type,ArtName),Score,Risks,Options) <-
   !norm_risk_text(Risks,RiskText);
   !alt_text(Options,ArtName,AltText);
   RoundedScore = math.round(Score*10)/10;
   ?focus_label(Focus);
   .print("Choosing ",Type," (",ArtName,") next - ",RiskText,". ",AltText," (score ",RoundedScore,", focus ",Focus,")").

+!norm_risk_text([],Text) <- Text = "no meaningful norm risk here".

+!norm_risk_text(Risks,Text) <- !norm_risk_parts(Risks,Parts); Text = Parts.

+!norm_risk_parts([risk(N,Pct)],Text) <-
   ?norm_label(N,Label);
   .concat("risk of ",Label," ",Pct,"%",Text).

+!norm_risk_parts([risk(N,Pct)|Rest],Text) <-
   ?norm_label(N,Label);
   !norm_risk_parts(Rest,RestText);
   .concat("risk of ",Label," ",Pct,"%, ",RestText,Text).

+!alt_text(Options,Chosen,Text) <-
   .findall(sc(I,S),(.member(opt(I,S),Options) & I \== Chosen),Rivals);
   if (Rivals == []) {
      Text = "it was the only option available";
   } else {
      !min_opt(Rivals,sc(BestI,BestS));
      .length(Rivals,N);
      RoundedBestS = math.round(BestS*10)/10;
      .concat("considered ",N," other option(s); the next best was ",BestI," at score ",RoundedBestS,Text);
   }.

+!min_opt([X],X) <- true.
+!min_opt([sc(I,S)|Rest],Best) <-
   !min_opt(Rest,sc(RI,RS));
   if (S <= RS) { Best = sc(I,S); } else { Best = sc(RI,RS); }.

// +!norm_label(N,L) <- norm_label(N,L).

// -!lookupFactory <-
//    .wait(500);
//    !lookupFactory.

+!collect_candidates([],[]) <- true.
+!collect_candidates([Type|Rest],AllCands) <-
   ?registry_id(RegId);
   getInstances(Type,Names)[artifact_id(RegId)];
   !pair_type(Type,Names,TypeCands);
   !collect_candidates(Rest,RestCands);
   !append_lists(TypeCands,RestCands,AllCands).

+!pair_type(_,[],[]) <- true.
+!pair_type(Type,[N|Ns],[pair(Type,N)|Rest]) <-
   !pair_type(Type,Ns,Rest).

+!append_lists([],L,L) <- true.
+!append_lists([H|T],L2,[H|R]) <-
   !append_lists(T,L2,R).


+!compute_urgency <-
   cartago.invoke_obj("java.lang.System",currentTimeMillis,Now);
   ?order_start_time(StartTime);
   ?order_deadline_ms(Deadline);
   Elapsed = Now - StartTime;
   Urgency = Elapsed / Deadline;
   -+elapsed(Elapsed);
   -+urgency(Urgency).

// loop that visists all station eventually
+!enter_next_station <-
   ?visited(V);
   ?base_stations(Base);
   !compute_urgency;
   !decide_next_station(Base,V,Next);
   if (Next == done) {
      !finish_order;          // inc/shortcut.asl: normal completion, or a shortcut attempt
   } else {
      Next = pair(Type,ArtName);
      ?order_id(OrderId);
      lookupArtifact(ArtName,ArtId);
      focus(ArtId);
      -+chosen_artifact(ArtId,OrderId);
      ?speed(Speed);
      .print("Entering ",Type," -> instance ",ArtName);
      enter(OrderId,Speed)[artifact_id(ArtId)];
   }.

// Decides the next station AND the instance of that station.
// Every instance of every station still to be visited is estimated once; the
// candidates (instances of unlocked stations) are then scored against the norms.
+!decide_next_station(Req,Visited,Next) <-
   .findall(S,(.member(S,Req) & not .member(S,Visited)),Remaining);
   if (Remaining == []) {
      Next = done;
   } else {
      !filter_unlocked(Remaining,Visited,Unlocked);
      if (Unlocked == []) {
         .print("WARNING: no unlocked station - check routing_rules for a cycle. Remaining: ",Remaining);
         .wait(500);
         !decide_next_station(Req,Visited,Next);
      } else {
         !collect_candidates(Remaining,AllInstances);
         ?speed(Speed);
         !estimate_all(AllInstances,Speed,Estimates);
         !best_per_type(Remaining,Estimates,Bests);
         !consider_shortcut(Remaining,Bests,Skip);      // inc/shortcut.asl: is skipping a station worth its sanction?
         if (Skip == true) {
            Next = done;
         } else {
            .findall(est(T,A,W,Sv,E,F),(.member(est(T,A,W,Sv,E,F),Estimates) & .member(T,Unlocked)),Candidates);
            !score_candidates(Candidates,Bests,Scored);
            !pick_min(Scored,scored(Next,BestScore,BestRisks));
            .findall(opt(Inst,Sc),.member(scored(pair(_,Inst),Sc,_),Scored),Options);
            !explain_choice(Next,BestScore,BestRisks,Options);
         }
      }
   }.

// One est(Type,Instance,WaitMs,ServiceMs,Energy,FaultProb) per instance.
+!estimate_all([],_,[]) <- true.
+!estimate_all([pair(Type,ArtName)|Rest],Speed,[est(Type,ArtName,W,Sv,E,F)|ERest]) <-
   lookupArtifact(ArtName,ArtId);
   getEstimate(Speed,W,Sv,E,F)[artifact_id(ArtId)];
   !estimate_all(Rest,Speed,ERest).

// Best case per still-to-visit type (cheapest instance for each figure);
// used as the optimistic forecast of the stations that come after the candidate.
+!best_per_type([],_,[]) <- true.
+!best_per_type([T|Ts],Ests,[best(T,Sv,E,F)|Rest]) <-
   .findall(X,.member(est(T,_,_,X,_,_),Ests),Svs);
   .min(Svs,Sv);
   .findall(X,.member(est(T,_,_,_,X,_),Ests),Es);
   .min(Es,E);
   .findall(X,.member(est(T,_,_,_,_,X),Ests),Fs);
   .min(Fs,F);
   !best_per_type(Ts,Ests,Rest).

// Forecast of everything except the candidate's own type:
// future(CandidateType,Bests,ServiceMsSum,EnergySum,ProbAllPassesCorrect)
+!future(_,[],0,0,1) <- true.
+!future(T,[best(T,_,_,_)|Rest],Sv,E,P) <- !future(T,Rest,Sv,E,P).
+!future(T,[best(T2,Sv2,E2,F2)|Rest],Sv,E,P) : T2 \== T <-
   !future(T,Rest,Sv1,E1,P1);
   Sv = Sv1 + Sv2;
   E = E1 + E2;
   P = P1 * (1 - F2).

// Lower score is better: weighted expected norm violation, plus the wait in
// seconds as a tie-breaker (so equally safe options pick the quicker one).
+!score_candidates([],_,[]) <- true.
+!score_candidates([est(Type,ArtName,W,Sv,E,F)|Rest],Bests,[scored(pair(Type,ArtName),Score,Risks)|SRest]) <-
   !future(Type,Bests,FSv,FE,FP);
   .findall(N,norm_weight(N,_),Norms);
   !norm_costs(Norms,ctx(W,Sv,E,F,FSv,FE,FP),NormCost,Risks);
   !soft_cost(ctx(W,Sv,E,F,FSv,FE,FP),SoftCost);
   Score = NormCost + SoftCost + W / 1000;
   !score_candidates(Rest,Bests,SRest).

+!norm_costs([],_,0,[]) <- true.
+!norm_costs([N|Ns],Ctx,Total,[risk(N,Pct)|Risks]) <-
   !norm_risk(N,Ctx,Risk);
   ?norm_weight(N,Wt);
   !focus_multiplier(N,Mult);    // this skateboard's focus scales how much the norm matters to it
   Pct = math.round(Risk * 100);
   !norm_costs(Ns,Ctx,RestTotal,Risks);
   Total = Wt * Mult * Risk + RestTotal.

// Risk of violating each norm if this candidate is chosen (0 = safe, 1 = violated).
// ctx(WaitMs,ServiceMs,Energy,FaultProb, FutureServiceMs,FutureEnergy,FutureProbCorrect)
+!norm_risk(n_max_duration,ctx(W,Sv,_,_,FSv,_,_),Risk) <-
   ?elapsed(Elapsed);
   ?order_deadline_ms(Deadline);
   Projected = Elapsed + W + Sv + FSv;
   !ramp(Projected,Deadline,Risk).

+!norm_risk(n_energy_budget,ctx(_,_,E,_,_,FE,_),Risk) <-
   ?energy_used(Used);
   if (eff_budget(Budget)) {
      Projected = Used + E + FE;
      !ramp(Projected,Budget,Risk);
   } else {
      Risk = 0;
   }.

+!norm_risk(n_content_correct,ctx(_,_,_,F,_,_,FP),Risk) <-
   if (content_ok(true)) {
      Risk = 1 - (1 - F) * FP;
   } else {
      Risk = 1;   // already violated: nothing left to protect
   }.

// 0 up to 90% of the limit, 1 from 110%, linear in between (keeps a safety margin).
+!ramp(Projected,Limit,Risk) <-
   V = (Projected - 0.9 * Limit) / (0.2 * Limit);
   if (V <= 0) {
      Risk = 0;
   } else {
      if (V >= 1) { Risk = 1; } else { Risk = V; }
   }.

+!pick_min([X],X) <- true.
+!pick_min([scored(C,Score,R)|Rest],Best) <-
   !pick_min(Rest,scored(RC,RScore,RR));
   if (Score <= RScore) {
      Best = scored(C,Score,R);
   } else {
      Best = scored(RC,RScore,RR);
   }.

+!filter_unlocked([],_,[]) <- true.
+!filter_unlocked([S|Rest],Visited,Result) <-
   ?routing_id(RoutingId);
   getRequiredPredecessors(S,Prereqs)[artifact_id(RoutingId)];
   .findall(P,(.member(P,Prereqs) & not .member(P,Visited)),Missing);
   !filter_unlocked(Rest,Visited,RestResult);
   if (Missing == []) {
      Result = [S|RestResult];
   } else {
      Result = RestResult;
   }.

// Processing (reactive: fires once the twin confirms work has actually started)
+station_started(Station,TableId,OrderId) : order_id(OrderId) <-
   !process_station(Station,TableId).

+!process_station(Station,TableId) <-
   .print("Processing ",Station," on ",TableId);
   . // nothing to actively do while the twin's timer runs; kept as its own
         // plan so a future strategy has a real place to react mid-processing.

// The twin signals station_done once the station finishes
+station_done(Station,OrderId,TorqueApplied,ColorApplied,RequirementMet,EnergyUsed) : order_id(OrderId) <-
   if (not RequirementMet) { -+requirements_ok(false); }
   .print(Station," done - torque=",TorqueApplied," color=",ColorApplied," energy=",EnergyUsed," reqs=",RequirementMet);
   // Bookkeeping the norm projections rely on (same rules quality_control applies at the end).
   ?energy_used(Used);
   NewUsed = Used + EnergyUsed;
   -+energy_used(NewUsed);
   ?done_count(N);
   ?torque_setting(TorqueWanted);
   ?wheel_color_front(ColorFront);
   ?wheel_color_rear(ColorRear);
   if (N == 0) { ColorWanted = ColorFront; } else { ColorWanted = ColorRear; }
   if (TorqueApplied \== TorqueWanted | ColorApplied \== ColorWanted) { -+content_ok(false); }
   N1 = N + 1;
   -+done_count(N1);
   // Decide the next station only now, so energy/content above are up to date.
   !enter_next_station.
//    ?visited(V);
//    -+visited([Station|V]);
//    !exit_station(Station).

+station_ready_to_exit(Station,OrderId) : order_id(OrderId) <- 
   if (not RequirementMet) { -+requirements_ok(false); }
   ?visited(V);
   -+visited([Station|V]);
   !exit_station(Station).

+!exit_station(Station) <-
   .print("Exiting Station ",Station);
   ?chosen_artifact(ArtId,OrderId);
   exit(OrderId)[artifact_id(ArtId)];
   ?visited(V);
   -+visited([Station|V]).
   // the next station is chosen in +station_done, right after the station reports its result

{ include("skate_focus.asl") }
{ include("regulation_aware.asl") }
{ include("shortcut.asl") }
{ include("org_member.asl") }
{ include("workspace.asl") }
{ include("factory_detector_strategy.asl") }
{ include("factory_sanction_plans.asl") }
{ include("$jacamoJar/templates/common-cartago.asl") }
