// ASA Black Box library file. "Remove ASA" in the ASA Black Box program deletes it; keep your own code in other files.
package org.firstinspires.ftc.teamcode;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.IMU;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Center of gravity" mode of the ASA Black Box program: center of gravity and tipping limits, measured with the hub's
 * IMU. No motor moves. Needs an IMU named "imu" (the default in every Control Hub configuration).
 *
 * INIT: dpad up/down = wheelbase, left/right = track (cm, wheel ground contact to ground contact).
 * START: for each step, put the robot as shown, hold it still, press A. B = back one step, Y = start over.
 * The result is saved as "<date>_cog.txt" and drawn on the results page (top and side view).
 */
final class AsaCenterOfGravity {

    /** Driver Hub colors and helpers (see AsaBlackBoxOpMode). */
    private static final class H extends AsaBlackBoxOpMode.Html {}

    private final LinearOpMode op;
    private final boolean[] last = new boolean[7];
    /** A balance step tips the robot at least this far from flat (degrees); real balance angles are 20-60°. */
    static final double MIN_TILT = 3;
    private final File settings = new File(AsaRecorder.defaultDir(), "cog-settings.txt");
    private double wheelbase, track;

    AsaCenterOfGravity(LinearOpMode op) {
        this.op = op;
        double[] size = load(settings);
        wheelbase = size[0];
        track = size[1];
    }

    static String[] steps() {
        return new String[]{
                tr("flat on the floor", "стоит ровно на полу", "drept pe podea"),
                tr("on its REAR wheels: lift the front until it balances by itself",
                   "на ЗАДНИХ колёсах: поднимите перёд, пока робот не будет держать равновесие сам",
                   "pe roțile din SPATE: ridicați fața până se echilibrează singur"),
                tr("on its FRONT wheels: lift the rear until it balances",
                   "на ПЕРЕДНИХ колёсах: поднимите зад, пока не будет держать равновесие",
                   "pe roțile din FAȚĂ: ridicați spatele până se echilibrează"),
                tr("on its RIGHT wheels: lift the left side until it balances",
                   "на ПРАВЫХ колёсах: поднимите левую сторону, пока не будет держать равновесие",
                   "pe roțile din DREAPTA: ridicați partea stângă până se echilibrează"),
                tr("on its LEFT wheels: lift the right side until it balances",
                   "на ЛЕВЫХ колёсах: поднимите правую сторону, пока не будет держать равновесие",
                   "pe roțile din STÂNGA: ridicați partea dreaptă până se echilibrează")};
    }

    /** One pass of the INIT screen. */
    void initLoop() {
        Gamepad g = op.gamepad1;
        Telemetry t = op.telemetry;
        if (edge(0, g.dpad_up)) wheelbase += 0.5;
        if (edge(1, g.dpad_down)) wheelbase = Math.max(5, wheelbase - 0.5);
        if (edge(2, g.dpad_right)) track += 0.5;
        if (edge(3, g.dpad_left)) track = Math.max(5, track - 0.5);
        t.addLine(H.dim(tr("Wheelbase, front to back (dpad ▲ ▼): ", "Колёсная база, перёд-зад (dpad ▲ ▼): ", "Ampatament, față-spate (dpad ▲ ▼): "))
                + "<b>" + String.format(java.util.Locale.US, "%.1f cm", wheelbase) + "</b>");
        t.addLine(H.dim(tr("Track, left to right (dpad ◀ ▶): ", "Колея, лево-право (dpad ◀ ▶): ", "Ecartament, stânga-dreapta (dpad ◀ ▶): "))
                + "<b>" + String.format(java.util.Locale.US, "%.1f cm", track) + "</b>");
        t.addLine(H.dim(tr("Measure where the wheels touch the floor, center to center. No motor moves.",
                "Меряйте между точками, где колёса касаются пола. Моторы не крутятся.",
                "Măsurați între punctele unde roțile ating podeaua. Niciun motor nu se mișcă.")));
    }

