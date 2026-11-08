+!monitor_outcome(SA): 
		sanction_application(
		id(SAId),
		time(SATime),
		decision_id(SDId),
		executor(Me)
	) = SA <-
	?sanction_decision(
		id(SDId),
		_,
		_,
		_,
		_,
		norm(
			id(NormId),_,_,_,target(NormTarget),_,_,_
		),
		_,
		_
	);
	NormToMonitorWithoutAnnot = norm(
		id(NormId),_,_,_,target(NormTarget),_,_,_
	);
	NormToMonitorWithAnnot = norm(
		id(NormId),_,_,_,target(NormTarget),_,_,_
	)[H|T];
	?current_round(ApplicationRound);
	.wait({+NormToMonitorWithoutAnnot}); // activation
	.wait({+NormToMonitorWithAnnot}); // violation/compliance
	if ( .member(deactivation_time(_),[H|T]) ) {
		// Keep monitoring
		!!monitor_outcome(SA);
	} else {
		cartago.invoke_obj("java.lang.System",currentTimeMillis,SOTime);
		?current_round(CurrentRound);
		?indeterminate_efficacy_after(MaxWait);
		SO = sanction_outcome(
			id(_),
			time(SOTime),
			application_id(SAId),
			controller(Me),
			efficacy(Efficacy)
		);
		if ( CurrentRound > (ApplicationRound + MaxWait) ) {
			Efficacy = indeterminate;
		} else {
			if ( .member(violation_time(_),[H|T]) ) {
				Efficacy = effective;
			} else {
				Efficacy = ineffective;
			}
		}
		addOutcome(SO);
	}.