{ include("org_member.asl") }

total_skateboards(100).
possible_intervals_ms([2500,5000,10000,15000,20000]).

+!start <-
   ?total_skateboards(Default);
   jia.sysprop("skt.orders",Default,Total);     // -Dskt.orders=N overrides the number of random orders (0 = none)
   .print("Creating ", Total, " skateboards on a random timer.");
   !spawn_loop(1,Total).

+!spawn_loop(N,Total) : N > Total <-
   .print("Done! created ", Total, " skateboards.").

+!spawn_loop(N,Total) <-
   .concat("skateboard",N,Name);
   .create_agent(Name,"skateboard.asl");
   // same two steps as a web order: tell the agent its order, ask the manager to admit it
   .send(Name,tell,order_mode(random));
   .send(org_manager,achieve,admit(Name));
   .print("Created ", Name, " (", N, "/", Total, ")");
   N1 = N + 1;
   !wait_random_interval;
   !spawn_loop(N1,Total).
   
+!wait_random_interval <-
   ?possible_intervals_ms(Intervals);
   .random(Intervals,WaitMs);
   .wait(WaitMs).
