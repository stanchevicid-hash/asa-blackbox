import org.asa.tools.blackbox.BatteryHealth;
import org.asa.tools.blackbox.BlackBox;
import org.asa.tools.blackbox.CenterOfGravity;
import org.asa.tools.blackbox.Rule;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;
import java.util.Random;

/**
 * Battery health, hub reset detection, loop profiler, crash capture and center of gravity, each against an answer
 * known in advance.
 *
 *   javac -d out src/org/asa/tools/core/*.java src/org/asa/tools/blackbox/*.java test/BlackBoxFeaturesTest.java
 *   java -cp out BlackBoxFeaturesTest
 */
public class BlackBoxFeaturesTest {

    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        battery();
        hubReset();
        profilerAndCrash();
        centerOfGravity();
        sameSecond();
        System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** V = open-circuit voltage (slowly falling) - R * I, with current steps from driving and sensor noise. */
    static void battery() {
        System.out.println("BATTERY HEALTH");
        for (double trueR : new double[]{0.08, 0.12, 0.30}) {
            Random rnd = new Random(3);
            BatteryHealth b = new BatteryHealth();
            double amps = 2;
            for (int i = 0; i < 1500; i++) {                         // 150 ms reads for ~3.75 min
                double t = i * 0.15;
                if (rnd.nextDouble() < 0.3) amps = 1 + rnd.nextDouble() * 18;   // driving: current jumps around
                double ocv = 13.1 - 0.004 * t;
                double v = ocv - trueR * amps + rnd.nextGaussian() * 0.03;     // hub voltage noise ~30 mV
                b.feed(t, v, amps + rnd.nextGaussian() * 0.1);
            }
            double r = b.resistance();
            check(Math.abs(r - trueR) / trueR < 0.10, String.format("true %.2f ohm -> estimated %.3f ohm (%s), %d slopes, %.2f Ah",
                    trueR, r, BatteryHealth.verdict(r), b.samples(), b.ampHours()));
        }
        BatteryHealth idle = new BatteryHealth();
        for (int i = 0; i < 100; i++) idle.feed(i * 0.15, 12.6, 1.0);
        check(Double.isNaN(idle.resistance()), "no current steps (robot idle) -> no estimate, not a wrong one");
    }

    static double p0, p1, p2;

    static void hubReset() throws Exception {
        System.out.println("HUB RESET");
        File dir = new File(System.getProperty("java.io.tmpdir"), "asa-feat-" + System.nanoTime());
        BlackBox bb = BlackBox.create("hub").dir(dir);
        bb.signal("a.pos", () -> p0).signal("b.pos", () -> p1).signal("c.pos", () -> p2);
        bb.hubResetRule("hub1", "a.pos", "b.pos");        // a and b on one hub
        bb.hubResetRule("hub2", "c.pos");                  // c alone on another
        bb.start();
        double t = 0;
        p0 = 1500; p1 = -800; p2 = 2000;
        for (int i = 0; i < 50; i++) { p0 += 7; p1 -= 3; p2 += 5; bb.update(t += 0.02); }
        p0 = 0; p1 = 0;                                     // hub 1 reboots: both its encoders read 0
        bb.update(t += 0.02);
        for (int i = 0; i < 50; i++) { p0 += 7; p1 -= 3; p2 += 5; bb.update(t += 0.02); }
        bb.encodersReset();                                 // code homes the lift on hub 2 on purpose
        p2 = 0;
        bb.update(t += 0.02);
        for (int i = 0; i < 20; i++) bb.update(t += 0.02);
        p0 = 0;                                             // only one of hub 1's two encoders goes to 0: not a hub reset
        for (int i = 0; i < 5; i++) bb.update(t += 0.02);
        bb.stop();
        check(count(bb, "hub1-reset") == 1, "hub 1 reboot reported once (" + count(bb, "hub1-reset") + ")");
        check(count(bb, "hub2-reset") == 0, "encoders reset by code are not reported");
    }

