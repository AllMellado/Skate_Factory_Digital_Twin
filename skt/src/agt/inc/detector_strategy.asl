+!report(NormInstance) <- 
	norm(_,_,_,_,target(Target),_,_,_)[H|T] = NormInstance;
   .my_name(Me);
   if (.member(violation_time(_), [H|T]) & Target \== Me) {
       .print("VIOLATION detected: ", Target," violated norm ", NormInstance);
       !evaluate(NormInstance);    // existing sanctioning path
   } else {
       if (.member(compliance_time(_), [H|T]) & Target \== Me) {
           .print("COMPLIANCE: ", Target," followed norm ", NormInstance);
       }
   }.