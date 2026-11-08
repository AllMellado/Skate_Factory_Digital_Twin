// factory_sanction_plans.asl
// Implements the <content> of each sanction declared in
// factory-regulative-spec.xml. executor.asl calls these by name
// (via !SContent) after evaluator.asl has decided to apply them.

+!flag_for_rework(Target) <-
   .print("*** REWORK REQUIRED for ", Target, " ***");
   .send(quality_control,tell,rework_flagged(Target)).

+!notify_supervisor(Target) <-
   .print("Supervisor notified about ", Target).

+!log_quality_metric(Target) <-
   .send(quality_control,tell,quality_metric_logged(Target)).
