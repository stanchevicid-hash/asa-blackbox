// ASA Black Box library file. "Remove ASA" in the ASA Black Box program deletes it; keep your own code in other files.
package org.firstinspires.ftc.teamcode;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.Gamepad;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.firstinspires.ftc.robotcore.external.navigation.TempUnit;
import org.firstinspires.ftc.robotcore.external.navigation.VoltageUnit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Health check" mode of the ASA Black Box program. Hubs (answering, temperature, firmware), the battery (now, and each
 * battery's health from past sessions), and every motor: a motor only turns while you hold A (+) or B (-), at 0.3,
 * or one after another by itself with Y.
 *
 * INIT: dpad left/right = which battery is in (A..H). START: Y = all motors by itself, or dpad up/down = motor,
 * hold A/B = turn it; X = finish and show the report.
 */
final class AsaHealthCheck {

    /** Driver Hub colors and helpers (see AsaBlackBoxOpMode). */
    private static final class H extends AsaBlackBoxOpMode.Html {}

    static final String[] LABELS = {"-", "A", "B", "C", "D", "E", "F", "G", "H"};
    static final double AUTO_POWER = 0.3, AUTO_SECONDS = 1.5;

    private final LinearOpMode op;
    private final boolean[] last = new boolean[6];
    private boolean waitRelease;
    private final File dir = AsaRecorder.defaultDir(), labelFile = new File(dir, "battery-label.txt");
    private int label;
    private final List<LynxModule> hubs;
    private final Map<LynxModule, String> firmware = new HashMap<LynxModule, String>();
    private final List<DcMotorEx> motors = new ArrayList<DcMotorEx>();
    private final List<String> names = new ArrayList<String>();

    AsaHealthCheck(LinearOpMode op) {
        this.op = op;
        label = indexOf(AsaBlackBoxOpMode.read(labelFile));
        hubs = op.hardwareMap.getAll(LynxModule.class);
        for (LynxModule h : hubs) { String v; try { v = h.getFirmwareVersionString(); } catch (Exception e) { v = "?"; } firmware.put(h, v); }   // read once: a command to the hub
        for (DcMotor m : op.hardwareMap.getAll(DcMotor.class))
            if (m instanceof DcMotorEx) { motors.add((DcMotorEx) m); names.add(op.hardwareMap.getNamesOf(m).iterator().next()); }
    }

    /** One pass of the INIT screen. */
    void initLoop() {
        Telemetry t = op.telemetry;
        if (edge(0, op.gamepad1.dpad_right)) label = (label + 1) % LABELS.length;
        if (edge(1, op.gamepad1.dpad_left)) label = (label + LABELS.length - 1) % LABELS.length;
        t.addLine(H.dim(tr("Battery in the robot (dpad ◀ ▶): ", "Батарея в роботе (dpad ◀ ▶): ", "Bateria din robot (dpad ◀ ▶): "))
                + "<b>" + H.esc(LABELS[label]) + "</b>");
        t.addLine(H.dim(tr("Hubs: ", "Хабов: ", "Huburi: ")) + hubs.size() + H.gap() + H.dim(tr("Motors: ", "Моторов: ", "Motoare: ")) + motors.size());
        for (String l : batteryHistory(dir)) t.addLine(l);
        t.addLine("");
        t.addLine(H.dim(tr("START, then Y = test every motor by itself", "START, потом Y = проверить все моторы автоматически",
                "START, apoi Y = testează singur toate motoarele")));
        t.addLine(H.c(H.YELLOW, tr("Lift the robot or clear space: the motors will turn.", "Поднимите робота или освободите место: моторы будут крутиться.",
                "Ridicați robotul sau faceți loc: motoarele se vor învârti.")));
    }

