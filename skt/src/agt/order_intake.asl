// order_intake: turns web orders (queued by the HTTP API) into skateboard agents.
// Same path as skate_creator: create the agent, tell it its order, and ask
// org_manager to admit it. The agent only starts once the manager has given it
// the order_builder role.
// Needs role intake_clerk (given by org_manager).

{ include("org_member.asl") }

next_agent_seq(1).

+!start <-
   lookupArtifact("order_gateway",GwId);
   +gateway_id(GwId);
   .print("Order intake online - waiting for web orders");
   !serve.

+!serve <-
   ?gateway_id(GwId);
   while (true) {
      takeWebOrder(Ticket,Customer,Front,Rear,Torque,Deadline,Wt,We,Wq,Price)[artifact_id(GwId)];
      !handle_web_order(Ticket,Customer,Front,Rear,Torque,Deadline,Wt,We,Wq,Price);
   }.

-!serve <-
   .print("Intake interrupted (is order_intake an intake_clerk?) - retrying in 5s");
   .wait(5000);
   !serve.

// Focus profile: the customer's explicit choice wins; otherwise the advisor derives one from price and
// deadline (and, in learned mode, from the outcomes of this customer's earlier orders).
+!profile_for(Customer,Price,Deadline,Wt,We,Wq,Wt1,We1,Wq1) : Wt + We + Wq > 0 <- Wt1 = Wt; We1 = We; Wq1 = Wq.
+!profile_for(Customer,Price,Deadline,_,_,_,Wt1,We1,Wq1) <-
   lookupArtifact("profile_advisor",AdvId);
   advise(Customer,Price,Deadline,Wt1,We1,Wq1,_)[artifact_id(AdvId)].

+!handle_web_order(Ticket,Customer,Front,Rear,Torque,Deadline,Wt0,We0,Wq0,Price) <-
   ?gateway_id(GwId);
   !profile_for(Customer,Price,Deadline,Wt0,We0,Wq0,Wt,We,Wq);
   ?next_agent_seq(N);
   N1 = N + 1;
   -+next_agent_seq(N1);
   .concat("web_skateboard",N,Name);
   .print("Web order ",Ticket," from ",Customer," -> ",Name);
   .create_agent(Name,"skateboard.asl");
   .send(Name,tell,order_mode(given(Customer,Front,Rear,Torque,Deadline,Price,focus(Wt,We,Wq))));
   registerTicketAgent(Ticket,Name)[artifact_id(GwId)];
   +ticket_of(Name,Ticket);
   .send(org_manager,achieve,admit(Name)).

+admission_denied(Name)[source(org_manager)] : ticket_of(Name,Ticket) <-
   ?gateway_id(GwId);
   rejectTicket(Ticket,"admission_denied")[artifact_id(GwId)];
   .print("Ticket ",Ticket," rejected: admission of ",Name," denied").
