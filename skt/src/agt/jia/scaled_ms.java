package jia;

import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.NumberTermImpl;
import jason.asSyntax.NumberTerm;
import jason.asSyntax.Term;

/** jia.scaled_ms(FactoryMs, RealMs): a factory duration in real ms under -Dskt.timescale. */
public class scaled_ms extends DefaultInternalAction {
    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {
        double f = ((NumberTerm) args[0]).solve();
        return un.unifies(args[1], new NumberTermImpl(skt.FactoryConfig.ms((int) f)));
    }
}
