// ASA Black Box library file. "Remove ASA" in the ASA Black Box program deletes it; keep your own code in other files.
package org.firstinspires.ftc.teamcode;


/**
 * A diagnostic rule: fires once when its condition stays true for forTime() seconds,
 * then stays quiet until the condition clears (plus a cooldown).
 *
 *   bb.rule("lift-stall")
 *     .when(r -> r.get("lift.amps") > 5 && Math.abs(r.get("lift.vel")) < 30 && Math.abs(r.get("lift.power")) > 0.7)
 *     .forTime(0.25)
 *     .show("lift.amps", "lift.vel", "lift.power");
 */
public class AsaRule {

    public enum Severity { INFO, WARNING, CRITICAL }

    public interface Condition { boolean test(AsaRecorder r) throws Exception; }

    final String name;
    private Condition condition = new Condition() { public boolean test(AsaRecorder r) { return false; } };
    private double holdS = 0;
    private double cooldownS = 2;
    private Severity severity = Severity.WARNING;
    private String[] show = new String[0];

    private double since = -1;
    private double lastFired = Double.NEGATIVE_INFINITY;
    private boolean active;
    private int count;

    AsaRule(String name) { this.name = name; }

    public AsaRule when(Condition c) { condition = c; return this; }
    public AsaRule forTime(double seconds) { holdS = seconds; return this; }
    public AsaRule cooldown(double seconds) { cooldownS = seconds; return this; }
    public AsaRule severity(Severity s) { severity = s; return this; }
    public AsaRule show(String... signals) { show = signals; return this; }

    public String name() { return name; }
    public Severity severity() { return severity; }
    /** How many times this rule fired in the session. */
    public int count() { return count; }

    /** Returns the warning text if the rule fires now, else null. */
    String check(AsaRecorder r, double t) {
        boolean c;
        try { c = condition.test(r); } catch (Throwable e) { c = false; }
        if (!c) { since = -1; active = false; return null; }
        if (since < 0) since = t;
        if (active || t - since < holdS || t - lastFired < cooldownS) return null;
        active = true;
        lastFired = t;
        count++;
        StringBuilder msg = new StringBuilder(severity + " " + name);
        msg.append(" (").append(Math.round((t - since) * 1000)).append(" ms)");
        for (String s : show) msg.append(' ').append(s).append('=').append(AsaRecorder.fmt(r.get(s)));
        return msg.toString();
    }
}
