// Authorisation expiry test (needs the test hook +!test_authorise in org_manager, see CHANGES.md).
// expiry_a adopts inside the authorisation's lifetime, expiry_b after it ran out.
!go.
+!go <-
   .my_name(Me);
   .wait(9000);
   .send(org_manager,achieve,test_authorise(Me));
   if (Me == expiry_a) { .wait(400); } else { .wait(4500); }
   lookupArtifact("factory_group",G);
   !try_adopt(Me,G).
+!try_adopt(Me,G) <- adoptRole(order_builder)[artifact_id(G)]; .print("EXPIRY RESULT ",Me,": adopted").
-!try_adopt(Me,_) <- .print("EXPIRY RESULT ",Me,": refused").