    static void profilerAndCrash() throws Exception {
        System.out.println("LOOP PROFILER + CRASH");
        File dir = new File(System.getProperty("java.io.tmpdir"), "asa-feat-" + System.nanoTime());
        final double[] x = {0};
        BlackBox bb = BlackBox.create("prof").dir(dir);
        bb.signal("x", () -> x[0]);
        bb.profile("vision");
        bb.start();
        for (int i = 0; i < 25; i++) {
            bb.section("vision"); Thread.sleep(12);
            bb.section("drive"); Thread.sleep(3);
            x[0] = i;
            bb.update();
        }
        try { throw new IllegalStateException("lift motor not found"); }
        catch (IllegalStateException e) { bb.crash(e); }
        List<String> report = bb.stop();
        String loop = "";
        for (String l : report) if (l.startsWith("loop ")) loop = l;
        System.out.println("  " + loop);
        check(loop.contains("vision 1") && loop.indexOf("vision") < loop.indexOf("drive"), "vision (~12 ms) listed first, then drive");
        File crash = new File(dir, bb.rec.session() + ".crash.txt");
        String text = read(crash);
        check(text.contains("lift motor not found") && text.contains("BlackBoxFeaturesTest") && text.contains("x = 24"),
                "crash file has the error, the stack trace and the last values");
        check(read(new File(dir, bb.rec.session() + ".events.csv")).contains("CRASH"), "CRASH event on the timeline");
        check(read(new File(dir, bb.rec.session() + ".csv")).contains("time.vision"), "time.vision column in the log");
    }

    static void centerOfGravity() {
        System.out.println("CENTER OF GRAVITY");
        double L = 32, W = 36, fromRear = 13, fromRight = 19.5, h = 11;      // cm
        double rear = Math.toDegrees(Math.atan(fromRear / h)), front = Math.toDegrees(Math.atan((L - fromRear) / h));
        double right = Math.toDegrees(Math.atan(fromRight / h)), left = Math.toDegrees(Math.atan((W - fromRight) / h));
        CenterOfGravity c = CenterOfGravity.compute(L, W, rear, front, right, left);
        for (String l : c.lines()) System.out.println("  " + l);
        check(Math.abs(c.height - h) < 1e-6 && Math.abs(c.fromRear - fromRear) < 1e-6 && Math.abs(c.fromRight - fromRight) < 1e-6,
                "recovers height 11, 13 from rear, 19.5 from right exactly");
        check(Math.abs(c.maxBrake() - 9.81 * 19 / 11) < 1e-9, "tipping: braking limit g*(L - fromRear)/h");
        // a 1 degree reading error on every angle, the size of holding the robot by hand
        CenterOfGravity e = CenterOfGravity.compute(L, W, rear + 1, front - 1, right + 1, left - 1);
        check(Math.abs(e.height - h) < 0.6 && Math.abs(e.fromRear - fromRear) < 0.8,
                String.format("1 deg error on each angle -> height %.1f (11), from rear %.1f (13): within 1 cm", e.height, e.fromRear));
        check(CenterOfGravity.compute(L, W, rear + 8, front, right, left).notes.size() == 1, "inconsistent heights are flagged");
        check(Math.abs(CenterOfGravity.tiltBetween(0, 0, 25, 0) - 25) < 1e-9 && Math.abs(CenterOfGravity.tiltBetween(0, 0, 0, -30) - 30) < 1e-9,
                "tilt from pitch alone and roll alone");
        check(Math.abs(CenterOfGravity.tiltBetween(5, -3, 5, -3)) < 1e-9, "same pose -> 0 tilt");
    }

    static int count(BlackBox bb, String name) {
        for (Rule r : bb.rules()) if (r.name().equals(name)) return r.count();
        return -1;
    }

    static String read(File f) throws Exception {
        if (!f.isFile()) return "";
        StringBuilder s = new StringBuilder();
        BufferedReader r = new BufferedReader(new FileReader(f));
        for (String line; (line = r.readLine()) != null; ) s.append(line).append('\n');
        r.close();
        return s.toString();
    }

    /** The automatic recorder can start the same OpMode twice in one second (INIT, STOP, INIT): two files, not one. */
    static void sameSecond() throws Exception {
        System.out.println("SAME TAG, SAME SECOND");
        java.io.File dir = java.nio.file.Files.createTempDirectory("asa-same").toFile();
        org.asa.tools.core.Recorder a = new org.asa.tools.core.Recorder(), b = new org.asa.tools.core.Recorder();
        a.signal("x", new org.asa.tools.core.Recorder.Source() { public double get() { return 1; } });
        b.signal("x", new org.asa.tools.core.Recorder.Source() { public double get() { return 2; } });
        a.start(dir, "TeleOp Main"); a.update(); a.stop();
        b.start(dir, "TeleOp Main"); b.update(); b.stop();
        check(!a.session().equals(b.session()), "second session gets its own name: " + a.session() + " / " + b.session());
        check(new java.io.File(dir, a.session() + ".csv").exists() && new java.io.File(dir, b.session() + ".csv").exists(), "both files kept");
    }
}
