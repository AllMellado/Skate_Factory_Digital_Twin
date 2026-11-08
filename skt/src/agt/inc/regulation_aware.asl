// regulation_aware.asl -- every agent that deals with the regulation board starts from here.
// The scenario is switched on with -Dskt.regulation=1 (default 0 = the factory behaves exactly as before).
// Focusing the artifact "regulation" gives the agent, as beliefs:
//   regulation(Version)   fine(Norm,Amount)   compliance(Version,Norm,Inspected,Violations)   sanctions(Version,Norm,Applied,Total)
+!regulation_setup <-
   jia.sysprop("skt.regulation",0,Mode);
   if (Mode > 0) {
      lookupArtifact("regulation",RegId);
      focus(RegId);
      +regulation_id(RegId);
      +regulation_on;
   }.
