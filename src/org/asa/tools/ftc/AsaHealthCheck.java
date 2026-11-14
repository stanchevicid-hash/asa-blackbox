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
    private final boolean[] last = new boolean[5];

    @Override
    public void runOpMode() {
        File dir = Recorder.defaultDir();
        File labelFile = new File(dir, "battery-label.txt");
        int label = indexOf(read(labelFile));
        List<LynxModule> hubs = hardwareMap.getAll(LynxModule.class);
        List<DcMotorEx> motors = new ArrayList<DcMotorEx>();
        List<String> names = new ArrayList<String>();
        for (DcMotor m : hardwareMap.getAll(DcMotor.class))
            if (m instanceof DcMotorEx) { motors.add((DcMotorEx) m); names.add(hardwareMap.getNamesOf(m).iterator().next()); }

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

        BlackBox bb = BlackBox.create("healthcheck");
        if (label > 0) bb.battery(LABELS[label]);
        FtcBlackBox.attachAll(bb, hardwareMap);
        AsaWeb.start();
        AsaWeb.publish(bb);
        bb.start();

        Map<String, String> verdict = new LinkedHashMap<String, String>();
        int sel = 0, startTicks = 0;
        double pulsePower = 0, maxVel = 0, maxAmps = 0;
        boolean done = false;
        List<String> report = new ArrayList<String>();
        try {
            while (opModeIsActive()) {
                if (!done && !motors.isEmpty()) {
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
                        int moved = m.getCurrentPosition() - startTicks;
                        String enc = Math.abs(moved) < 5 ? "encoder: no counts (none, unplugged, or jammed)"
                                : "encoder OK, " + (Math.signum(moved) == Math.signum(pulsePower) ? "+power counts up" : "+power counts DOWN");
                        verdict.put(names.get(sel), String.format("%s; max %.0f ticks/s, %.1f A", enc, maxVel, maxAmps));
                    }
                    pulsePower = want;
                }
                if (edge(4, gamepad1.x) && !done) {
                    for (DcMotorEx mm : motors) mm.setPower(0);
                    report = bb.stop();
                    done = true;
                }
                if (!done) bb.update();

                for (LynxModule h : hubs) {
                    String hn = hardwareMap.getNamesOf(h).isEmpty() ? "hub" : hardwareMap.getNamesOf(h).iterator().next();
                    telemetry.addLine(String.format("%s: %s, %.0f C, %.2f V", hn, h.isNotResponding() ? "NOT RESPONDING" : "ok",
                            h.getTemperature(TempUnit.CELSIUS), h.getInputVoltage(VoltageUnit.VOLTS)));
                }
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