    void run() {
        AsaBlackBoxOpMode.write(labelFile, LABELS[label]);
        // Raw power, no built-in PID: a motor left in RUN_TO_POSITION by an earlier OpMode would hold still and look like
        // a dead encoder. RUN_WITHOUT_ENCODER still counts ticks, it only stops the hub from steering the motor.
        for (DcMotorEx m : motors) {
            m.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        }
        Telemetry telemetry = op.telemetry;
        Gamepad gamepad1 = op.gamepad1;
        BlackBox bb = session();

        Map<String, String> verdict = new LinkedHashMap<String, String>();
        int sel = 0, startTicks = 0;
        double pulsePower = 0, maxVel = 0, maxAmps = 0;
        boolean done = false;
        int auto = -1;                                                    // automatic test: index of the motor, -1 = off
        double autoT0 = -1;
        List<String> report = new ArrayList<String>();
        try {
            while (op.opModeIsActive()) {
                if (waitRelease && !gamepad1.a && !gamepad1.b) waitRelease = false;
                if (!done && !motors.isEmpty() && auto < 0 && !waitRelease) {
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
                    double t = op.getRuntime();
                    if (gamepad1.b) {                                    // B stops the automatic test at once
                        stopAll();
                        auto = -1;
                        waitRelease = true;                              // B still held must not turn this motor backward
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
                        } else op.sleep(300);                            // let it stop before the next one
                    }
                }
                if (edge(4, gamepad1.x) && !done) {
                    stopAll();
                    report = bb.stop();
                    done = true;
                }
                else if (edge(5, gamepad1.y) && done && !motors.isEmpty()) {                     // test again: the automatic test, results kept
                    bb = session();
                    done = false;
                    auto = 0;
                    autoT0 = -1;
                }
                if (!done) bb.update();

                for (LynxModule h : hubs) {
                    String hn = op.hardwareMap.getNamesOf(h).isEmpty() ? "hub" : op.hardwareMap.getNamesOf(h).iterator().next();
                    boolean lost = h.isNotResponding();
                    double temp = h.getTemperature(TempUnit.CELSIUS), volts = h.getInputVoltage(VoltageUnit.VOLTS);
                    telemetry.addLine(H.lv(lost ? 'r' : temp > 60 || volts < 12 ? 'y' : 'g', String.format("%s: %s, %.0f °C, %.2f V", hn,
                            lost ? tr("NOT RESPONDING", "НЕ ОТВЕЧАЕТ", "NU RĂSPUNDE") : "ok", temp, volts))
                            + H.dim("   firmware " + firmware.get(h)));
                }
                telemetry.addLine("");
                if (done) {
                    telemetry.addLine(H.title(tr("Report", "Отчёт", "Raport")) + H.gap() + H.dim(tr("Y = test again", "Y = проверить ещё раз", "Y = testează din nou")));
                } else if (auto >= 0) {
                    telemetry.addLine(H.title(tr("Automatic test ", "Автопроверка ", "Test automat ") + (auto + 1) + " / " + motors.size())
                            + H.gap() + "<b>" + H.c(H.RED, "B = STOP") + "</b>");
                } else {
                    telemetry.addLine(H.dim(tr("Y = test ALL motors by itself", "Y = проверить ВСЕ моторы автоматически", "Y = testează singur TOATE motoarele")));
                    telemetry.addLine(H.dim(tr("or dpad ▲ ▼ = motor, hold A / B = turn it, X = report", "или dpad ▲ ▼ = мотор, держать A / B = крутить, X = отчёт",
                            "sau dpad ▲ ▼ = motor, țineți A / B = îl rotește, X = raport")));
                }
                if (!done && !motors.isEmpty()) {
                    DcMotorEx m = motors.get(sel);
                    telemetry.addLine(H.pick(names.get(sel) + "  (" + (sel + 1) + "/" + motors.size() + ")")
                            + (pulsePower != 0 || auto >= 0 ? "  " + H.c(H.YELLOW, tr("turning", "крутится", "se rotește")) : ""));
                    telemetry.addLine(H.dim(String.format("ticks %d   vel %.0f   %.1f A", m.getCurrentPosition(), m.getVelocity(), m.getCurrent(CurrentUnit.AMPS))));
                }
                for (Map.Entry<String, String> e : verdict.entrySet())
                    telemetry.addLine(H.lv(e.getValue().charAt(0), e.getKey() + ": " + e.getValue().substring(1)));
                for (String w : bb.warnings()) telemetry.addLine(H.lv(w.contains("CRITICAL") ? 'r' : 'y', w));
                if (done) for (String l : report) telemetry.addLine(H.dim(l));
                telemetry.addLine("");
                telemetry.addLine(H.dim(FtcBlackBox.resultsHint()));
                telemetry.update();
            }
        } finally {
            stopAll();
            bb.stop();
        }
    }

    /** Every motor to 0, each on its own: a hub that stopped answering must not leave the others turning. */
    private void stopAll() {
        for (DcMotorEx m : motors) { try { m.setPower(0); } catch (Exception ignored) {} }
    }

    /** What a short run of one motor says about it; the first character is its traffic light (g, y, r). */
    static String judge(int moved, double power, double maxVel, double maxAmps) {
        if (maxAmps < 0.2) return "r" + tr("NO CURRENT: power cable unplugged, in another port, or broken wire",
                "НЕТ ТОКА: кабель питания не подключён, в другом порту или оборван",
                "FĂRĂ CURENT: cablul de alimentare e scos, în alt port sau rupt");
        if (Math.abs(moved) < 5)
            return "y" + String.format("%.1f A, ", maxAmps) + tr("encoder: no counts (wheel turned? encoder cable unplugged or in another port; wheel still? jammed)",
                    "энкодер не считает (колесо крутилось? кабель энкодера не подключён или в другом порту; стояло? заклинило)",
                    "encoderul nu numără (roata s-a rotit? cablul encoderului e scos sau în alt port; a stat? e blocată)");
        boolean up = Math.signum(moved) == Math.signum(power);
        return (up ? "g" : "y") + String.format("OK %.1f A, ", maxAmps)
                + (up ? tr("encoder counts up", "энкодер считает вверх", "encoderul numără în sus")
                      : tr("encoder counts DOWN (reversed: setDirection or swap the encoder cable)", "энкодер считает ВНИЗ (наоборот: setDirection или поменяйте кабель энкодера)",
                           "encoderul numără în JOS (invers: setDirection sau inversați cablul encoderului)"))
                + String.format(", max %.0f ticks/s", maxVel);
    }

    private BlackBox session() {
        BlackBox bb = BlackBox.create("healthcheck");
        if (label > 0) bb.battery(LABELS[label]);
        FtcBlackBox.attachAll(bb, op.hardwareMap);   // also starts the results page and shows this session live
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
            String v = Double.isNaN(ohm) ? tr("not enough data (drive more)", "мало данных (поездите ещё)", "prea puține date (mai conduceți)")
                    : ohm < 0.15 ? tr("good", "хорошая", "bună")
                    : ohm < 0.25 ? tr("watch it", "следите за ней", "urmăriți-o")
                    : tr("weak: check connectors, then replace", "слабая: проверьте разъёмы, потом замените", "slabă: verificați conectorii, apoi înlocuiți-o");
            out.add(H.lv(Double.isNaN(ohm) ? 'n' : ohm < 0.15 ? 'g' : ohm < 0.25 ? 'y' : 'r',
                    String.format(tr("battery %s: %.3f ohm, %s", "батарея %s: %.3f Ом, %s", "bateria %s: %.3f ohm, %s"), e.getKey(), ohm, v)));
        }
        return out;
    }

    private static String tr(String en, String ru, String ro) { return AsaBlackBoxOpMode.tr(en, ru, ro); }

    private static int indexOf(String s) {
        for (int i = 0; i < LABELS.length; i++) if (LABELS[i].equals(s)) return i;
        return 0;
    }

    private boolean edge(int i, boolean now) {
        boolean r = now && !last[i];
        last[i] = now;
        return r;
    }
}
