package jia;

import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.NumberTermImpl;
import jason.asSyntax.Term;

/** jia.seeded_random(Key, R): uniform [0,1); reproducible per Key when -Dskt.seed is set. */
public class seeded_random extends DefaultInternalAction {
    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {
        double r = skt.FactoryConfig.rng("agent", args[0].toString()).nextDouble();
        return un.unifies(args[1], new NumberTermImpl(r));
    }
}
