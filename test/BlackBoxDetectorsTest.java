import org.asa.tools.blackbox.BlackBox;
import org.asa.tools.blackbox.Rule;

import java.io.File;
import java.util.Random;

/**
 * The v1.4 detectors, each with the fault it must catch and the healthy look-alike it must ignore:
 * loop pause, frozen sensor, drivetrain wheel dragging, motor overheating, disconnect, low battery at start.
 *
 *   javac -d out src/org/asa/tools/core/*.java src/org/asa/tools/blackbox/*.java test/BlackBoxDetectorsTest.java
 *   java -cp out BlackBoxDetectorsTest
 */
public class BlackBoxDetectorsTest {

    static int failures = 0;
    static final double[] v = new double[16];        // signal values, set by each scenario
    static final Random rnd = new Random(7);

    static void check(boolean ok, String what) {
        System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        pause();
        frozen();
        imbalance();
        thermal();
        disconnect();
        lowBattery();
        System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static BlackBox box(String... names) {
        BlackBox bb = BlackBox.create("det").dir(new File(System.getProperty("java.io.tmpdir"), "asa-det-" + System.nanoTime()));
        for (int i = 0; i < names.length; i++) { final int k = i; bb.signal(names[i], () -> v[k]); }
        return bb;
    }

    static int count(BlackBox bb, String rule) {
        for (Rule r : bb.rules()) if (r.name().equals(rule)) return r.count();
        return -1;
    }

    static void pause() {
        System.out.println("LOOP PAUSE");
        BlackBox bb = box().pauseRule(150);
        bb.start();
        double t = 0;
        for (int i = 0; i < 500; i++) bb.update(t += 0.015 + rnd.nextDouble() * 0.05);   // jittery 15-65 ms loop
        check(count(bb, "loop-pause") == 0, "jittery 15-65 ms loop: no warning");
        bb.update(t += 0.25);                                                              // one 250 ms freeze
        for (int i = 0; i < 100; i++) bb.update(t += 0.02);
        check(count(bb, "loop-pause") == 1, "one 250 ms loop: reported once");
        bb.stop();
    }

    static void frozen() {
        System.out.println("FROZEN SENSOR (IMU heading)");
        BlackBox bb = box("imu.yaw", "fl.power", "never.yaw").frozenRule("imu.yaw", 2.0, "fl.power").frozenRule("never.yaw", 2.0, "fl.power");
        bb.start();
        double t = 0, yaw = 10;
        v[1] = 0.6;
        for (int i = 0; i < 500; i++) { v[0] = yaw += rnd.nextGaussian() * 0.01; bb.update(t += 0.02); }   // driving straight: tiny noise
        check(count(bb, "imu.yaw-frozen") == 0, "driving straight (heading barely changes, but has noise): no warning");
        v[1] = 0;
        for (int i = 0; i < 300; i++) bb.update(t += 0.02);                                // robot still, same value
        check(count(bb, "imu.yaw-frozen") == 0, "robot standing still with an identical value: no warning");
        v[1] = 0.6;
        for (int i = 0; i < 150; i++) bb.update(t += 0.02);                                // 3 s identical while driving
        check(count(bb, "imu.yaw-frozen") == 1, "identical value for 3 s while driving: reported once");
        check(count(bb, "never.yaw-frozen") == 0, "a sensor that never changed at all (not initialized): not reported as frozen");
        bb.stop();
    }

    static void imbalance() {
        System.out.println("DRIVETRAIN WHEEL DRAGGING");
        String[] m = {"fl", "fr", "bl", "br"};
        BlackBox bb = box("fl.power", "fl.amps", "fr.power", "fr.amps", "bl.power", "bl.amps", "br.power", "br.amps").imbalanceRule(m);
        bb.start();
        double t = 0;
        for (int s = 0; s < 60; s++) {                     // 60 drive segments: straight, spin-up, turning, idle
            double p = rnd.nextDouble() < 0.2 ? 0 : 0.3 + rnd.nextDouble() * 0.7;
            boolean turning = rnd.nextBoolean();
            double tSeg = t;
            for (int i = 0; i < 100; i++) {
                double age = t - tSeg;
                for (int k = 0; k < 4; k++) {
                    double pk = turning ? p * (k % 2 == 0 ? 1 : -0.4) : p;
                    v[2 * k] = pk;
                    double spin = age < 0.3 ? 4 * Math.abs(pk) : 0;              // spin-up spike on every wheel
                    double extra = turning && k < 2 ? 2.5 * Math.abs(pk) : 0;    // turning loads the front wheels more
                    v[2 * k + 1] = Math.abs(pk) * 2.5 + spin + extra + rnd.nextGaussian() * 0.25;
                }
                bb.update(t += 0.02);
            }
        }
        int healthy = 0;
        for (String n : m) healthy += count(bb, n + "-drags");
        check(healthy == 0, "120 s of healthy driving (spin-ups, turning, idle): no warning (" + healthy + ")");
        for (int i = 0; i < 200; i++) {                    // straight at 0.6, back-left rubs: 3x the current
            for (int k = 0; k < 4; k++) { v[2 * k] = 0.6; v[2 * k + 1] = 1.5 + rnd.nextGaussian() * 0.2; }
            v[5] = 4.5 + rnd.nextGaussian() * 0.2;
            bb.update(t += 0.02);
        }
        check(count(bb, "bl-drags") == 1 && count(bb, "fl-drags") + count(bb, "fr-drags") + count(bb, "br-drags") == 0,
                "back-left drawing 3x the others: reported once, for that wheel only");
        bb.stop();
    }

    static void thermal() {
        System.out.println("MOTOR OVERHEATING");
        BlackBox bb = box("lift.amps").thermalRule("lift", 5.0, 120);
        bb.start();
        double t = 0;
        v[0] = 9.2;
        for (int i = 0; i < 50; i++) bb.update(t += 0.02);      // 1 s stall
        v[0] = 3;
        for (int i = 0; i < 15000; i++) bb.update(t += 0.02);   // 5 min at 3 A
        check(count(bb, "lift-hot") == 0, "1 s stall then 5 min at 3 A: no warning");
        v[0] = 7;
        double hot = -1;
        for (int i = 0; i < 9000 && hot < 0; i++) { bb.update(t += 0.02); if (count(bb, "lift-hot") > 0) hot = i * 0.02; }
        check(hot > 30 && hot < 180, "held at 7 A: reported after " + Math.round(hot) + " s (needs 30-180 s)");
        bb.stop();
    }

    static void disconnect() {
        System.out.println("DISCONNECT");
        BlackBox bb = box("gamepad1.ok", "gamepad2.ok").disconnectRule("gamepad1", "gamepad1.ok").disconnectRule("gamepad2", "gamepad2.ok");
        bb.start();
        double t = 0;
        v[0] = 1; v[1] = 0;                                      // gamepad 2 never connected (one-driver team)
        for (int i = 0; i < 100; i++) bb.update(t += 0.02);
        v[0] = 0;
        for (int i = 0; i < 30; i++) bb.update(t += 0.02);   // 0.6 s without the gamepad
        v[0] = 1;
        for (int i = 0; i < 100; i++) bb.update(t += 0.02);
        check(count(bb, "gamepad1-disconnected") == 1, "gamepad 1 dropped for 0.6 s: reported once");
        check(count(bb, "gamepad2-disconnected") == 0, "gamepad 2 never connected: not reported");
        v[0] = 0;                                             // STOP pressed: released in the last loops only
        for (int i = 0; i < 3; i++) bb.update(t += 0.004);
        check(count(bb, "gamepad1-disconnected") == 1, "gamepads released by STOP in the last loops: not reported");
        bb.stop();
    }

    static void lowBattery() {
        System.out.println("LOW BATTERY AT START");
        // idle voltage read in INIT; then an autonomous drives from t=0 and sags even a full battery to ~11 V
        for (double[] c : new double[][]{{12.1, 1}, {13.2, 0}, {Double.NaN, 0}}) {
            BlackBox bb = box("battery").lowBatteryAtStartRule(c[0], 12.4);
            bb.start();
            double t = 0;
            for (int i = 0; i < 3000; i++) { v[0] = 11.0 + rnd.nextGaussian() * 0.2; bb.update(t += 0.02); }
            check(count(bb, "battery-low-at-start") == (int) c[1], c[0] + " V at INIT: " + (c[1] == 1 ? "reported once" : "no warning, though driving sags it to 11 V"));
            bb.stop();
        }
    }
}
