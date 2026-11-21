package org.asa.tools.ftc;

import android.content.Context;

import com.qualcomm.ftccommon.FtcEventLoop;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManager;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier;
import com.qualcomm.robotcore.hardware.DcMotorEx;

import org.asa.tools.blackbox.BlackBox;
import org.asa.tools.core.Recorder;
import org.firstinspires.ftc.ftccommon.external.OnCreateEventLoop;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records every match with no code from the team: when any OpMode (Java or Blocks) is INITed, it attaches the Black
 * Box to that OpMode's hardware and samples it 20 times a second from its own thread until STOP. Sessions are named
 * after the OpMode, so "TeleOp Main" is compared with earlier "TeleOp Main" runs.
 *
 * Safety first, it runs next to the team's code during matches:
 * - it never changes the team's hubs (caching mode, motor modes) and reads the encoders with one bulk read per hub;
 * - every error stops the recording, never the robot; the thread is a daemon;
 * - it steps aside when the OpMode records by itself (FtcBlackBox.attachAll in the team's code), for ASA's own
 *   programs, and when switched off in the "ASA Black Box" program (Y at INIT, saved in autorecord.txt).
 */
public final class AsaAutoRecorder implements OpModeManagerNotifier.Notifications {

    /** One listener per robot, also across OnBot Java rebuilds (each build loads new classes). */
    private static final String KEY = "org.asa.tools.autorecorder";
    /** One bulk read per hub per sample: 20 samples/s with the Control Hub alone, 10 with an Expansion Hub too. */
    static final long PERIOD_MS_PER_HUB = 50;

    /** The SDK keeps listeners in a weak set: without this strong reference the recorder would be garbage collected. */
    private static AsaAutoRecorder instance;

    private final OpModeManagerImpl manager;
    private volatile Session session;

    private AsaAutoRecorder(OpModeManagerImpl manager) { this.manager = manager; }

    /** Called by the SDK when the robot app starts (and after "Restart Robot"). */
    @OnCreateEventLoop
    public static void onCreateEventLoop(Context context, FtcEventLoop eventLoop) {
        try { install(eventLoop.getOpModeManager()); } catch (Throwable ignored) {}
    }

    /** From the ASA Black Box program: works even when the robot was not restarted since the library was installed. */
    static void installFromOpMode() {
        try { install(OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().getRootActivity())); } catch (Throwable ignored) {}
    }

    static synchronized void install(OpModeManagerImpl m) {
        if (m == null || (instance != null && instance.manager == m)) return;
        Object old = System.getProperties().get(KEY);   // a copy from before an OnBot rebuild, other class loader
        if (old instanceof OpModeManagerNotifier.Notifications) {
            OpModeManagerNotifier.Notifications o = (OpModeManagerNotifier.Notifications) old;
            try { o.onOpModePostStop(null); } catch (Throwable ignored) {}
            try { m.unregisterListener(o); } catch (Throwable ignored) {}
        }
        instance = new AsaAutoRecorder(m);
        m.registerListener(instance);
        System.getProperties().put(KEY, instance);
        if (enabled()) AsaWeb.start();                  // the results page from power-on; switched off (Y): nothing runs
    }

    /** Remove ASA: unhooks the recorder from the robot app at once and stops a recording in progress. */
    static synchronized void uninstall() {
        Object o = System.getProperties().get(KEY);
        if (o instanceof OpModeManagerNotifier.Notifications) {
            OpModeManagerNotifier.Notifications n = (OpModeManagerNotifier.Notifications) o;
            try { n.onOpModePostStop(null); } catch (Throwable ignored) {}
            if (instance != null) try { instance.manager.unregisterListener(n); } catch (Throwable ignored) {}
        }
        if (instance != null) {
            try { instance.onOpModePostStop(null); } catch (Throwable ignored) {}
            try { instance.manager.unregisterListener(instance); } catch (Throwable ignored) {}
        }
        instance = null;
        System.getProperties().remove(KEY);
    }

    /** True when this copy of the library was hooked into the robot app (at power-on, or by the ASA program). */
    static boolean installed() { return instance != null; }

    /** "TeleOp Main, 21.11 14:10" for the newest recording, or null. */
    static String lastRecording() {
        String[] all = Recorder.defaultDir().list();
        String best = null;
        if (all != null) for (String f : all)
            if (f.matches("\\d{8}_\\d{6}_.+\\.csv") && !f.endsWith(".events.csv") && !f.endsWith(".summary.csv") && (best == null || f.compareTo(best) > 0)) best = f;
        if (best == null) return null;
        return best.substring(16, best.length() - 4).replace('_', ' ') + ", " + best.substring(6, 8) + "." + best.substring(4, 6)
                + " " + best.substring(9, 11) + ":" + best.substring(11, 13);
    }

    /**
     * "Recording test" in the ASA Black Box program: is it safe to leave the automatic recording on?
     * A loop like a team's (every motor's position and speed, telemetry) runs 4 s without and 4 s with the recorder,
     * twice, alternating so that warming up or the battery do not bias it. It compares the loop times and checks that
     * the recording really sampled the robot. No motor is powered. The test recording is deleted.
     */
    static void selfTest(LinearOpMode op) {
        org.firstinspires.ftc.robotcore.external.Telemetry t = op.telemetry;
        List<DcMotorEx> motors = op.hardwareMap.getAll(DcMotorEx.class);
        List<Double> off = new ArrayList<Double>(), on = new ArrayList<Double>();
        long samples = 0, period = 0;
        int encOk = 0;
        double onSeconds = 0;
        for (int round = 0; round < 4 && op.opModeIsActive(); round++) {
            boolean rec = round % 2 == 1;
            Session s = null;
            if (rec) {
                s = new Session(op, "ASA record test");
                s.test = true;
                s.begin();
                s.started = true;
                period = s.periodMs;
            }
            long t0 = System.nanoTime(), prev = t0, end = t0 + 4000000000L;
            boolean first = true;
            while (op.opModeIsActive() && System.nanoTime() < end) {
                for (DcMotorEx m : motors) { m.getCurrentPosition(); m.getVelocity(); }   // what a team's loop reads
                t.addLine(Html.title(tr("Recording test", "Проверка записи", "Test înregistrare")));
                t.addLine(Html.dim(tr("Part ", "Часть ", "Partea ") + (round + 1) + " / 4: ")
                        + (rec ? Html.c(Html.PINK, tr("recording ON", "запись ВКЛ", "înregistrare PORNITĂ")) : Html.dim(tr("recording OFF", "запись ВЫКЛ", "înregistrare OPRITĂ"))));
                t.addLine(Html.dim(tr("No motor turns. About 16 seconds.", "Моторы не крутятся. Около 16 секунд.", "Niciun motor nu se rotește. Cam 16 secunde.")));
                t.update();
                long now = System.nanoTime();
                if (!first) (rec ? on : off).add((now - prev) / 1e6);   // the first loop of a part includes the switch
                first = false;
                prev = now;
            }
            if (s != null) {
                onSeconds += (System.nanoTime() - t0) / 1e9;
                double[] last = s.bb.values();
                List<String> names = s.bb.names();
                s.finish();
                try { s.thread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                samples += s.bb.rec.samples();
                int ok = 0;
                for (int i = 0; i < Math.min(names.size(), last.length); i++) if (names.get(i).endsWith(".pos") && !Double.isNaN(last[i])) ok++;
                encOk = Math.max(encOk, ok);
            }
        }
        if (!op.opModeIsActive()) return;

        double mOff = pct(off, 50), mOn = pct(on, 50), pOff = pct(off, 95), pOn = pct(on, 95), d = mOn - mOff;
        char loop = d <= 1 && pOn - pOff <= 3 ? 'g' : d <= 3 ? 'y' : 'r';
        double expected = period > 0 ? onSeconds * 1000 / period : 0;
        char rec = samples >= 0.7 * expected && samples > 0 ? 'g' : samples > 0 ? 'y' : 'r';
        char enc = motors.isEmpty() ? 'n' : encOk >= motors.size() ? 'g' : 'r';
        String[] report = {
            Html.title(tr("Recording test: result", "Проверка записи: результат", "Test înregistrare: rezultat")),
            Html.lv(loop, String.format(java.util.Locale.US, tr("Loop: %.1f ms without recording, %.1f ms with it (%+.1f ms)",
                    "Цикл: %.1f мс без записи, %.1f мс с записью (%+.1f мс)", "Ciclu: %.1f ms fără înregistrare, %.1f ms cu ea (%+.1f ms)"), mOff, mOn, d)),
            Html.dim(loop == 'g' ? tr("the recording does not slow your program", "запись не мешает вашей программе", "înregistrarea nu încetinește programul")
                    : loop == 'y' ? tr("a little slower: if the robot drives differently, switch the recording off (Y)", "чуть медленнее: если робот ездит иначе, выключите запись (Y)", "puțin mai lent: dacă robotul merge altfel, opriți înregistrarea (Y)")
                    : tr("clearly slower: switch the recording off (Y) and tell us", "заметно медленнее: выключите запись (Y) и напишите нам", "clar mai lent: opriți înregistrarea (Y) și scrieți-ne")),
            Html.lv(rec, String.format(java.util.Locale.US, tr("Recorded %d samples in %.0f s (expected about %.0f)", "Записано %d замеров за %.0f с (ожидалось около %.0f)",
                    "Înregistrate %d măsurători în %.0f s (așteptat cam %.0f)"), samples, onSeconds, expected)),
            motors.isEmpty() ? Html.dim(tr("No motors in the configuration", "В конфигурации нет моторов", "Nu sunt motoare în configurație"))
                    : Html.lv(enc, String.format(tr("Encoders read: %d of %d motors", "Энкодеры читаются: %d из %d моторов", "Encodere citite: %d din %d motoare"), encOk, motors.size())),
            Html.dim(tr("The test recording was deleted.", "Тестовая запись удалена.", "Înregistrarea de test a fost ștearsă.")),
        };
        while (op.opModeIsActive()) {
            for (String l : report) t.addLine(l);
            t.update();
            op.idle();
        }
    }

    private static double pct(List<Double> v, int p) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<Double>(v);
        Collections.sort(s);
        return s.get(Math.min(s.size() - 1, s.size() * p / 100));
    }

    private static String tr(String en, String ru, String ro) { return AsaBlackBoxOpMode.tr(en, ru, ro); }

    /** Driver Hub colors (see AsaBlackBoxOpMode). */
    private static final class Html extends AsaBlackBoxOpMode.Html {}

    /** Switched on unless the file says "off". */
    static boolean enabled() { return !"off".equals(AsaBlackBoxOpMode.read(new File(Recorder.defaultDir(), "autorecord.txt"))); }

    static void setEnabled(boolean on) { AsaBlackBoxOpMode.write(new File(Recorder.defaultDir(), "autorecord.txt"), on ? "on" : "off"); }

    /** Not recorded: the idle "Stop Robot" OpMode and ASA's own programs (they record themselves when they need to). */
    static boolean skip(String name, OpMode op) {
        if (name == null || name.isEmpty() || OpModeManager.DEFAULT_OP_MODE_NAME.equals(name)) return true;
        if (name.startsWith("ASA ")) return true;
        // by class, not by "Asa..." in general: a team may call its own drive program AsaDrive. SysId OpModes extend
        // AsaSysIdOpMode (checked by name: the Black Box package does not contain that class); extra bus traffic
        // would disturb their measurement.
        for (Class<?> c = op.getClass(); c != null; c = c.getSuperclass()) {
            String n = c.getSimpleName();
            if (n.equals("AsaBlackBoxOpMode") || n.equals("AsaSysIdOpMode") || c.getName().startsWith("org.asa.tools.")) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- SDK notifications (event loop thread)

    @Override public void onOpModePreInit(OpMode op) {
        Session old = session;
        if (old != null) old.finish();
        session = null;
        try {
            if (op == null || !enabled()) return;
            String name = manager.getActiveOpModeName();
            if (skip(name, op)) return;
            Session s = new Session(op, name);
            s.begin();
            session = s;
        } catch (Throwable ignored) { session = null; }
    }

    @Override public void onOpModePreStart(OpMode op) {
        Session s = session;
        if (s != null) s.started = true;
    }

    @Override public void onOpModePostStop(OpMode op) {
        Session s = session;
        session = null;
        if (s != null) s.finish();
    }

    /** Autonomous by the SDK's own list (Java and Blocks alike), else by the annotation. */
    static boolean isAutonomous(OpMode op, String name) {
        try {
            org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta meta =
                    org.firstinspires.ftc.robotcore.internal.opmode.RegisteredOpModes.getInstance().getOpModeMetadata(name);
            if (meta != null) return meta.flavor == org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta.Flavor.AUTONOMOUS;
        } catch (Throwable ignored) {}
        return op.getClass().isAnnotationPresent(com.qualcomm.robotcore.eventloop.opmode.Autonomous.class);
    }

    /** Recordings kept on the Control Hub; teams have no easy way to delete files there, so the oldest go first. */
    static final int KEEP = 300;

    /**
     * Deletes the oldest recordings (all files of a session) beyond keep. Names start with yyyyMMdd_HHmmss, so name
     * order is time order. Center of gravity results, battery history and settings are not recordings: never touched.
     */
    static void prune(File dir, int keep) {
        try {
            String[] all = dir == null ? null : dir.list();
            if (all == null) return;
            java.util.List<String> sessions = new java.util.ArrayList<String>();
            for (String f : all)
                if (f.matches("\\d{8}_\\d{6}_.+\\.csv") && !f.endsWith(".events.csv") && !f.endsWith(".summary.csv"))
                    sessions.add(f.substring(0, f.length() - 4));
            if (sessions.size() <= keep) return;
            java.util.Collections.sort(sessions);
            for (String old : sessions.subList(0, sessions.size() - keep))
                for (String suffix : new String[]{".csv", ".events.csv", ".summary.csv", ".report.txt", ".crash.txt"}) new File(dir, old + suffix).delete();
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- one recording

    private static final class Session implements Runnable {
        final OpMode op;
        final String name;
        BlackBox bb;
        FtcBlackBox.Bulk bulk;
        volatile boolean running = true, started, startLogged;
        boolean test;                 // self-test: never kept
        Thread thread;
        long attachedNs, periodMs, startNs;

        Session(OpMode op, String name) { this.op = op; this.name = name; }

        void begin() {
            bb = BlackBox.create(name);
            bulk = FtcBlackBox.attachBackground(bb, op.hardwareMap);
            // in Autonomous the Driver Station may stop sending the gamepads: that is not a disconnect
            if (!isAutonomous(op, name)) FtcBlackBox.attachGamepads(bb, op.gamepad1, op.gamepad2);
            attachedNs = System.nanoTime();
            periodMs = PERIOD_MS_PER_HUB * Math.max(1, bulk.hubs.size());
            bb.start();
            bb.event("AUTO_RECORD", name);
            Thread t = new Thread(this, "asa-autorecord");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);   // the team's OpMode thread always goes first
            thread = t;
            t.start();
        }

        void finish() { running = false; }

        @Override public void run() {
            prune(bb.rec.dir(), KEEP);
            int errors = 0;
            boolean manual = false;
            try {
                while (running) {
                    long t0 = System.nanoTime();
                    if (FtcBlackBox.manualAttachNs > attachedNs) { manual = true; break; }   // the team records this OpMode itself
                    if (started && !startLogged) { bb.event("START", ""); startLogged = true; startNs = System.nanoTime(); }
                    if (startLogged && !bulk.armed && System.nanoTime() - startNs > 1000000000L) bulk.armed = true;
                    try {
                        bulk.refresh();
                        bb.update();
                        errors = 0;
                    } catch (Throwable e) {
                        if (++errors > 20) { bb.event("AUTO_RECORD_ERROR", String.valueOf(e)); break; }   // ~1 s of failures: give up
                    }
                    long left = periodMs - (System.nanoTime() - t0) / 1000000;
                    Thread.sleep(Math.max(5, left));
                }
            } catch (Throwable ignored) {
            } finally {
                // INIT then STOP without START is a mistake or a test of the init code: nothing worth keeping
                if (!manual && started && !startLogged) try { bb.event("START", ""); } catch (Throwable ignored) {}   // STOP in the same 50 ms
                if (manual || !started || test) discard(); else try { bb.stop(); } catch (Throwable ignored) {}
            }
        }

        /** Not kept: the team's own Black Box records this OpMode (no double entry), or the OpMode never started. */
        private void discard() {
            try {
                bb.rec.stop();
                File dir = bb.rec.dir();
                String s = bb.rec.session();
                if (dir == null || s == null) return;
                for (String suffix : new String[]{".csv", ".events.csv", ".summary.csv", ".report.txt"}) new File(dir, s + suffix).delete();
            } catch (Throwable ignored) {}
        }
    }
}
