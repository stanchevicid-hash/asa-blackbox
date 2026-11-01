package org.asa.tools.blackbox;

import org.asa.tools.core.Live;
import org.asa.tools.core.Recorder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Flight recorder + health monitor.
 *
 *   BlackBox bb = BlackBox.create("teleop");
 *   FtcBlackBox.attachAll(bb, hardwareMap);         // every motor + battery, optional
 *   bb.channel("lift").signal("target", () -> target);
 *   bb.start();
 *   while (opModeIsActive()) { ...; bb.update(); }
 *   bb.stop();                                       // writes summary + baseline report
 *
 * Plain Java; FTC glue lives in org.asa.tools.ftc.FtcBlackBox.
 */
public class BlackBox implements Live {

    public final Recorder rec = new Recorder();
    private final String tag;
    private final List<Rule> rules = new ArrayList<Rule>();
    private final LinkedList<String> warnings = new LinkedList<String>();
    private final List<String> report = new ArrayList<String>();
    private final Map<String, Double> baseline = new HashMap<String, Double>();
    private File dir = Recorder.defaultDir();
    private long lastLoopNs;
    private double loopMs;

    public static BlackBox create(String tag) { return new BlackBox(tag); }

    private BlackBox(String tag) { this.tag = tag; }

    public BlackBox dir(File dir) { this.dir = dir; return this; }

    // ---------------------------------------------------------------- signals

    public BlackBox signal(String name, Recorder.Source s) { rec.signal(name, s); return this; }
    public BlackBox signal(String name, Recorder.Source s, int periodMs) { rec.signal(name, s, periodMs); return this; }

    /** Groups signals under "name." */
    public Channel channel(String name) { return new Channel(name); }

    public class Channel {
        private final String prefix;
        Channel(String name) { prefix = name + "."; }
        public Channel signal(String n, Recorder.Source s) { rec.signal(prefix + n, s); return this; }
        public Channel signal(String n, Recorder.Source s, int periodMs) { rec.signal(prefix + n, s, periodMs); return this; }
    }

    public void event(String type) { rec.event(type, ""); }
    public void event(String type, String detail) { rec.event(type, detail); }

    // ---------------------------------------------------------------- rules

    public Rule rule(String name) {
        Rule r = new Rule(name);
        rules.add(r);
        return r;
    }

    public List<Rule> rules() { return rules; }

    /**
     * Standard motor checks for signals "<m>.power", "<m>.vel" and optionally "<m>.amps":
     *  - stall: high current, almost no motion, real command
     *  - encoder lost: encoder that used to move reads 0 while the motor is driven normally
     */
    public BlackBox motorRules(final String m, final double stallAmps, final double minVel) {
        final boolean hasAmps = rec.has(m + ".amps");
        if (hasAmps) {
            rule(m + "-stall").forTime(0.3).show(m + ".amps", m + ".vel", m + ".power")
                    .when(new Rule.Condition() { public boolean test(Recorder r) {
                        return r.get(m + ".amps") > stallAmps && Math.abs(r.get(m + ".vel")) < minVel && Math.abs(r.get(m + ".power")) > 0.5;
                    }});
        }
        final boolean[] moved = {false};
        rule(m + "-encoder-lost").forTime(0.5).severity(Rule.Severity.CRITICAL).show(m + ".power", m + ".vel")
                .when(new Rule.Condition() { public boolean test(Recorder r) {
                    double v = r.get(m + ".vel");
                    if (Math.abs(v) > minVel) moved[0] = true;          // motors without encoder never arm this rule
                    boolean normalCurrent = !hasAmps || r.get(m + ".amps") < stallAmps;
                    return moved[0] && v == 0 && Math.abs(r.get(m + ".power")) > 0.5 && normalCurrent;
                }});
        return this;
    }

    /** Battery below volts (REV hubs reset around 7 V). */
    public BlackBox brownoutRule(final String batterySignal, final double volts) {
        rule("brownout").forTime(0.05).severity(Rule.Severity.CRITICAL).show(batterySignal)
                .when(new Rule.Condition() { public boolean test(Recorder r) { return r.get(batterySignal) < volts; } });
        return this;
    }

    /** Loop slower than ms for one second. */
    public BlackBox slowLoopRule(final double ms) {
        rule("slow-loop").forTime(1.0).show("loop_ms")
                .when(new Rule.Condition() { public boolean test(Recorder r) { return r.get("loop_ms") > ms; } });
        return this;
    }

    /** |target - actual| > tolerance for longer than seconds: mechanism does not reach its setpoint. */
    public BlackBox notReachingRule(String name, final String target, final String actual, final double tolerance, double seconds) {
        rule(name).forTime(seconds).show(target, actual)
                .when(new Rule.Condition() { public boolean test(Recorder r) { return Math.abs(r.get(target) - r.get(actual)) > tolerance; } });
        return this;
    }

    /**
     * Signals compared with previous sessions at stop(), ignoring changes smaller than minChange
     * (in the signal's units). Motor currents, battery and loop time are compared by default;
     * add health metrics of your own, e.g. bb.baseline("lift.cycle_s", 0.05).
     * Positions and speeds are not compared by default: they depend on how the driver drove.
     */
    public BlackBox baseline(String signal, double minChange) { baseline.put(signal, minChange); return this; }

    private double baselineFloor(String name) {
        Double f = baseline.get(name);
        if (f != null) return f;
        if (name.endsWith(".amps")) return 0.5;
        if (name.equals("loop_ms")) return 5;
        if (name.equals("battery")) return 0.3;
        return Double.NaN;   // not a health signal
    }

