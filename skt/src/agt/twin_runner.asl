// One line per station instance: type, instance id, Java class, capacity,
// and any extra tuning. Add/remove instances only here.
// proc_time(Min,Max): nominal processing time [factory ms]; energy(E): units per pass at nominal speed
// (fast mode costs x1.7, slow mode x0.8). Faster instances are the hungrier ones, so a skateboard has to
// trade time against energy, and speed mode against quality.
stations([
   station("Trunks",   "A", "skt.TrunksStation",   1, [proc_time(3000,8000), energy(8)]),
   station("Trunks",   "B", "skt.TrunksStation",   1, [proc_time(1800,5000), energy(14), degrade(0.2)]),
   station("Trunks",   "C", "skt.TrunksStation",   1, [proc_time(2000,4000), energy(12)]),
   station("Wheels",   "A", "skt.WheelsStation",   1, [proc_time(3000,4000), energy(10)]),
   station("Wheels",   "B", "skt.WheelsStation",   1, [proc_time(1800,2500), energy(18)]),
   station("Torque",   "A", "skt.TorqueStation",   1, [proc_time(2000,6000), energy(9), fault(0.05)]),
   station("Torque",   "B", "skt.TorqueStation",   1, [proc_time(1500,4500), energy(12)]),
   station("Torque",   "C", "skt.TorqueStation",   1, [proc_time(1000,3000), energy(16)]),
   station("Optional", "A", "skt.OptionalStation", 1, [proc_time(3000,5000), energy(12)]),
   station("Optional", "B", "skt.OptionalStation", 1, [proc_time(1000,8000), energy(18)])
]).

!start.
+!start <-
   makeArtifact("station_registry", "skt.StationRegistry", [], RegistryId);
   focus(RegistryId);

   makeArtifact("empty_storage", "skt.EmptyStorage", [8088], EmptyId);
   focus(EmptyId);

   ?stations(Stations);
   !make_stations(Stations,RegistryId);

   makeArtifact("finished_storage", "skt.FinishedStorage", [], FinishedId);
   focus(FinishedId);

   makeArtifact("routing_rules", "skt.RoutingRules", [], RoutingId);
   focus(RoutingId); 
   // The agent should use norms not an artifact to chose the path
   // setRequiredPredecessors("Wheels", ["Trunks","Optional"])[artifact_id(RoutingId)];
   // setRequiredPredecessors("Trunks", ["Optional"])[artifact_id(RoutingId)];

   makeArtifact("quality_policy", "skt.QualityPolicy", [], PolicyId);
   focus(PolicyId);

   // scenario "regulation adaptation" (-Dskt.regulation=1): the versioned regulation, R1 read from its XML
   jia.sysprop("skt.regulation",0,RegMode);
   if (RegMode > 0) {
      makeArtifact("regulation", "skt.RegulationBoard", ["src/org/regulation-R1.xml"], RegulationId);
      focus(RegulationId);
   }

   // web orders: the HTTP API (EmptyStorage, port 8088) queues them here
   makeArtifact("order_gateway", "skt.OrderGateway", [], GatewayId);
   makeArtifact("profile_advisor", "skt.ProfileAdvisor", [], AdvisorId);

   // org_manager bootstraps itself; wait until the roles are in place before
   // anyone that needs one starts.
   !await_org;
   .send(org_manager, achieve, watch_factory);

   .print("Twin running. Open http://localhost:8088 in a browser to watch it.");
   if (RegMode > 0) { .send(regulator, achieve, start); }
   .send(quality_control, achieve, start);
   .wait(500);
   .send(order_intake, achieve, start);
   .send(skate_creator, achieve, start).

+!await_org <-
   lookupArtifact("factory_org",OrgId);
   focus(OrgId);
   !wait_org_ready.
-!await_org <-
   .wait(200);
   !await_org.

+!wait_org_ready : org_ready(true) <- true.
+!wait_org_ready <- .wait(200); !wait_org_ready.

// Creates, focuses and registers every station in the list. The artifact
// name is derived once (Type_Id), so it's guaranteed identical between
// makeArtifact and registerInstance -- no more hand-typed name to mismatch.
+!make_stations([],_) <- true.
+!make_stations([station(Type,Id,Class,Cap,Configs)|Rest],RegistryId) <-
   .concat(Type,"_",Id,ArtName);
   makeArtifact(ArtName,Class,[Id,Cap],ArtId);
   focus(ArtId);
   registerInstance(Type,ArtName)[artifact_id(RegistryId)];
   !apply_configs(Configs,ArtId);
   !make_stations(Rest,RegistryId).

// Extra per-instance tuning, one clause per kind of knob. Add a clause here
// only when you need a NEW kind of setting; using an existing one is just
// adding an entry to a station's config list above.
+!apply_configs([],_) <- true.
+!apply_configs([C|Cs],ArtId) <- !apply_config(C,ArtId); !apply_configs(Cs,ArtId).

+!apply_config(proc_time(Min,Max),ArtId) <- setProcessingTimeBounds(Min,Max)[artifact_id(ArtId)].
+!apply_config(fault(P),ArtId)           <- setFaultProbability(P)[artifact_id(ArtId)].
+!apply_config(req_pass(P),ArtId)        <- setRequirementPassRate(P)[artifact_id(ArtId)].
+!apply_config(energy(E),ArtId)          <- setEnergyCost(E)[artifact_id(ArtId)].
+!apply_config(degrade(P),ArtId)         <- setDegradationChance(P)[artifact_id(ArtId)].
+!apply_config(enter_dwell(Ms),ArtId)    <- setEnterDwellMs(Ms)[artifact_id(ArtId)].
+!apply_config(exit_dwell(Ms),ArtId)     <- setExitDwellMs(Ms)[artifact_id(ArtId)].