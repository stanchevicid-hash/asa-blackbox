import org.asa.tools.blackbox.BlackBox;
import org.asa.tools.blackbox.Rule;

import java.io.File;
import java.util.List;

/**
 * Desktop self-check for Black Box, no robot needed:
 *   java -jar ecj.jar -1.8 -nowarn -cp <ftc-sdk-classes> -d out src test/BlackBoxSelfTest.java
 *   java -cp out BlackBoxSelfTest
 * Rules must fire once, on time, and stay quiet when they should; files and baseline must be written.
 */
public class BlackBoxSelfTest {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        blackBox();
        System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(boolean ok, String what) {
        if (!ok) { failures++; System.out.println("  FAIL " + what); }
    }

    static double amps, vel, power, ampsB = 1, velB = 0;

    static void blackBox() throws Exception {
        System.out.println("BLACK BOX");
        File dir = new File(System.getProperty("java.io.tmpdir"), "asa-selftest-" + System.nanoTime());
        BlackBox bb = BlackBox.create("test").dir(dir);
        bb.channel("lift").signal("power", () -> power).signal("vel", () -> vel).signal("amps", () -> amps);
        bb.channel("intake").signal("power", () -> power).signal("vel", () -> velB).signal("amps", () -> ampsB);
        bb.signal("broken", () -> { throw new RuntimeException("sensor unplugged"); });
        bb.motorRules("lift", 4.5, 20).motorRules("intake", 4.5, 20).slowLoopRule(500);
        bb.start();
        bb.event("AUTO_STARTED");

        // normal motion, then stall, then encoder loss
        phase(bb, 0.6, 0.8, 1000, 2.0);   // moving: no warning
        phase(bb, 0.6, 0.8, 0, 6.0);      // stall: high current, no motion
        phase(bb, 0.6, 0.8, 1000, 2.0);   // free again
        phase(bb, 0.8, 0.8, 0, 1.5);      // encoder lost: driven, normal current, velocity exactly 0
        bb.stop();

        int stall = count(bb, "lift-stall"), lost = count(bb, "lift-encoder-lost"), intakeLost = count(bb, "intake-encoder-lost");
        check(stall == 1, "lift-stall fired once, got " + stall);
        check(lost == 1, "lift-encoder-lost fired once, got " + lost);
        check(intakeLost == 0, "intake without encoder must not report encoder-lost, got " + intakeLost);
        check(new File(dir, bb.rec.session() + ".csv").length() > 100, "csv written");
        check(new File(dir, bb.rec.session() + ".events.csv").length() > 20, "events written");
        check(Double.isNaN(bb.rec.get("broken")), "throwing signal reads NaN");
        System.out.println("  warnings " + bb.warnings());

        // baseline: second session with doubled current must be reported
        Thread.sleep(1100);   // session names have 1 s resolution
        BlackBox bb2 = BlackBox.create("test").dir(dir);
        bb2.channel("lift").signal("power", () -> power).signal("vel", () -> vel).signal("amps", () -> amps);
        bb2.start();
        phase(bb2, 0.6, 0.8, 1000, 2.0);
        phase(bb2, 0.6, 0.8, 0, 12.0);    // twice the stall current of session 1
        List<String> r2 = bb2.stop();
        boolean baseline = false;
        for (String l : r2) baseline |= l.startsWith("lift.amps peak") && l.contains("+");
        check(baseline, "baseline reports higher peak current: " + r2);
        System.out.println("  report " + r2);
        System.out.println(failures == 0 ? "  ok" : "");
    }

    static void phase(BlackBox bb, double seconds, double powerNow, double velNow, double ampsNow) throws Exception {
        power = powerNow; vel = velNow; amps = ampsNow;
        long end = System.nanoTime() + (long) (seconds * 1e9);
        while (System.nanoTime() < end) { bb.update(); Thread.sleep(10); }
    }

    static int count(BlackBox bb, String name) {
        for (Rule r : bb.rules()) if (r.name().equals(name)) return r.count();
        return -1;
    }
}