    // ---------------------------------------------------------------- loop

    public BlackBox start() {
        if (rec.isStarted()) return this;
        rec.signal("loop_ms", new Recorder.Source() { public double get() { return loopMs; } });
        rec.start(dir, tag);
        rec.event("SESSION_START", tag);
        lastLoopNs = System.nanoTime();
        return this;
    }

    /** Call once per loop. Costs one pass over the signals; file IO is on another thread. */
    public void update() {
        long now = System.nanoTime();
        loopMs = (now - lastLoopNs) / 1e6;
        lastLoopNs = now;
        rec.update();
        double t = rec.time();
        for (Rule r : rules) {
            String w = r.check(rec, t);
            if (w == null) continue;
            rec.event(r.severity().name(), w);
            synchronized (warnings) {
                warnings.addFirst(Recorder.fmt(t) + "s " + w);
                if (warnings.size() > 20) warnings.removeLast();
            }
        }
    }

    /** Live view for the web UI: empty until start(). */
    public List<String> names() { return rec.isStarted() ? rec.names() : new ArrayList<String>(); }
    public double[] values() { return rec.isStarted() ? rec.snapshot() : new double[0]; }

    /** Newest first. */
    public List<String> warnings() { synchronized (warnings) { return new ArrayList<String>(warnings); } }

    public double loopMs() { return loopMs; }

    /** Stop recording, write "<session>.summary.csv" and "<session>.report.txt" (baseline comparison). */
    public List<String> stop() {
        if (!rec.isStarted()) return report;
        rec.event("SESSION_END", "");
        rec.stop();
        try { writeSummaryAndReport(); } catch (Exception ignored) {}
        return report;
    }

    /** Lines of the last report (rule counts + changes vs. previous sessions). */
    public List<String> report() { return report; }

    // ---------------------------------------------------------------- baseline

    private void writeSummaryAndReport() throws Exception {
        List<String> names = rec.names();
        File out = new File(rec.dir(), rec.session() + ".summary.csv");
        FileWriter w = new FileWriter(out);
        w.write("signal,mean_abs,peak_abs\n");
        for (int i = 0; i < names.size(); i++)
            w.write(names.get(i) + "," + Recorder.fmt(rec.meanAbs(i)) + "," + Recorder.fmt(rec.peakAbs(i)) + "\n");
        w.close();

        report.clear();
        report.add("session " + rec.session() + ", " + rec.samples() + " samples" + (rec.dropped() > 0 ? ", dropped " + rec.dropped() : ""));
        for (Rule r : rules) if (r.count() > 0) report.add(r.severity() + " " + r.name() + " x" + r.count());

        // compare with previous sessions of the same tag: median of up to 10 older summaries
        // session = yyyyMMdd_HHmmss_<tag>: match the tag exactly, so "teleop" does not pick up "auto_teleop"
        final String own = Recorder.safe(tag) + ".summary.csv";
        String[] files = rec.dir().list(new FilenameFilter() { public boolean accept(File d, String n) { return n.length() > 16 && n.substring(16).equals(own); } });
        if (files == null) files = new String[0];
        Arrays.sort(files);
        List<Map<String, double[]>> old = new ArrayList<Map<String, double[]>>();
        for (int i = files.length - 1; i >= 0 && old.size() < 10; i--)
            if (!files[i].startsWith(rec.session())) old.add(readSummary(new File(rec.dir(), files[i])));
        if (old.isEmpty()) { report.add("baseline: first session with tag '" + tag + "'"); save(); return; }

        report.add("baseline: " + old.size() + " previous sessions");
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            double floor = baselineFloor(n);
            if (Double.isNaN(floor)) continue;
            compare(n, "mean", rec.meanAbs(i), 0, old, floor);
            if (!n.equals("battery")) compare(n, "peak", rec.peakAbs(i), 1, old, floor);   // battery peak = full charge, says nothing
        }
        save();
    }

    private void compare(String name, String what, double now, int col, List<Map<String, double[]>> old, double floor) {
        List<Double> vals = new ArrayList<Double>();
        for (Map<String, double[]> m : old) { double[] v = m.get(name); if (v != null && !Double.isNaN(v[col])) vals.add(v[col]); }
        if (vals.size() < 1 || Double.isNaN(now)) return;
        Collections.sort(vals);
        double med = vals.get(vals.size() / 2);
        if (Math.abs(med) < 1e-3 || Math.abs(now - med) < floor) return;
        double change = (now - med) / Math.abs(med);
        // ponytail: fixed 30% threshold; make per-signal if some signals are naturally noisy
        if (Math.abs(change) >= 0.30)
            report.add(name + " " + what + " " + Recorder.fmt(now) + " vs " + Recorder.fmt(med) + " (" + (change > 0 ? "+" : "") + Math.round(change * 100) + "%)");
    }

    private static Map<String, double[]> readSummary(File f) {
        Map<String, double[]> m = new HashMap<String, double[]>();
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            r.readLine();
            for (String line; (line = r.readLine()) != null; ) {
                String[] p = line.split(",", -1);
                if (p.length >= 3) m.put(p[0], new double[]{num(p[1]), num(p[2])});
            }
            r.close();
        } catch (Exception ignored) {}
        return m;
    }

    private static double num(String s) { try { return Double.parseDouble(s); } catch (Exception e) { return Double.NaN; } }

    private void save() throws Exception {
        FileWriter w = new FileWriter(new File(rec.dir(), rec.session() + ".report.txt"));
        for (String line : report) w.write(line + "\n");
        w.close();
    }
}
