// Security smoke test for the regulation board (run with -Dskt.regulation=1; add `agent regulation_rogue_test` to skt_regulation.jcm).
// An agent with NO roles tries to rewrite the regulation, forge verdicts/sanctions and dodge regimentation. Every attempt must fail.
!go.
+!go <-
   .wait(8000);
   lookupArtifact("regulation",RegId);
   lookupArtifact("finished_storage",FinishedId);
   .wait(6000);                       // let a first order exist
   .my_name(Me);
   !attempt("amend the regulation (fine 0, no regimentation)", amendRegulation(n_complete_assembly,0,false,"I want it cheap")[artifact_id(RegId)]);
   !attempt("forge an inspection verdict", recordInspection(1,Me,n_complete_assembly,false)[artifact_id(RegId)]);
   !attempt("apply a sanction to somebody", applySanction(1,n_complete_assembly)[artifact_id(RegId)]);
   !attempt("log a decision on someone else's order", logDecision(1,n_complete_assembly,"comply",0,0)[artifact_id(RegId)]);
   !attempt("finish someone's order", finishOrder(1)[artifact_id(FinishedId)]);
   .print("regulation rogue tests finished").
+!attempt(What,Action) <- Action; .print("TEST FAILED (was allowed): ",What).
-!attempt(What,_) <- .print("TEST OK (refused): ",What).
