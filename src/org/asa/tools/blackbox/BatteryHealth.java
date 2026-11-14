package org.asa.tools.blackbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Internal resistance of the battery (plus its connectors, switch and wires) from what the hubs measure anyway:
 * when the current jumps by dI the voltage drops by R * dI. Each pair of readings taken close together with a big
 * enough current change gives one slope; the median of all slopes is the estimate, so noise and odd samples do not
 * move it. A healthy FTC battery and wiring is roughly 0.05-0.15 ohm; a tired battery or a loose connector shows as
 * a higher value than the same battery had before.
 */
public class BatteryHealth {

    /** Pairs closer in time than this are compared (seconds). */
    static final double MAX_DT = 0.3;
    /** Current change needed for a usable slope (amps). Smaller steps are mostly noise. */
    static final double MIN_DI = 1.5;

    private final List<Double> slopes = new ArrayList<Double>();
    private double lastT = Double.NaN, lastV, lastI;
    private double startV = Double.NaN, minV = Double.POSITIVE_INFINITY, ampSeconds;

    /** One reading of battery voltage and total current, taken together (the same instant). */
    public synchronized void feed(double timeS, double volts, double amps) {
        if (Double.isNaN(volts) || Double.isNaN(amps) || volts <= 0) return;
        if (Double.isNaN(startV)) startV = volts;
        minV = Math.min(minV, volts);
        if (!Double.isNaN(lastT)) {
            double dt = timeS - lastT;
            if (dt > 0 && dt < 5) ampSeconds += (amps + lastI) / 2 * dt;
            double dI = amps - lastI;
            if (dt > 0 && dt <= MAX_DT && Math.abs(dI) >= MIN_DI) {
                double r = -(volts - lastV) / dI;
                if (r > -0.5 && r < 2) slopes.add(r);           // a slope outside this is a glitch, not a battery
            }
        }
        lastT = timeS; lastV = volts; lastI = amps;
    }

    /** Estimated resistance in ohms, NaN until there are enough current steps (driving gives plenty). */
    public synchronized double resistance() {
        if (slopes.size() < 8) return Double.NaN;
        List<Double> s = new ArrayList<Double>(slopes);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    public synchronized int samples() { return slopes.size(); }
    public synchronized double startVolts() { return startV; }
    public synchronized double minVolts() { return minV == Double.POSITIVE_INFINITY ? Double.NaN : minV; }
    public synchronized double ampHours() { return ampSeconds / 3600; }

    /** Plain verdict on an absolute scale; the comparison with the same battery's history is in the report. */
    public static String verdict(double ohms) {
        if (Double.isNaN(ohms)) return "not enough data (drive more)";
        if (ohms < 0.15) return "good";
        if (ohms < 0.25) return "watch it";
        return "weak: check connectors, then replace";
    }
}
