package jia;

import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.NumberTermImpl;
import jason.asSyntax.NumberTerm;
import jason.asSyntax.Term;

/** jia.sysprop(Name, Default, Value): numeric JVM property (-Dname=...), or Default when it is not set. */
public class sysprop extends DefaultInternalAction {
    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {
        String v = System.getProperty(args[0].toString().replace("\"", ""));
        double d = v == null ? ((NumberTerm) args[1]).solve() : Double.parseDouble(v);
        return un.unifies(args[2], new NumberTermImpl(d));
    }
}
