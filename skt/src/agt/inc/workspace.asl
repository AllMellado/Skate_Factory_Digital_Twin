// workspace.asl -- every agent declared in a .jcm is already inside the workspace "main". Asking to join it again is refused by
// newer CArtAgO ("Join Workspace error: null", JaCaMo 1.3.x) and tolerated by older ones. Either way the agent ends up in "main",
// so a refusal is not an error: it must not stop the plan that asked (it stopped quality_control's startup and every skateboard's
// start_order: "Could not start the order - missing role or bad order?").
+!join_main <- joinWorkspace("main",_).
-!join_main <- true.