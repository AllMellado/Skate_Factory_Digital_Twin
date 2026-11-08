package skt;

import java.util.Random;

/**
 * Run-wide settings, read from JVM system properties so experiments can change them without code edits.
 *   -Dskt.timescale=N   all factory durations (dwell, processing, norm windows, customer deadlines)
 *                       are divided by N. The model is unchanged; only the time unit shrinks.
 *   -Dskt.seed=S        makes the exogenous randomness (processing times, faults, degradation, speed
 *                       choice) a pure function of S and of what is being drawn for (order, station,
 *                       pass), so it does not depend on thread scheduling. Unset = unseeded.
 */
public final class FactoryConfig {
    private FactoryConfig() {}

    public static final double SCALE = Double.parseDouble(System.getProperty("skt.timescale", "1"));
    public static final boolean SEEDED = System.getProperty("skt.seed") != null;
    public static final long SEED = Long.getLong("skt.seed", 0L);

    /** Real milliseconds for a duration expressed in factory milliseconds. */
    public static int ms(int factoryMs) { return (int) Math.max(1, Math.round(factoryMs / SCALE)); }

    /** Factory milliseconds for a real duration (inverse of ms). */
    public static double factoryMs(double realMs) { return realMs * SCALE; }

    /** Random source for one specific purpose; reproducible when seeded. */
    public static Random rng(Object... key) {
        if (!SEEDED) return new Random();
        long h = SEED * 0x9E3779B97F4A7C15L + 0x7F4A7C15L;
        for (Object k : key) {
            for (char c : String.valueOf(k).toCharArray()) h = (h ^ c) * 0x100000001B3L;
            h ^= (h >>> 29); h *= 0xBF58476D1CE4E5B9L; h ^= (h >>> 32);
        }
        return new Random(h);
    }
}
