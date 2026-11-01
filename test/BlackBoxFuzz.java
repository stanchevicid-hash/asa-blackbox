import org.asa.tools.blackbox.BlackBox;
import org.asa.tools.blackbox.Rule;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Monte Carlo for the Black Box rules: random robots (2-8 motors, some without encoder) drive a full 150 s match
 * at 8-25 ms loops with realistic healthy behaviour (driving, accelerating current spikes, reversals, holding a
 * lift at 0.15-0.45 power with the encoder still, idle, battery sag) and random injected faults:
 * stall, lost encoder, brownout, slow loop. Every injected fault must be reported by the right rule, and nothing
 * else may be reported (false alarms).
 *
 *   java -cp out BlackBoxFuzz [matches] [seed]
 */
public class BlackBoxFuzz {

    static class Fault { String rule; double from, to; boolean seen; Fault(String r, double a, double b) { rule = r; from = a; to = b; } }

    static class Motor {
        String name; boolean encoder;
        double power, vel, amps, maxTicks, pos;
        double segEnd; double segPower; int segKind;           // 0 drive, 1 hold, 2 idle
    }

    public static void main(String[] args) {
        int matches = args.length > 0 ? Integer.parseInt(args[0]) : 100;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 1;
        Random rnd = new Random(seed);
        int injected = 0, caught = 0, falseAlarms = 0;
        File dir = new File(System.getProperty("java.io.tmpdir"), "asa-bbfuzz");
        for (int match = 0; match < matches; match++) {
            int nm = 2 + rnd.nextInt(7);
            final Motor[] ms = new Motor[nm];
            BlackBox bb = BlackBox.create("fuzz").dir(dir);
            final double[] battery = {12.6};
            bb.signal("battery", () -> battery[0], 100);
            for (int i = 0; i < nm; i++) {
                final Motor m = ms[i] = new Motor();
                m.name = "m" + i;
                m.encoder = rnd.nextDouble() < 0.7;
                m.maxTicks = 1000 + rnd.nextDouble() * 2000;
                bb.channel(m.name).signal("power", () -> m.power).signal("pos", () -> m.pos)
                        .signal("vel", () -> m.encoder ? m.vel : 0).signal("amps", () -> m.amps, 100);
                bb.motorRules(m.name, 5.0, 20);                      // FtcBlackBox.attachAll defaults
            }
            bb.brownoutRule("battery", 8.0).slowLoopRule(50);
            bb.start();

            // faults: 0-3 per match, at random times and motors, never overlapping on one motor
            List<Fault> faults = new ArrayList<Fault>();
            int nf = rnd.nextInt(4);
            for (int k = 0; k < nf; k++) {
                int type = rnd.nextInt(4);
                double from = 20 + rnd.nextDouble() * 110;
                Motor m = ms[rnd.nextInt(nm)];
                if (type == 0) faults.add(new Fault(m.name + "-stall", from, from + 0.6 + rnd.nextDouble() * 1.5));
                else if (type == 1) {
                    Motor e = null;
                    for (Motor x : ms) if (x.encoder) e = x;
                    if (e == null) continue;
                    faults.add(new Fault(e.name + "-encoder-lost", from, from + 1 + rnd.nextDouble() * 2));
                } else if (type == 2) faults.add(new Fault("brownout", from, from + 0.15 + rnd.nextDouble() * 0.2));
                else faults.add(new Fault("slow-loop", from, from + 1.5 + rnd.nextDouble()));
            }
            boolean clash = false;                                   // keep one fault per motor at a time
            for (int a = 0; a < faults.size(); a++) for (int b = a + 1; b < faults.size(); b++)
                if (faults.get(a).rule.equals(faults.get(b).rule) || (faults.get(a).from < faults.get(b).to + 3 && faults.get(b).from < faults.get(a).to + 3)) clash = true;
            if (clash) faults.subList(1, faults.size()).clear();
            injected += faults.size();

            double t = 0;
            while (t < 150) {
                boolean slow = false;
                for (Fault f : faults) if (f.rule.equals("slow-loop") && t >= f.from && t < f.to) slow = true;
                double dt = slow ? 0.08 : 0.008 + rnd.nextDouble() * 0.017;
                t += dt;
                double load = 0;
                for (Motor m : ms) {
                    if (t >= m.segEnd) {                             // next healthy behaviour segment
                        double r = rnd.nextDouble();
                        m.segKind = r < 0.6 ? 0 : r < 0.8 ? 1 : 2;
                        m.segPower = m.segKind == 0 ? (rnd.nextDouble() * 2 - 1) : m.segKind == 1 ? 0.15 + rnd.nextDouble() * 0.3 : 0;
                        m.segEnd = t + 0.4 + rnd.nextDouble() * 3;
                    }
                    String fault = null;
                    for (Fault f : faults) if (t >= f.from && t < f.to && f.rule.startsWith(m.name + "-")) fault = f.rule.substring(m.name.length() + 1);
                    m.power = m.segPower;
                    if ("stall".equals(fault)) {
                        m.power = 0.6 + 0.3 * rnd.nextDouble();
                        m.vel = rnd.nextGaussian() * 4;
                        m.amps = 6 + rnd.nextDouble() * 3;
                    } else if ("encoder-lost".equals(fault)) {
                        m.power = 0.6 + 0.4 * rnd.nextDouble();
                        double target = m.power * m.maxTicks;
                        m.vel += (target - m.vel) * Math.min(1, dt / 0.1);
                        m.amps = 0.5 + 1.2 * m.power + rnd.nextGaussian() * 0.1;   // spinning free: low current
                        m.pos += m.vel * dt;
                        m.vel = m.vel;                                               // real speed; the encoder reads 0:
                    } else if (m.segKind == 1) {                     // holding a lift: encoder still, current ~ power x 9 A
                        m.vel = 0;
                        m.amps = m.power * 9 * (0.85 + 0.3 * rnd.nextDouble());
                    } else {                                         // drive / idle: first-order speed, current with accel spike
                        double target = m.power * m.maxTicks;
                        double before = m.vel;
                        m.vel += (target - m.vel) * Math.min(1, dt / 0.12);
                        double accel = Math.abs(m.vel - before) / dt / m.maxTicks;          // fraction of top speed per s
                        m.amps = Math.max(0, 0.3 + 2.5 * Math.abs(m.power) + 3 * Math.min(1.5, accel) + rnd.nextGaussian() * 0.15);
                        m.vel += m.encoder && Math.abs(m.vel) > 1 ? rnd.nextGaussian() * 0.01 * Math.abs(m.vel) : 0;
                        if (Math.abs(m.vel) < 2) m.vel = 0;          // a still motor reads exactly 0 ticks/s
                    }
                    m.pos += m.vel * dt;
                    load += m.amps;
                }
                // encoder-lost: the hub reports 0 although the motor turns
                for (Motor m : ms) for (Fault f : faults) if (f.rule.equals(m.name + "-encoder-lost") && t >= f.from && t < f.to) m.vel = 0;
                battery[0] = 12.6 - 0.004 * t - 0.06 * load + rnd.nextGaussian() * 0.02;
                for (Fault f : faults) if (f.rule.equals("brownout") && t >= f.from && t < f.to) battery[0] = 7.4;
                bb.update(t);
                String tr = System.getProperty("trace");                  // "match:motor:from:to"
                if (tr != null) {
                    String[] q = tr.split(":");
                    if (Integer.parseInt(q[0]) == match && t >= Double.parseDouble(q[2]) && t <= Double.parseDouble(q[3]))
                        for (Motor mm : ms) if (mm.name.equals(q[1]))
                            System.out.printf("  t=%.3f kind=%d seg=%.2f power=%.2f vel=%.0f amps=%.2f (bb amps %.2f)%n", t, mm.segKind, mm.segPower, mm.power, mm.vel, mm.amps, bb.rec.get(mm.name + ".amps"));
                }
            }
            bb.stop();

            // judge: every warning must belong to an injected fault (same rule, during it or within 1 s after)
            for (Rule r : bb.rules()) {
                if (r.count() == 0) continue;
                boolean expected = false;
                for (Fault f : faults) if (f.rule.equals(r.name())) { expected = true; f.seen = true; }
                if (!expected) {
                    falseAlarms += r.count();
                    System.out.printf("FALSE ALARM match %d: %s x%d%n", match, r.name(), r.count());
                    for (String w : bb.warnings()) if (w.contains(r.name())) System.out.println("    " + w);
                    for (Motor mm : ms) if (r.name().startsWith(mm.name + "-")) System.out.println("    encoder=" + mm.encoder + " maxTicks=" + Math.round(mm.maxTicks));
                }
            }
            for (Fault f : faults) {
                if (f.seen) caught++;
                else System.out.printf("MISSED match %d: %s at %.1f-%.1f s%n", match, f.rule, f.from, f.to);
            }
        }
        System.out.printf("%n%d matches: %d faults injected, %d caught, %d missed; %d false alarms%n",
                matches, injected, caught, injected - caught, falseAlarms);
        System.exit(caught == injected && falseAlarms == 0 ? 0 : 1);
    }
}
