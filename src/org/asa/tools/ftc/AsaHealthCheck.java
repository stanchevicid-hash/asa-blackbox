package org.asa.tools.ftc;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;

import org.asa.tools.blackbox.BatteryHealth;
import org.asa.tools.blackbox.BlackBox;
import org.asa.tools.core.Recorder;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.firstinspires.ftc.robotcore.external.navigation.TempUnit;
import org.firstinspires.ftc.robotcore.external.navigation.VoltageUnit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-robot check from the Driver Hub, no code needed. Hubs (answering, temperature), the battery (now, and each
 * battery's health from past sessions), and every motor: a motor only turns while you hold A (+) or B (-), at 0.3.
 *
 * INIT: dpad left/right = which battery is in (A..H). START: dpad up/down = motor, hold A/B = turn it, X = finish.
 */
@TeleOp(name = "ASA Health Check", group = "ASA")
public class AsaHealthCheck extends LinearOpMode {

    static final String[] LABELS = {"-", "A", "B", "C", "D", "E", "F", "G", "H"};
    private final boolean[] last = new boolean[6];

    @Override
    public void runOpMode() {
        File dir = Recorder.defaultDir();
        File labelFile = new File(dir, "battery-label.txt");
        int label = indexOf(read(labelFile));
        List<LynxModule> hubs = hardwareMap.getAll(LynxModule.class);
        java.util.Map<LynxModule, String> firmware = new java.util.HashMap<LynxModule, String>();    // read once: a command to the hub
        for (LynxModule h : hubs) { String v; try { v = h.getFirmwareVersionString(); } catch (Exception e) { v = "?"; } firmware.put(h, v); }
        List<DcMotorEx> motors = new ArrayList<DcMotorEx>();
        List<String> names = new ArrayList<String>();
        for (DcMotor m : hardwareMap.getAll(DcMotor.class))
            if (m instanceof DcMotorEx) { motors.add((DcMotorEx) m); names.add(hardwareMap.getNamesOf(m).iterator().next()); }
        // Raw power, no built-in PID: a motor left in RUN_TO_POSITION by an earlier OpMode would hold still and look like
        // a dead encoder. RUN_WITHOUT_ENCODER still counts ticks, it only stops the hub from steering the motor.
        for (DcMotorEx m : motors) {
            m.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        }

        while (!isStarted() && !isStopRequested()) {
            if (edge(0, gamepad1.dpad_right)) label = (label + 1) % LABELS.length;
            if (edge(1, gamepad1.dpad_left)) label = (label + LABELS.length - 1) % LABELS.length;
            telemetry.addLine("ASA Health Check");
            telemetry.addData("battery in the robot (dpad left/right)", LABELS[label]);
            telemetry.addData("hubs / motors", "%d / %d", hubs.size(), motors.size());
            for (String l : batteryHistory(dir)) telemetry.addLine(l);
            telemetry.addLine("START, then dpad up/down = motor, hold A/B = turn it, X = finish");
            telemetry.update();
            idle();
        }
        write(labelFile, LABELS[label]);

        BlackBox bb = session(label);
        AsaWeb.start();
        AsaWeb.publish(bb);

        Map<String, String> verdict = new LinkedHashMap<String, String>();
        int sel = 0, startTicks = 0;
        double pulsePower = 0, maxVel = 0, maxAmps = 0;
        boolean done = false;
        int auto = -1;                                                    // automatic test: index of the motor, -1 = off
        double autoT0 = -1;
        List<String> report = new ArrayList<String>();
        try {
            while (opModeIsActive()) {
                if (!done && !motors.isEmpty() && auto < 0) {
                    if (edge(2, gamepad1.dpad_down) && pulsePower == 0) sel = (sel + 1) % motors.size();
                    if (edge(3, gamepad1.dpad_up) && pulsePower == 0) sel = (sel + motors.size() - 1) % motors.size();
                    DcMotorEx m = motors.get(sel);
                    double want = gamepad1.a ? 0.3 : gamepad1.b ? -0.3 : 0;
                    if (want != 0 && pulsePower == 0) { startTicks = m.getCurrentPosition(); maxVel = 0; maxAmps = 0; }
                    if (want != 0) {
                        m.setPower(want);
                        maxVel = Math.max(maxVel, Math.abs(m.getVelocity()));
                        maxAmps = Math.max(maxAmps, m.getCurrent(CurrentUnit.AMPS));
                    }
                    if (want == 0 && pulsePower != 0) {                  // released: judge the motor
                        m.setPower(0);
                        verdict.put(names.get(sel), judge(m.getCurrentPosition() - startTicks, pulsePower, maxVel, maxAmps));
                    }
                    pulsePower = want;
                    // Y: every motor in turn by itself, then the report
                    if (edge(5, gamepad1.y) && pulsePower == 0) { auto = 0; autoT0 = -1; }
                }
                if (auto >= 0 && !done) {
                    DcMotorEx m = motors.get(auto);
                    sel = auto;
                    double t = getRuntime();
                    if (gamepad1.b) {                                    // B stops the automatic test at once
                        for (DcMotorEx mm : motors) mm.setPower(0);
                        auto = -1;
                    } else if (autoT0 < 0) {
                        autoT0 = t; startTicks = m.getCurrentPosition(); maxVel = 0; maxAmps = 0;
                        m.setPower(AUTO_POWER);
                    } else if (t - autoT0 < AUTO_SECONDS) {
                        if (t - autoT0 > 0.3) {                          // skip the spin-up spike
                            maxVel = Math.max(maxVel, Math.abs(m.getVelocity()));
                            maxAmps = Math.max(maxAmps, m.getCurrent(CurrentUnit.AMPS));
                        }
                    } else {
                        m.setPower(0);
                        verdict.put(names.get(auto), judge(m.getCurrentPosition() - startTicks, AUTO_POWER, maxVel, maxAmps));
                        autoT0 = -1;
                        auto++;
                        if (auto >= motors.size()) {                     // all done: report
                            auto = -1;
                            report = bb.stop();
                            done = true;
                        } else sleep(300);                               // let it stop before the next one
                    }
                }
                if (edge(4, gamepad1.x) && !done) {
                    for (DcMotorEx mm : motors) mm.setPower(0);
                    report = bb.stop();
                    done = true;
                }
                else if (edge(5, gamepad1.y) && done) {                     // back to testing, results kept
                    bb = session(label);
                    AsaWeb.publish(bb);
                    done = false;
                }
                if (!done) bb.update();

                for (LynxModule h : hubs) {
                    String hn = hardwareMap.getNamesOf(h).isEmpty() ? "hub" : hardwareMap.getNamesOf(h).iterator().next();
                    telemetry.addLine(String.format("%s: %s, %.0f C, %.2f V", hn, h.isNotResponding() ? "NOT RESPONDING" : "ok",
                            h.getTemperature(TempUnit.CELSIUS), h.getInputVoltage(VoltageUnit.VOLTS)));
                    telemetry.addLine("  firmware " + firmware.get(h) + " (update with REV Hardware Client if older than your team's other hubs)");
                }
                telemetry.addLine(done ? "Y = test again"
                        : auto >= 0 ? "AUTOMATIC TEST " + (auto + 1) + "/" + motors.size() + " | B = STOP"
                        : "Y = test ALL motors by itself | or: dpad up/down = motor, hold A / B = turn it | X = report");
                telemetry.addLine(FtcBlackBox.resultsHint());
                if (done) {
                    telemetry.addLine("Report:");
                    for (String l : report) telemetry.addLine(l);
                } else if (!motors.isEmpty()) {
                    DcMotorEx m = motors.get(sel);
                    telemetry.addLine("> " + names.get(sel) + (pulsePower != 0 ? "  TURNING" : "") + "  (" + (sel + 1) + "/" + motors.size() + ")");
                    telemetry.addData("ticks / vel / amps", "%d  %.0f  %.1f", m.getCurrentPosition(), m.getVelocity(), m.getCurrent(CurrentUnit.AMPS));
                }
                for (Map.Entry<String, String> e : verdict.entrySet()) telemetry.addLine(e.getKey() + ": " + e.getValue());
                for (String w : bb.warnings()) telemetry.addLine(w);
                telemetry.update();
            }
        } finally {
            for (DcMotorEx mm : motors) mm.setPower(0);
            bb.stop();
            AsaWeb.publish(null);
        }
    }

    static final double AUTO_POWER = 0.3, AUTO_SECONDS = 1.5;

    /** What a short run of one motor says about it. */
    static String judge(int moved, double power, double maxVel, double maxAmps) {
        if (maxAmps < 0.2) return "NO CURRENT: power cable unplugged, in another port, or broken wire";
        if (Math.abs(moved) < 5)
            return String.format("%.1f A, encoder: no counts (wheel turned? encoder cable unplugged or in another port; wheel still? jammed)", maxAmps);
        return String.format("OK %.1f A, encoder %s, max %.0f ticks/s", maxAmps,
                Math.signum(moved) == Math.signum(power) ? "counts up" : "counts DOWN (reversed)", maxVel);
    }

    private BlackBox session(int label) {
        BlackBox bb = BlackBox.create("healthcheck");
        if (label > 0) bb.battery(LABELS[label]);
        FtcBlackBox.attachAll(bb, hardwareMap);
        bb.start();
        return bb;
    }

    /** Last health of each battery from batteries.csv, worst first. */
    static List<String> batteryHistory(File dir) {
        Map<String, String> lastLine = new LinkedHashMap<String, String>();
        try {
            BufferedReader r = new BufferedReader(new FileReader(new File(dir, "batteries.csv")));
            r.readLine();
            for (String line; (line = r.readLine()) != null; ) {
                String[] p = line.split(",", -1);
                if (p.length >= 3) lastLine.put(p[1], p[2]);
            }
            r.close();
        } catch (Exception ignored) {}
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> e : lastLine.entrySet()) {
            double ohm;
            try { ohm = Double.parseDouble(e.getValue()); } catch (Exception x) { continue; }
            out.add(String.format("battery %s: %.3f ohm, %s", e.getKey(), ohm, BatteryHealth.verdict(ohm)));
        }
        return out;
    }

    private static int indexOf(String s) {
        for (int i = 0; i < LABELS.length; i++) if (LABELS[i].equals(s)) return i;
        return 0;
    }

    private static String read(File f) {
        try { BufferedReader r = new BufferedReader(new FileReader(f)); String s = r.readLine(); r.close(); return s == null ? "" : s.trim(); }
        catch (Exception e) { return ""; }
    }

    private static void write(File f, String s) {
        try { f.getParentFile().mkdirs(); FileWriter w = new FileWriter(f); w.write(s + "\n"); w.close(); } catch (Exception ignored) {}
    }

    private boolean edge(int i, boolean now) {
        boolean r = now && !last[i];
        last[i] = now;
        return r;
    }
}
