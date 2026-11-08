// skate_focus.asl -- what this skateboard cares about most.
//
// A skateboard has a focus profile focus_weights(Time,Energy,Quality), three
// numbers that sum to 1. It comes from the customer (web order) or, when
// nobody asked, is drawn at random so different skateboards behave differently.
// The profile changes the choice of station in three ways:
//   1. norm risks are re-weighted:  n_max_duration -> time,
//                                   n_energy_budget -> energy,
//                                   n_content_correct -> quality
//      (multiplier = 3 * weight, kept in [focus_min_mult, focus_max_mult] so
//       no norm is ever ignored completely);
//   2. a "soft" cost pushes the agent to save what it cares about even while
//      it is still far from violating a norm (shorter, cheaper, safer path);
//   3. the build speed: time-focused agents tend to be "fast" builders (quicker
//      but more fault-prone), quality-focused ones "slow".
// Tuning knobs are the beliefs below.

// The order's own budget (from the customer's price) wins over the policy-wide one.
eff_budget(B) :- order_energy_budget(B).
eff_budget(B) :- energy_budget(B).

// key of this agent's reproducible random draws: the order reference of a web order, else its name
speed_key(C) :- order_mode(given(C,_,_,_,_,_,_)).
speed_key(Me) :- .my_name(Me).

focus_strength(100).      // size of the soft cost next to a norm severity (~100)
focus_min_mult(0.5).
focus_max_mult(2.5).

focus_presets([balanced, time, energy, quality]).
focus_preset(balanced, 1, 1, 1).
focus_preset(time,     6, 2, 2).
focus_preset(energy,   2, 6, 2).
focus_preset(quality,  2, 2, 6).

norm_dimension(n_max_duration,    time).
norm_dimension(n_energy_budget,   energy).
norm_dimension(n_content_correct, quality).

// The customer's profile only counts if it came from a trusted intake agent.
+!choose_focus :
      order_mode(given(_,_,_,_,_,_,focus(T,E,Q)))[source(S)] & trusted_intake(L) & .member(S,L) & T + E + Q > 0 <-
   !set_focus(T,E,Q,"customer").

+!choose_focus <-
   ?focus_presets(Presets);
   .random(Presets,P);
   ?focus_preset(P,T,E,Q);
   !set_focus(T,E,Q,P).

+!set_focus(T,E,Q,Why) <-
   Sum = T + E + Q;
   Wt = T / Sum;
   We = E / Sum;
   Wq = Q / Sum;
   -+focus_weights(Wt,We,Wq);
   if (Wt >= We & Wt >= Wq & Wt > 0.4) {
      -+focus_label("time");
   } else {
      if (We >= Wt & We >= Wq & We > 0.4) {
         -+focus_label("energy");
      } else {
         if (Wq >= Wt & Wq >= We & Wq > 0.4) {
            -+focus_label("quality");
         } else {
            -+focus_label("balanced");
         }
      }
   }
   ?focus_label(Label);
   .print("Focus (",Why,"): ",Label," - time ",math.round(Wt*100),"%, energy ",math.round(We*100),"%, quality ",math.round(Wq*100),"%").

// Fast builders are quicker but more fault-prone: pick by time vs quality weight.
+!choose_speed <-
   ?focus_weights(Wt,_,Wq);
   ?speed_key(Key);
   jia.seeded_random(Key,R);
   if (R < Wt / (Wt + Wq)) {
      +speed("fast");
      .print("Fast Builder!");
   } else {
      +speed("slow");
      .print("Slow Builder!");
   }.

// Multiplier applied to the weighted risk of a norm.
+!focus_multiplier(Norm,M) <-
   ?focus_weights(Wt,We,Wq);
   ?norm_dimension(Norm,Dim);
   if (Dim == time) { P = Wt; } else { if (Dim == energy) { P = We; } else { P = Wq; } }
   M0 = 3 * P;
   ?focus_min_mult(Lo);
   ?focus_max_mult(Hi);
   if (M0 < Lo) { M = Lo; } else { if (M0 > Hi) { M = Hi; } else { M = M0; } }.

// Cost of how much time/energy/quality this candidate would spend, even when no
// norm is at risk yet. Each part is a fraction of the matching limit (capped at 1.5).
+!soft_cost(ctx(W,Sv,E,F,FSv,FE,FP),Soft) <-
   ?focus_weights(Wt,We,Wq);
   ?focus_strength(K);
   ?elapsed(Elapsed);
   ?order_deadline_ms(Deadline);
   TF0 = (Elapsed + W + Sv + FSv) / Deadline;
   if (TF0 > 1.5) { TF = 1.5; } else { TF = TF0; }
   ?energy_used(Used);
   if (eff_budget(Budget)) {
      EF0 = (Used + E + FE) / Budget;
      if (EF0 > 1.5) { EF = 1.5; } else { EF = EF0; }
   } else {
      EF = 0;
   }
   if (content_ok(true)) { QF = 1 - (1 - F) * FP; } else { QF = 0; }
   Soft = K * (Wt * TF + We * EF + Wq * QF).
