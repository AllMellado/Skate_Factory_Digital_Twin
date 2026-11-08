// NOTE: deliberately in package ora4mas.nopl. NormativeBoard.removeFact is
// package-private, and Java only lets a subclass override it from the same
// package. (Overriding addFact alone would leave removeFact open: an agent
// could delete an aim fact and make an obligation fail for someone else.)
package ora4mas.nopl;

import cartago.OPERATION;
import jason.asSyntax.parser.ParseException;
import npl.NormativeFailureException;
import skt.OrgGuard;

/**
 * NormativeBoard whose facts can only be asserted/removed by agents with the
 * proper role. Facts are how a norm is activated and how its aim is completed,
 * so guarding them guards "completing a norm":
 *   play(Agent,Role)   -> permission assert_org_fact  (role org_manager)
 *   anything else      -> permission assert_norm_fact (role quality_inspector)
 */
public class SecureNormativeBoard extends NormativeBoard {

    @OPERATION
    @Override
    public void addFact(String fact) throws ParseException, NormativeFailureException {
        String denied = OrgGuard.checkFact(getCurrentOpAgentId().getAgentName(), fact, "addFact");
        if (denied != null) { failed(denied); return; }
        super.addFact(fact);
    }

    @OPERATION
    @Override
    void removeFact(String fact) throws ParseException, NormativeFailureException {
        String denied = OrgGuard.checkFact(getCurrentOpAgentId().getAgentName(), fact, "removeFact");
        if (denied != null) { failed(denied); return; }
        super.removeFact(fact);
    }

    /** Loading extra norms changes what is obligatory: org_manager only. */
    @OPERATION
    @Override
    public void load(String nplSourceOrFile) throws moise.common.MoiseException, npl.parser.ParseException {
        String denied = OrgGuard.checkAdmin(getOpUserName(), "NormativeBoard.load");
        if (denied != null) { failed(denied); return; }
        super.load(nplSourceOrFile);
    }

    /**
     * Loads the norms of the factory from an NPL file (src/org/factory-os.npl). Moise 0.13 does not load norms from the
     * organisational specification into the board created from it (the board starts with an empty program), so the manager
     * loads them from this file. The file is the single source of the norms.
     *
     * Conveniences, so the file can be written the way it reads best:
     *   - norm windows are written in factory seconds (`5 seconds`) and are converted here to (scaled) milliseconds;
     *   - the XML entities &amp; &lt; &gt; are decoded, so a norm copied out of an XML file still parses;
     *   - a sanction rule may be written with braces,   sanction-rule srs1(Ag, X) { -> sanction(Ag, increase(X)). }
     *     The NPL parser (every Moise/JaCaMo version so far) has no braces there, it wants
     *                                                    sanction-rule srs1(Ag, X) -> sanction(Ag, increase(X)).
     *     so the braces are removed here; a rule already in the parser's form is left alone.
     */
    @OPERATION
    public void loadNormsFromFile(String nplFile) throws Exception {
        String denied = OrgGuard.checkAdmin(getOpUserName(), "NormativeBoard.loadNormsFromFile");
        if (denied != null) { failed(denied); return; }
        java.nio.file.Path path = java.nio.file.Paths.get(nplFile);
        if (!java.nio.file.Files.isReadable(path)) { failed("no_such_npl_file:" + nplFile); return; }
        String norms = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8)
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        // "sanction-rule head { -> body. }"  ->  "sanction-rule head -> body."  (the head may not contain "->": that is the parser's own form)
        norms = java.util.regex.Pattern.compile("(sanction-rule\\s+(?:(?!->)[^{}])*?)\\{\\s*([^{}]*?)\\s*\\}", java.util.regex.Pattern.DOTALL)
                .matcher(norms).replaceAll("$1$2");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("`(\\d+(?:\\.\\d+)?) seconds?`").matcher(norms);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, "`" + Math.max(1, Math.round(Double.parseDouble(m.group(1)) * 1000 / skt.FactoryConfig.SCALE)) + " milliseconds`");
        m.appendTail(sb);
        norms = sb.toString();
        String text = norms.contains("scope") ? norms : "scope main {\n" + norms + "\n}";
        String plain = withoutSanctions(text);
        if (!"off".equalsIgnoreCase(System.getProperty("skt.npl.sanctions", "auto"))) {
            try {
                super.load(text);
                return;
            } catch (Exception e) {
                if (!(e instanceof npl.parser.ParseException) || plain.equals(text)) throw e;     // not about sanctions: report it
                System.err.println("[NormativeBoard] this NPL version does not accept the sanction syntax (" + String.valueOf(e.getMessage()).split("\n")[0]
                        + "): loading " + nplFile + " without its sanction clauses and sanction-rules");
            }
        }
        super.load(plain);
    }

    /** The same program without `if fulfilled|unfulfilled|inactive: rule(..)` clauses and without `sanction-rule ... .` definitions. */
    private static String withoutSanctions(String npl) {
        String s = npl.replaceAll("\\s+if\\s+(?:fulfilled|unfulfilled|inactive)\\s*:\\s*\\w+\\((?:[^()]|\\([^()]*\\))*\\)", "");
        return s.replaceAll("(?s)sanction-rule\\b.*?\\.(?=\\s|$)", "");
    }
}