    void run() {
        Telemetry telemetry = op.telemetry;
        Gamepad gamepad1 = op.gamepad1;
        save(settings, wheelbase, track);
        IMU imu = op.hardwareMap.tryGet(IMU.class, "imu");
        if (imu == null) {
            while (op.opModeIsActive()) {
                telemetry.addLine(H.lv('r', tr("No IMU named \"imu\" in the configuration.", "В конфигурации нет IMU с именем \"imu\".", "Nu există un IMU numit \"imu\" în configurație.")));
                telemetry.update();
                op.idle();
            }
            return;
        }
        // any orientation works: only the angle between two readings is used
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP, RevHubOrientationOnRobot.UsbFacingDirection.FORWARD)));

        String[] steps = steps();
        double[][] taken = new double[steps.length][];
        double[] pBuf = new double[25], rBuf = new double[25];
        int n = 0, step = 0;
        AsaCenterOfGravityMath result = null;
        String saved = "";
        try {
            while (op.opModeIsActive()) {
                YawPitchRollAngles a = imu.getRobotYawPitchRollAngles();
                pBuf[n % pBuf.length] = a.getPitch(AngleUnit.DEGREES);
                rBuf[n % rBuf.length] = a.getRoll(AngleUnit.DEGREES);
                n++;
                int k = Math.min(n, pBuf.length);
                double pm = 0, rm = 0;
                for (int i = 0; i < k; i++) { pm += pBuf[i]; rm += rBuf[i]; }
                pm /= k; rm /= k;
                double wobble = 0;
                for (int i = 0; i < k; i++) wobble = Math.max(wobble, AsaCenterOfGravityMath.tiltBetween(pm, rm, pBuf[i], rBuf[i]));
                boolean steady = k == pBuf.length && wobble < 0.5;
                double tilt = taken[0] == null ? 0 : AsaCenterOfGravityMath.tiltBetween(taken[0][0], taken[0][1], pm, rm);

                // steps 2-5 must really tip the robot: a second press without moving would give a 0° angle (and a division by 0)
                boolean tipped = step == 0 || tilt >= MIN_TILT;
                if (result == null && edge(4, gamepad1.a) && steady && tipped) {
                    taken[step] = new double[]{pm, rm};
                    step++;
                    n = 0;   // the next step averages only readings taken after this press
                    if (step == steps.length) {
                        double[] deg = new double[4];
                        for (int i = 1; i < steps.length; i++) deg[i - 1] = AsaCenterOfGravityMath.tiltBetween(taken[0][0], taken[0][1], taken[i][0], taken[i][1]);
                        result = AsaCenterOfGravityMath.compute(wheelbase, track, deg[0], deg[1], deg[2], deg[3]);
                        saved = write(result, deg);
                    }
                }
                if (edge(5, gamepad1.b) && step > 0 && result == null) taken[--step] = null;
                if (edge(6, gamepad1.y)) { step = 0; result = null; saved = ""; java.util.Arrays.fill(taken, null); }

                if (result == null) {
                    String s = tr("Step ", "Шаг ", "Pasul ") + (step + 1) + "/" + steps.length + ": " + steps[step];
                    StringBuilder dots = new StringBuilder();
                    for (int i = 0; i < steps.length; i++) dots.append(i < step ? "●" : i == step ? "◉" : "○").append(' ');
                    telemetry.addLine(H.title(tr("Center of gravity", "Центр тяжести", "Centrul de greutate")) + H.gap() + H.c(H.PINK, dots.toString()));
                    telemetry.addLine("<b>" + H.esc(steps[step]) + "</b>");
                    telemetry.addLine(!tipped ? H.lv('y', String.format(java.util.Locale.US, tr("tip it more: at least %.0f°", "наклоните сильнее: хотя бы %.0f°", "înclinați mai mult: cel puțin %.0f°"), MIN_TILT))
                            : steady ? H.lv('g', tr("steady: press A", "робот неподвижен: нажмите A", "stabil: apăsați A"))
                                     : H.lv('y', tr("hold still...", "держите неподвижно...", "țineți nemișcat...")));
                    if (taken[0] != null) telemetry.addLine(H.dim(tr("tilt ", "наклон ", "înclinare ")) + String.format(java.util.Locale.US, "%.1f°", tilt));
                    telemetry.addLine(H.dim(tr("B = back, Y = start over", "B = назад, Y = начать заново", "B = înapoi, Y = de la început")));
                    AsaWeb.status(tr("Center of gravity: ", "Центр тяжести: ", "Centrul de greutate: ") + s);
                } else {
                    for (String l : linesHtml(result)) telemetry.addLine(l);
                    telemetry.addLine(H.dim(saved));
                    telemetry.addLine(H.dim(tr("The drawing is on the results page. Y = measure again (e.g. with the lift up)",
                            "Рисунок на странице результатов. Y = измерить ещё раз (например, с поднятым лифтом)",
                            "Desenul e pe pagina de rezultate. Y = măsoară din nou (de ex. cu liftul ridicat)")));
                    AsaWeb.status("");
                }
                telemetry.addLine("");
                telemetry.addLine(H.dim(FtcBlackBox.resultsHint()));
                telemetry.update();
            }
        } finally {
            AsaWeb.status("");
        }
    }

    /**
     * The result on the Driver Hub with the same traffic lights as the results page: a limit of 8 m/s² or more is safe
     * (the wheels slip first), 5 to 8 can tip on a hard stop or a hit, below 5 tips easily; ramps: 30° and 15°.
     */
    static java.util.List<String> linesHtml(AsaCenterOfGravityMath c) {
        java.util.List<String> t = lines(c), out = new java.util.ArrayList<String>();
        out.add(H.title(t.get(0)));
        for (int i = 1; i <= 3; i++) out.add("<b>" + H.esc(t.get(i)) + "</b>");
        char worst = 'g';
        for (double a : new double[]{c.maxBrake(), c.maxAccel(), c.maxSideways()}) worst = worse(worst, a >= 8 ? 'g' : a >= 5 ? 'y' : 'r');
        worst = worse(worst, c.maxRampDeg() >= 30 ? 'g' : c.maxRampDeg() >= 15 ? 'y' : 'r');
        out.add(H.lv(worst, t.get(4)));
        for (int i = 5; i < t.size(); i++) out.add(H.lv('y', t.get(i)));
        return out;
    }

    private static char worse(char a, char b) { return a == 'r' || b == 'r' ? 'r' : a == 'y' || b == 'y' ? 'y' : 'g'; }

    /** The result in the chosen language (plain text, also written to the file). */
    static java.util.List<String> lines(AsaCenterOfGravityMath c) {
        java.util.List<String> l = new java.util.ArrayList<String>();
        l.add(tr("Center of gravity (cm):", "Центр тяжести (см):", "Centrul de greutate (cm):"));
        l.add(String.format(Locale.US, tr("height %.1f  (front/back %.1f, left/right %.1f)", "высота %.1f  (перёд/зад %.1f, лево/право %.1f)",
                "înălțime %.1f  (față/spate %.1f, stânga/dreapta %.1f)"), c.height, c.heightFrontBack, c.heightLeftRight));
        l.add(String.format(Locale.US, tr("from rear wheels %.1f of %.1f (%.0f%%)", "от задних колёс %.1f из %.1f (%.0f%%)",
                "de la roțile din spate %.1f din %.1f (%.0f%%)"), c.fromRear, c.wheelbase, 100 * c.fromRear / c.wheelbase));
        l.add(String.format(Locale.US, tr("from right wheels %.1f of %.1f (%.0f%%)", "от правых колёс %.1f из %.1f (%.0f%%)",
                "de la roțile din dreapta %.1f din %.1f (%.0f%%)"), c.fromRight, c.track, 100 * c.fromRight / c.track));
        l.add(String.format(Locale.US, tr("tips at: brake %.1f m/s2, accel %.1f, sideways %.1f, ramp %.0f deg",
                "опрокинется при: торможении %.1f м/с2, разгоне %.1f, вбок %.1f, уклоне %.0f°",
                "se răstoarnă la: frânare %.1f m/s2, accelerare %.1f, lateral %.1f, pantă %.0f°"),
                c.maxBrake(), c.maxAccel(), c.maxSideways(), c.maxRampDeg()));
        if (!c.notes.isEmpty()) l.add(tr("the two heights differ a lot: redo the balance tests more carefully",
                "две высоты сильно отличаются: повторите шаги аккуратнее",
                "cele două înălțimi diferă mult: refaceți pașii mai atent"));
        return l;
    }

    /**
     * "<date>_cog.txt": line 1 is for the results page (key=value), then the result in words.
     * The page draws the robot from line 1, so keep its keys.
     */
    private static String write(AsaCenterOfGravityMath c, double[] deg) {
        try {
            File dir = AsaRecorder.defaultDir();
            dir.mkdirs();
            File f = new File(dir, new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_cog.txt");
            FileWriter w = new FileWriter(f);
            w.write(String.format(Locale.US,
                    "ASA COG wheelbase=%.2f track=%.2f fromRear=%.2f fromRight=%.2f height=%.2f heightFB=%.2f heightLR=%.2f"
                            + " brake=%.2f accel=%.2f sideways=%.2f ramp=%.1f rear=%.2f front=%.2f right=%.2f left=%.2f%n",
                    c.wheelbase, c.track, c.fromRear, c.fromRight, c.height, c.heightFrontBack, c.heightLeftRight,
                    c.maxBrake(), c.maxAccel(), c.maxSideways(), c.maxRampDeg(), deg[0], deg[1], deg[2], deg[3]));
            for (String l : c.lines()) w.write(l + "\n");
            w.close();
            return tr("saved ", "сохранено ", "salvat ") + f.getName();
        } catch (Exception e) {
            return tr("not saved: ", "не сохранено: ", "nesalvat: ") + e.getMessage();
        }
    }

    private static double[] load(File f) {
        double[] v = {30, 30};
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String[] p = r.readLine().trim().split(" ");
            r.close();
            v[0] = Double.parseDouble(p[0]);
            v[1] = Double.parseDouble(p[1]);
            for (int i = 0; i < 2; i++) if (!(v[i] >= 5 && v[i] < 500)) v[i] = 30;   // a damaged file must not give 0 or NaN
        } catch (Exception ignored) {}
        return v;
    }

    private static void save(File f, double wheelbase, double track) {
        try {
            f.getParentFile().mkdirs();
            FileWriter w = new FileWriter(f);
            w.write(wheelbase + " " + track + "\n");
            w.close();
        } catch (Exception ignored) {}
    }

    private static String tr(String en, String ru, String ro) { return AsaBlackBoxOpMode.tr(en, ru, ro); }

    private boolean edge(int i, boolean now) {
        boolean r = now && !last[i];
        last[i] = now;
        return r;
    }
}
