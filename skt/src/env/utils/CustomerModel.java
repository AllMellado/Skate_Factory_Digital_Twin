package skt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Customer requirements -> focus profile, plus learning from past orders.
 *
 * A customer states a price and a delivery deadline. From them we derive
 *   - an energy budget for the order (a cheaper order leaves less room for energy),
 *   - a prior focus profile (tight deadline -> time, low price -> energy, high price -> quality),
 * and, in mode "learned", adapt the profile per customer from the outcomes of their earlier orders.
 *
 * Modes (-Dskt.profile=): random | static | derived | learned (default)
 *   random   no profile is supplied; the agent draws a preset (original behaviour)
 *   static   balanced for everyone
 *   derived  prior from price and deadline
 *   learned  derived prior, multiplied by exp(theta[customer]); theta moves toward the dimensions
 *            that the customer's previous orders violated
 */
public final class CustomerModel {
    private CustomerModel() {}

    public static final String MODE = System.getProperty("skt.profile", "learned");

    // price range accepted from customers and the factory-time deadlines that count as tight / relaxed
    public static final double PRICE_MIN = 50, PRICE_MAX = 150;
    static final double D_TIGHT = 30_000, D_RELAXED = 90_000;          // factory ms
    static final double ETA = 0.6, THETA_CLIP = 2.0, FLOOR = 0.15;     // learning rate, bound, weight floor

    /** Energy budget [units] an order of this price can use. */
    public static double energyBudget(double price) {
        double p = clip((price - PRICE_MIN) / (PRICE_MAX - PRICE_MIN), 0, 1);
        return 40 + 50 * p;                                              // 40 .. 90
    }

    /** Prior weights (time, energy, quality) from the stated requirements; not normalised. */
    static double[] prior(double price, double deadlineFactoryMs) {
        double urgency = clip((D_RELAXED - deadlineFactoryMs) / (D_RELAXED - D_TIGHT), 0, 1);
        double p = clip((price - PRICE_MIN) / (PRICE_MAX - PRICE_MIN), 0, 1);
        return new double[] { FLOOR + (1 - FLOOR) * urgency, FLOOR + (1 - FLOOR) * (1 - p), FLOOR + (1 - FLOOR) * p };
    }

    private static final Map<String, double[]> THETA = new ConcurrentHashMap<>();
    private static final Map<String, Integer> SEEN = new ConcurrentHashMap<>();

    /** Weights for a new order; all zeros means "no profile, let the agent draw one". */
    /** Customer identity: the part before an optional '#reference' (customer#ref names one order of a customer). */
    public static String identity(String customer) { int i = customer.indexOf('#'); return i < 0 ? customer : customer.substring(0, i); }

    public static double[] weights(String customer, double price, double deadlineRealMs) {
        customer = identity(customer);
        switch (MODE) {
            case "random":  return new double[] { 0, 0, 0 };
            case "static":  return new double[] { 1, 1, 1 };
            default: break;
        }
        double[] w = prior(price, FactoryConfig.factoryMs(deadlineRealMs));
        if ("learned".equals(MODE)) {
            double[] th = THETA.getOrDefault(customer, new double[3]);
            for (int i = 0; i < 3; i++) w[i] *= Math.exp(th[i]);
        }
        return w;
    }

    /** Called when an order of this customer finished: shift weight toward what went wrong. */
    public static synchronized void record(String customer, boolean deadlineOk, boolean energyOk, boolean contentOk) {
        double[] v = { deadlineOk ? 0 : 1, energyOk ? 0 : 1, contentOk ? 0 : 1 };
        double mean = (v[0] + v[1] + v[2]) / 3.0;
        double[] th = THETA.computeIfAbsent(customer, k -> new double[3]);
        for (int i = 0; i < 3; i++) th[i] = clip(th[i] + ETA * (v[i] - mean), -THETA_CLIP, THETA_CLIP);
        SEEN.merge(customer, 1, Integer::sum);
    }

    public static JSONObject snapshot() {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, double[]> e : THETA.entrySet())
            out.put(e.getKey(), new JSONObject().put("theta", new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]).put(e.getValue()[2])).put("orders_seen", SEEN.getOrDefault(e.getKey(), 0)));
        return out;
    }

    /** What happened to a finished order against what its customer asked for. */
    public static JSONObject evaluate(JSONObject order, long cycleMs) {
        JSONArray logs = order.getJSONArray("production_logs");
        java.util.List<JSONObject> done = new java.util.ArrayList<>();
        for (int i = 0; i < logs.length(); i++) {
            JSONObject l = logs.getJSONObject(i);
            if ("COMPLETED".equals(l.optString("new_status"))) done.add(l);
        }
        double energy = 0; boolean contentOk = true; int pass = 0;
        for (JSONObject l : done) {
            JSONObject p = l.getJSONObject("data_payload");
            energy += p.optDouble("energy_used", 0);
            pass++;
            String expected = pass == 1 ? order.getString("wheel_color_front") : order.getString("wheel_color_rear");
            if (p.optInt("torque_applied", -1) != order.getInt("torque_setting") || !expected.equals(p.optString("color_applied"))) contentOk = false;
        }
        double budget = order.optDouble("energy_budget", 1e9);
        return new JSONObject().put("cycle_ms", cycleMs).put("energy_used", Math.round(energy * 10) / 10.0)
                .put("deadline_ok", cycleMs <= order.optLong("deadline_ms", Long.MAX_VALUE))
                .put("energy_ok", energy <= budget).put("content_ok", contentOk);
    }

    private static double clip(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }
}
