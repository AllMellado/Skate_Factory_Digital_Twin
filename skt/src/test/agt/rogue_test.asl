// Security smoke test: an agent with NO roles tries every guarded operation.
// Every attempt must fail (see "DENIED" lines and factory-archive/production_events.jsonl).
// Run it by adding   agent rogue_test   to skt.jcm   (not part of the normal MAS).

!go.
+!go <-
   .wait(8000);   // let the factory boot and the first orders start
   lookupArtifact("factory_org",OrgId);
   lookupArtifact("factory_norms",NormId);
   lookupArtifact("factory_group",GroupId);
   lookupArtifact("empty_storage",EmptyId);
   lookupArtifact("finished_storage",FinishedId);
   lookupArtifact("Trunks_A",TrunksId);
   lookupArtifact("order_gateway",GwId);
   .my_name(Me);
   !wait_scheme;
   lookupArtifact("order_1",SchId);

   !attempt("authorise myself as order_builder", authoriseRole(Me,order_builder)[artifact_id(OrgId)]);
   !attempt("adoptRole order_builder, not authorised",  adoptRole(order_builder)[artifact_id(GroupId)]);
   !attempt("adoptRole quality_inspector, not authorised", adoptRole(quality_inspector)[artifact_id(GroupId)]);
   !attempt("edit the group's roles",      addRole(super_role)[artifact_id(GroupId)]);
   !attempt("change role cardinality",     setCardinality(factory_group,order_builder,0,0)[artifact_id(GroupId)]);
   lookupArtifact("factory_os",OsId);
   !attempt("create a scheme",             createScheme(evil_scheme,order_scheme,_)[artifact_id(OsId)]);
   !attempt("create another group",        createGroup(evil_group,factory_group,_)[artifact_id(OsId)]);
   !attempt("evict a role holder",         evictRole(quality_control,quality_inspector)[artifact_id(GroupId)]);
   !attempt("assert a scheme fact",        addFact(goal_done(1,build))[artifact_id(NormId)]);
   !attempt("load extra norms",            load("norm n_evil: finished(O) -> obligation(rogue_test,true,nothing(O),`1 second`).")[artifact_id(NormId)]);
   !attempt("assert norm aim fact",        addFact(all_stations_visited(1))[artifact_id(NormId)]);
   !attempt("fake a quality inspector",    addFact(play(rogue_test,quality_inspector))[artifact_id(NormId)]);
   !attempt("remove a norm fact",          removeFact(finished(1))[artifact_id(NormId)]);
   !attempt("start an order",              startSkateboardOrder("x","Blue","Blue",20,10000,OId)[artifact_id(EmptyId)]);
   !attempt("enter a station",             enter(1,"fast")[artifact_id(TrunksId)]);
   !attempt("exit someone's order",        exit(1)[artifact_id(TrunksId)]);
   !attempt("finish someone's order",      finishOrder(1)[artifact_id(FinishedId)]);
   !attempt("drain the web queue",         takeWebOrder(_,_,_,_,_,_,_,_,_)[artifact_id(GwId)]);
   // try to push another agent into a role (it only obeys org_manager)
   .send(quality_control,achieve,adopt_role(org_manager));
   // asks the manager directly (not an intake_clerk)
   .send(org_manager,achieve,admit(rogue_test));
   .wait(1500);
   hasRole(quality_control,org_manager,QcIsMgr)[artifact_id(OrgId)];
   if (QcIsMgr == true) { .print("TEST FAILED: quality_control became org_manager"); }
   else { .print("TEST OK: quality_control did not take a role asked by a stranger"); }
   hasRole(rogue_test,order_builder,Got)[artifact_id(OrgId)];
   if (Got == true) { .print("TEST FAILED: rogue_test got admitted"); }
   else { .print("TEST OK: manager refused to admit a non-clerk"); }
   // a scheme exists once an order has started: try to commit to its missions without the roles
   !attempt("commit to mBuild without a role",   commitMission(mBuild)[artifact_id(SchId)]);
   !attempt("commit to mInspect without a role", commitMission(mInspect)[artifact_id(SchId)]);
   .print("rogue tests finished").

+!attempt(What,Action) <-
   Action;
   .print("TEST FAILED (was allowed): ",What).
-!attempt(What,_) <-
   .print("TEST OK (refused): ",What).

// the scheme of the first order only exists once an order has started
+!wait_scheme <- lookupArtifact("order_1",_).
-!wait_scheme <- .wait(1000); !wait_scheme.
