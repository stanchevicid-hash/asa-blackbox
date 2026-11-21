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

    // loop profiler: time spent in each named section of the loop
    private final Map<String, double[]> sectionStats = new java.util.LinkedHashMap<String, double[]>();   // sum ms, max ms
    private final Map<String, Double> sectionNow = new HashMap<String, Double>();                         // this loop
    private final Map<String, Double> sectionLast = new HashMap<String, Double>();                        // last loop
    private String section;
    private long sectionStartNs;
    private long loops;
    private double loopSum;

    // battery health
    private final BatteryHealth battery = new BatteryHealth();
    private String batteryLabel = "";

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
     *  - encoder lost: encoder that used to move reads 0 while the motor is driven and draws little current
     *    (spinning free); a motor holding a load also reads 0 but draws ~power x 9 A, so it does not trip this
     */
    public BlackBox motorRules(final String m, final double stallAmps, final double minVel) {
        final boolean hasAmps = rec.has(m + ".amps");
        final boolean[] moved = {false};                                // has this encoder ever shown motion?
        if (hasAmps) {
            // 0.5 s: a normal spin-up also draws stall-like current for 0.1-0.3 s. A stall is a command held in one
            // direction: a reversal restarts the count, so back-to-back reversal spikes (whose short dip between them
            // can fall between two 100 ms current reads) never add up to a stall.
            // A motor without an encoder always reads 0 speed, so a long spin-up looks like a stall: it needs 1 s.
            final double[] lastSign = {0}, since = {Double.NaN};
            rule(m + "-stall").forTime(0.5).show(m + ".amps", m + ".vel", m + ".power")
                    .when(new Rule.Condition() { public boolean test(Recorder r) {
                        double p = r.get(m + ".power"), sign = Math.signum(p);
                        boolean reversed = sign != 0 && lastSign[0] != 0 && sign != lastSign[0];
                        if (sign != 0) lastSign[0] = sign;
                        boolean c = !reversed && r.get(m + ".amps") > stallAmps && Math.abs(r.get(m + ".vel")) < minVel && Math.abs(p) > 0.5;
                        if (!c) { since[0] = Double.NaN; return false; }
                        if (moved[0]) return true;
                        if (Double.isNaN(since[0])) since[0] = r.time();
                        return r.time() - since[0] >= 0.5;                  // + forTime 0.5 = 1 s without an encoder
                    }});
        }
        rule(m + "-encoder-lost").forTime(0.5).severity(Rule.Severity.CRITICAL).show(m + ".power", m + ".vel")
                .when(new Rule.Condition() { public boolean test(Recorder r) {
                    double v = r.get(m + ".vel");
                    if (Math.abs(v) > minVel) moved[0] = true;          // motors without encoder never arm this rule
                    double p = Math.abs(r.get(m + ".power"));
                    // encoder lost = the motor spins freely (low current) but no ticks arrive. A motor holding a load
                    // still also reads 0 ticks, but draws about power x 9 A (FTC stall current), so it is excluded.
                    boolean spinningFree = !hasAmps || r.get(m + ".amps") < 3.6 * p;
                    return moved[0] && v == 0 && p > 0.5 && spinningFree;
                }});
        return this;
    }

    private double resetByCodeAt = Double.NEGATIVE_INFINITY;

    /** Call when your code resets encoders on purpose (e.g. homing a lift), so it is not reported as a hub reset. */
    public void encodersReset() { resetByCodeAt = rec.isStarted() ? rec.time() : 0; rec.event("ENCODER_RESET", "by code"); }

    /**
     * A hub that reboots (static discharge, loose power, brownout) comes back with every encoder at 0. This fires
     * when all encoders of one hub that were away from 0 read 0 in the same loop, unless encodersReset() was called
     * within the last second.
     */
    public BlackBox hubResetRule(final String hub, final String... posSignals) {
        return zeroedRule(hub + "-reset", Rule.Severity.CRITICAL, posSignals);
    }

    /**
     * The same check when recording without the team's code (automatic recorder): all encoders of a hub back to 0
     * is either the program resetting them or the hub rebooting, so it is a WARNING named "-zeroed".
     */
    public BlackBox hubZeroedRule(final String hub, final String... posSignals) {
        return zeroedRule(hub + "-zeroed", Rule.Severity.WARNING, posSignals);
    }

    private BlackBox zeroedRule(String name, Rule.Severity severity, final String... posSignals) {
        final double[] prev = new double[posSignals.length];
        java.util.Arrays.fill(prev, Double.NaN);
        rule(name).severity(severity).cooldown(2).show(posSignals)
                .when(new Rule.Condition() { public boolean test(Recorder r) {
                    int away = 0, zeroed = 0;
                    for (int i = 0; i < posSignals.length; i++) {
                        double p = r.get(posSignals[i]);
                        if (!Double.isNaN(prev[i]) && Math.abs(prev[i]) > 100) {
                            away++;
                            if (Math.abs(p) <= 3) zeroed++;
                        }
                        prev[i] = p;
                    }
                    return away > 0 && zeroed == away && r.time() - resetByCodeAt > 1.0;
                }});
        return this;
    }

    /** Hub that stopped answering (signal "<hub>.ok" is 0). */
    public BlackBox hubLostRule(final String hub) {
        rule(hub + "-not-responding").severity(Rule.Severity.CRITICAL).cooldown(2).show(hub + ".ok")
                .when(new Rule.Condition() { public boolean test(Recorder r) { return r.get(hub + ".ok") == 0; } });
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

    /**
     * One loop longer than ms (a freeze the driver feels: a blocking sleep, a camera call, garbage collection).
     * The slow-loop rule needs a whole second; this catches a single spike.
     */
    public BlackBox pauseRule(final double ms) {
        rule("loop-pause").cooldown(1).show("loop_ms")
                .when(new Rule.Condition() { public boolean test(Recorder r) { return r.time() > 0.5 && r.get("loop_ms") > ms; } });
        return this;
    }

    /**
     * A sensor whose value has not changed by a single bit for `seconds` while the robot moves (any of
     * motionSignals, motor powers, above 0.3): a hung I2C sensor returns the same cached value forever.
     * A real sensor always has some noise. NaN readings (e.g. out of range) never count as frozen, and a sensor that
     * never changed at all (not initialized, unplugged from the start) is not "frozen": it must have been alive first.
     */
    public BlackBox frozenRule(final String signal, double seconds, final String... motionSignals) {
        final double[] last = {Double.NaN};
        final boolean[] alive = {false};
        rule(signal + "-frozen").forTime(seconds).cooldown(10).show(signal)
                .when(new Rule.Condition() { public boolean test(Recorder r) {
                    double v = r.get(signal);
                    boolean same = !Double.isNaN(v) && v == last[0];
                    if (!Double.isNaN(v) && !Double.isNaN(last[0]) && v != last[0]) alive[0] = true;
                    last[0] = v;
                    if (!alive[0]) return false;
                    boolean moving = false;
                    for (String m : motionSignals) if (Math.abs(r.get(m)) > 0.3) moving = true;
                    return same && moving;
                }});
        return this;
    }

    /**
     * Drivetrain friction: all wheels are commanded about the same power (within 0.15) above 0.3, but one draws more
     * than twice the median current of the others, and over 1.5 A, for 2 s. Usually a bent axle, a rubbing wheel, a
     * dragging chain or a failing gearbox; it slows the robot and drains the battery long before it breaks.
     */
    public BlackBox imbalanceRule(final String... motors) {
        if (motors.length < 2) return this;
        for (final String m : motors) {
            if (!rec.has(m + ".amps")) return this;
            final boolean[] encoderWorks = {false};
            rule(m + "-drags").forTime(2.0).cooldown(30).show(m + ".amps", m + ".power")
                    .when(new Rule.Condition() { public boolean test(Recorder r) {
                        if (Math.abs(r.get(m + ".vel")) > 20) encoderWorks[0] = true;   // every loop, before any filter
                        double pMin = Double.MAX_VALUE, pMax = 0;
                        List<Double> others = new ArrayList<Double>(), speeds = new ArrayList<Double>();
                        for (String o : motors) {
                            double p = Math.abs(r.get(o + ".power"));
                            pMin = Math.min(pMin, p); pMax = Math.max(pMax, p);
                            if (!o.equals(m)) { others.add(r.get(o + ".amps")); speeds.add(Math.abs(r.get(o + ".vel"))); }
                        }
                        if (pMin < 0.3 || pMax - pMin > 0.15) return false;
                        // friction = the wheel turns with the others but needs more current; a wheel that does not
                        // turn is a stall (other rule). Encoders read 0 when not plugged in, so speed is only compared
                        // with the other wheels that do report one; with no encoders at all only the current is used.
                        double vel = Math.abs(r.get(m + ".vel")), vMax = 0;
                        if (encoderWorks[0] && vel == 0) return false;        // its own working encoder: not turning
                        for (double s : speeds) if (s > vMax) vMax = s;
                        if (vMax > 0 && !(vel >= 0.5 * vMax)) return false;
                        Collections.sort(others);
                        double med = others.get(others.size() / 2), a = r.get(m + ".amps");
                        return a > 1.5 && a > 2 * med;
                    }});
        }
        return this;
    }

    /**
     * Motor overheating: the RMS current over the last ~tauSeconds (a first-order thermal model, I^2 filtered)
     * above ampsRms. A motor run like this loses power and can burn out. 5 A RMS over 2 minutes is a lot for an FTC
     * motor; a stall that the stall rule catches is short, this catches a long overload (a lift held up at high power).
     */
    public BlackBox thermalRule(final String m, final double ampsRms, final double tauSeconds) {
        final double[] heat = {0, Double.NaN};                    // filtered I^2, last time
        rule(m + "-hot").cooldown(30).show(m + ".amps")
                .when(new Rule.Condition() { public boolean test(Recorder r) {
                    double t = r.time(), a = r.get(m + ".amps");
                    if (!Double.isNaN(heat[1]) && !Double.isNaN(a)) {
                        double dt = Math.min(1.0, t - heat[1]);
                        heat[0] += (a * a - heat[0]) * dt / tauSeconds;
                    }
                    heat[1] = t;
                    return Math.sqrt(heat[0]) > ampsRms;
                }});
        return this;
    }

    /** Something that was connected (okSignal 1) is now 0: a gamepad, a sensor, a camera. Fires each time it drops. */
    public BlackBox disconnectRule(String name, final String okSignal) {
        final boolean[] was = {false};
        // 0.25 s: when the driver presses STOP, the Driver Station releases the gamepads a few ms before the OpMode
        // ends, which looks like a disconnect in the very last loop (seen on a real robot). A real drop lasts longer.
        rule(name + "-disconnected").severity(Rule.Severity.CRITICAL).forTime(0.25).cooldown(1).show(okSignal)
                .when(new Rule.Condition() { public boolean test(Recorder r) {
                    double ok = r.get(okSignal);
                    if (ok == 1) was[0] = true;
                    return was[0] && ok == 0;
                }});
        return this;
    }

    /**
     * Battery voltage read while the robot is idle (during INIT; under load even a full battery sags, and an autonomous
     * drives from the first second) below threshold: it will sag toward brownout in the match. Reported at the first
     * update(). FtcBlackBox.attachAll does this with 12.4 V.
     */
    public BlackBox lowBatteryAtStartRule(final double idleVolts, final double threshold) {
        final double v = idleVolts;
        if (!rec.has("battery.init")) rec.signal("battery.init", new Recorder.Source() { public double get() { return v; } }, 60000);
        rule("battery-low-at-start").cooldown(1e9).show("battery.init")
                .when(new Rule.Condition() { public boolean test(Recorder r) { return v > 5 && v < threshold; } });
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

    // ---------------------------------------------------------------- loop profiler

    /**
     * Sections to graph over time ("time.vision" etc. in the log). Sections you time but do not list here still
     * appear in the report's loop breakdown. Call before start().
     */
    public BlackBox profile(String... sections) {
        for (final String s : sections) {
            sectionStats.put(s, new double[2]);
            rec.signal("time." + s, new Recorder.Source() { public double get() {
                Double v = sectionLast.get(s);
                return v == null ? 0 : v;
            }});
        }
        return this;
    }

    /**
     * Start timing a part of the loop; the previous section ends here. update() ends the last one.
     *   bb.section("vision"); vision.update();  bb.section("drive"); drive();  bb.update();
     */
    public void section(String name) {
        long now = System.nanoTime();
        closeSection(now);
        section = name;
        sectionStartNs = now;
    }

    private void closeSection(long now) {
        if (section == null) return;
        double ms = (now - sectionStartNs) / 1e6;
        Double prev = sectionNow.get(section);
        sectionNow.put(section, prev == null ? ms : prev + ms);
        section = null;
    }

    private void endLoopSections() {
        closeSection(System.nanoTime());
        sectionLast.clear();
        sectionLast.putAll(sectionNow);
        for (Map.Entry<String, Double> e : sectionNow.entrySet()) {
            double[] s = sectionStats.get(e.getKey());
            if (s == null) sectionStats.put(e.getKey(), s = new double[2]);
            s[0] += e.getValue();
            s[1] = Math.max(s[1], e.getValue());
        }
        sectionNow.clear();
    }

    // ---------------------------------------------------------------- battery health

    /** Which battery is in the robot (e.g. "A"): its health is tracked across sessions in batteries.csv. */
    public BlackBox battery(String label) { batteryLabel = label == null ? "" : label.trim(); return this; }

    /** Feed with a voltage and total current read at the same instant (FtcBlackBox does this for you). */
    public BatteryHealth batteryHealth() { return battery; }

    // ---------------------------------------------------------------- crash

    /**
     * Record an exception that is about to end the OpMode, with its stack trace and the last values of every
     * signal, and write everything to disk now:
     *   try { ...loop... } catch (RuntimeException e) { bb.crash(e); throw e; } finally { bb.stop(); }
     */
    public void crash(Throwable t) {
        if (!rec.isStarted()) return;
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        String first = t.toString();
        StackTraceElement[] st = t.getStackTrace();
        if (st.length > 0) first += " at " + st[0];
        rec.event("CRASH", first);
        synchronized (warnings) { warnings.addFirst(Recorder.fmt(rec.time()) + "s CRITICAL CRASH " + first); }
        try {
            FileWriter w = new FileWriter(new File(rec.dir(), rec.session() + ".crash.txt"));
            w.write("time " + Recorder.fmt(rec.time()) + " s\n\n" + sw + "\nlast values:\n");
            List<String> names = rec.names();
            double[] v = rec.snapshot();
            for (int i = 0; i < Math.min(names.size(), v.length); i++) w.write(names.get(i) + " = " + Recorder.fmt(v[i]) + "\n");
            w.close();
        } catch (Exception ignored) {}
        rec.flush();
    }

    // ---------------------------------------------------------------- loop

    /** The newest Black Box started and when: the automatic recorder steps aside for a team's own one. */
    public static volatile BlackBox lastStarted;
    public static volatile long lastStartedNs = Long.MIN_VALUE;

    public BlackBox start() {
        if (rec.isStarted()) return this;
        lastStarted = this;
        lastStartedNs = System.nanoTime();
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
        endLoopSections();
        countLoop();
        rec.update();
        checkRules(rec.time());
    }

    /** Same, on a given clock (seconds since start): for simulations and replaying a log through new rules. */
    public void update(double timeSeconds) {
        loopMs = Double.isNaN(lastSimT) ? 0 : (timeSeconds - lastSimT) * 1000;
        lastSimT = timeSeconds;
        endLoopSections();
        countLoop();
        rec.update(timeSeconds);
        checkRules(timeSeconds);
    }

    private void countLoop() { if (rec.isStarted() && loopMs > 0) { loops++; loopSum += loopMs; } }

    private double lastSimT = Double.NaN;

    private void checkRules(double t) {
        for (Rule r : rules) {
            String w = r.check(rec, t);
            if (w == null) continue;
            rec.event(r.severity().name(), w.substring(w.indexOf(' ') + 1));   // type already says the severity
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
        reportLoop();
        reportBattery();

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

    /** "loop 24.1 ms avg: vision 14.2 ms (59%, max 31.0), drive 3.1 ms (13%), other 6.8 ms (28%)" */
    private void reportLoop() {
        if (loops == 0 || sectionStats.isEmpty()) return;
        double avg = loopSum / loops, timed = 0;
        StringBuilder s = new StringBuilder("loop " + Recorder.fmt(Math.round(avg * 10) / 10.0) + " ms avg:");
        List<Map.Entry<String, double[]>> list = new ArrayList<Map.Entry<String, double[]>>(sectionStats.entrySet());
        Collections.sort(list, new java.util.Comparator<Map.Entry<String, double[]>>() {
            public int compare(Map.Entry<String, double[]> a, Map.Entry<String, double[]> b) { return Double.compare(b.getValue()[0], a.getValue()[0]); }
        });
        for (Map.Entry<String, double[]> e : list) {
            double mean = e.getValue()[0] / loops;
            timed += mean;
            s.append(' ').append(e.getKey()).append(' ').append(Recorder.fmt(Math.round(mean * 10) / 10.0)).append(" ms (")
             .append(Math.round(100 * mean / avg)).append("%, max ").append(Recorder.fmt(Math.round(e.getValue()[1] * 10) / 10.0)).append("),");
        }
        double other = Math.max(0, avg - timed);
        s.append(" other ").append(Recorder.fmt(Math.round(other * 10) / 10.0)).append(" ms (").append(Math.round(100 * other / avg)).append("%)");
        report.add(s.toString());
    }

    /** Battery health of this session, compared with the same battery's history in batteries.csv. */
    private void reportBattery() throws Exception {
        double r = battery.resistance();
        if (Double.isNaN(r) && battery.samples() == 0) return;
        String label = batteryLabel.isEmpty() ? "?" : batteryLabel;
        StringBuilder s = new StringBuilder("battery " + label + ": " + (Double.isNaN(r) ? "-" : Recorder.fmt(Math.round(r * 1000) / 1000.0) + " ohm")
                + " " + BatteryHealth.verdict(r) + ", start " + Recorder.fmt(Math.round(battery.startVolts() * 100) / 100.0)
                + " V, min " + Recorder.fmt(Math.round(battery.minVolts() * 100) / 100.0) + " V, used "
                + Recorder.fmt(Math.round(battery.ampHours() * 1000) / 1000.0) + " Ah");

        File hist = new File(rec.dir(), "batteries.csv");
        List<Double> past = new ArrayList<Double>();
        if (hist.isFile() && !batteryLabel.isEmpty()) {
            BufferedReader in = new BufferedReader(new FileReader(hist));
            in.readLine();
            for (String line; (line = in.readLine()) != null; ) {
                String[] p = line.split(",", -1);
                if (p.length >= 3 && p[1].equals(batteryLabel)) { double v = num(p[2]); if (!Double.isNaN(v)) past.add(v); }
            }
            in.close();
        }
        if (!past.isEmpty() && !Double.isNaN(r)) {
            Collections.sort(past);
            double med = past.get(past.size() / 2), change = (r - med) / med;
            s.append("; usually ").append(Recorder.fmt(Math.round(med * 1000) / 1000.0)).append(" ohm (")
             .append(change > 0 ? "+" : "").append(Math.round(change * 100)).append("%)");
            if (change > 0.3) s.append(" WORSE THAN USUAL");
        }
        report.add(s.toString());
        if (batteryLabel.isEmpty()) report.add("tip: bb.battery(\"A\") to track each battery's health over time");
        else if (!Double.isNaN(r)) {
            boolean header = !hist.isFile();
            FileWriter w = new FileWriter(hist, true);
            if (header) w.write("session,battery,ohm,start_v,min_v,ah\n");
            w.write(rec.session() + "," + Recorder.safe(batteryLabel) + "," + Recorder.fmt(r) + "," + Recorder.fmt(battery.startVolts())
                    + "," + Recorder.fmt(battery.minVolts()) + "," + Recorder.fmt(battery.ampHours()) + "\n");
            w.close();
        }
    }

    private void compare(String name, String what, double now, int col, List<Map<String, double[]>> old, double floor) {
        List<Double> vals = new ArrayList<Double>();
        for (Map<String, double[]> m : old) { double[] v = m.get(name); if (v != null && !Double.isNaN(v[col])) vals.add(v[col]); }
        if (vals.size() < 1 || Double.isNaN(now)) return;
        Collections.sort(vals);
        double med = vals.get(vals.size() / 2);
        // a median below the floor means the signal was unused before (motor never run): nothing to compare with
        if (Math.abs(med) < Math.max(1e-3, floor) || Math.abs(now - med) < floor) return;
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
