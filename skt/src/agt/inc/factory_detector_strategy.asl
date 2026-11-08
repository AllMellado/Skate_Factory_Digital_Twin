// factory_detector_strategy.asl
// Overrides detector.asl's stub +!report. Unlike skt's detector_strategy.asl
// (which only reacts to OTHER agents' norm instances, via `Target \== Me` --
// appropriate for its peer-sanctioning model), each skateboard here is the
// sole target of its own norm instances, so it must react to its own outcomes directly.

+!report(NormInstance) <-
   norm(id(NormId),_,_,_,target(Target),_,_,_)[H|T] = NormInstance;
   .my_name(Me);
   if (.member(violation_time(VTime), [H|T])) {
      .print("VIOLATION: ", Me, " violated norm ", NormId);
      .send(quality_control,tell,norm_outcome(NormId,Me,violation,VTime));
      !evaluate(NormInstance);
   } else {
      if (.member(compliance_time(CTime), [H|T])) {
         .print("COMPLIANCE: ", Me, " complied with norm ", NormId);
         .send(quality_control,tell,norm_outcome(NormId,Me,compliance,CTime));
      }
   }.
