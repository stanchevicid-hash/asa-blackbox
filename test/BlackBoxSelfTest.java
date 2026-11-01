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
        wpilog();
        slowReads();
        diskError();
        System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static File lastSession;

    /** The session from blackBox() converted to WPILOG and read back with a parser written from the spec. */
    static void wpilog() throws Exception {
        System.out.println("WPILOG export (AdvantageScope)");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        org.asa.tools.core.WpiLog.convert(lastSession, bytes);
        byte[] b = bytes.toByteArray();
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[6]; in.get(magic);
        check(new String(magic, "UTF-8").equals("WPILOG"), "magic");
        check(in.getShort() == 0x0100, "version 1.0");
        int extraLen = in.getInt();
        in.position(in.position() + extraLen);
        java.util.Map<Long, String> names = new java.util.HashMap<Long, String>(), types = new java.util.HashMap<Long, String>();
        double maxAmps = 0; boolean sawWarning = false; long lastTs = -1; boolean ordered = true; int records = 0;
        while (in.hasRemaining()) {
            int h = in.get() & 0xFF;
            long id = le(in, (h & 3) + 1), size = le(in, ((h >> 2) & 3) + 1), ts = le(in, ((h >> 4) & 7) + 1);
            byte[] p = new byte[(int) size]; in.get(p);
            java.nio.ByteBuffer pb = java.nio.ByteBuffer.wrap(p).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (id == 0) {
                if (p[0] != 0) continue;
                pb.get();
                long eid = pb.getInt() & 0xFFFFFFFFL;
                byte[] n = new byte[pb.getInt()]; pb.get(n);
                byte[] t = new byte[pb.getInt()]; pb.get(t);
                names.put(eid, new String(n, "UTF-8")); types.put(eid, new String(t, "UTF-8"));
                continue;
            }
            records++;
            if (id < names.size() && ts < lastTs) ordered = false;   // signal rows are written in time order
            if (id < names.size()) lastTs = ts;
            if ("ASA/lift/amps".equals(names.get(id))) maxAmps = Math.max(maxAmps, pb.getDouble());
            if ("ASA/events".equals(names.get(id)) && new String(p, "UTF-8").contains("WARNING lift-stall")) sawWarning = true;
        }
        System.out.println("  " + b.length + " bytes, " + names.size() + " entries, " + records + " records");
        check(names.containsValue("ASA/lift/amps") && "double".equals(types.get(3L)), "entries named and typed: " + names);
        check(maxAmps == 6.0, "lift.amps values survive, max " + maxAmps);
        check(sawWarning, "stall warning in ASA/events");
        check(ordered, "timestamps in order");
        java.io.FileOutputStream f = new java.io.FileOutputStream(new File(System.getProperty("java.io.tmpdir"), "asa-selftest.wpilog"));
        f.write(b); f.close();
    }

    static long le(java.nio.ByteBuffer in, int n) {
        long v = 0;
        for (int k = 0; k < n; k++) v |= (long) (in.get() & 0xFF) << (8 * k);
        return v;
    }

    /** 9 current-like signals at 100 ms: never more than 2 hardware reads in one loop, each still read ~every 100 ms. */
    static void slowReads() throws Exception {
        System.out.println("SLOW READS are spread over loops");
        final int[] perLoop = {0};
        final long[] last = new long[9];
        final long[] maxGap = {0};
        BlackBox bb = BlackBox.create("slow").dir(new File(System.getProperty("java.io.tmpdir"), "asa-slow-" + System.nanoTime()));
        for (int i = 0; i < 9; i++) {
            final int k = i;
            bb.signal("m" + i + ".amps", () -> {
                perLoop[0]++;
                long now = System.nanoTime();
                if (last[k] != 0) maxGap[0] = Math.max(maxGap[0], now - last[k]);
                last[k] = now;
                return 1.0;
            }, 100);
        }
        bb.start();
        int worst = 0;
        long end = System.nanoTime() + 1_500_000_000L;
        while (System.nanoTime() < end) { perLoop[0] = 0; bb.update(); worst = Math.max(worst, perLoop[0]); Thread.sleep(10); }
        bb.stop();
        System.out.println("  most reads in one loop: " + worst + ", longest gap: " + maxGap[0] / 1_000_000 + " ms");
        check(worst <= 2, "at most 2 slow reads per loop, got " + worst);
        check(maxGap[0] < 200_000_000L, "every slow signal read within 200 ms, worst gap " + maxGap[0] / 1_000_000 + " ms");
    }

    /** Log folder cannot be created: the robot keeps running and the error is reported. */
    static void diskError() throws Exception {
        System.out.println("DISK ERROR is reported, not thrown");
        File blocker = File.createTempFile("asa-not-a-dir", ".tmp");   // a file where the folder should be
        BlackBox bb = BlackBox.create("disk").dir(blocker);
        bb.signal("x", () -> 1.0);
        bb.start();
        for (int i = 0; i < 20; i++) { bb.update(); Thread.sleep(5); }
        Thread.sleep(200);
        String err = bb.rec.writeError();
        bb.stop();
        System.out.println("  writeError = " + err);
        check(err != null, "write error must be visible");
        blocker.delete();
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
        lastSession = new File(dir, bb.rec.session() + ".csv");

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
