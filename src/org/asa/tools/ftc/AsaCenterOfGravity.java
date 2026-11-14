package org.asa.tools.ftc;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.IMU;

import org.asa.tools.blackbox.CenterOfGravity;
import org.asa.tools.core.Recorder;
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
 * Center of gravity and tipping limits, measured with the hub's IMU. No motor moves.
 * Needs an IMU named "imu" (the default in every Control Hub configuration).
 *
 * INIT: dpad up/down = wheelbase, left/right = track (cm, wheel ground contact to ground contact).
 * START: for each step, put the robot as shown, hold it still, press A. B = back one step, Y = start over.
 */
@TeleOp(name = "ASA Center of Gravity", group = "ASA")
public class AsaCenterOfGravity extends LinearOpMode {

    static final String[] STEPS = {
            "flat on the floor",
            "on its REAR wheels: lift the front until it balances by itself",
            "on its FRONT wheels: lift the rear until it balances",
            "on its RIGHT wheels: lift the left side until it balances",
            "on its LEFT wheels: lift the right side until it balances"};

    private final boolean[] last = new boolean[7];

    @Override
    public void runOpMode() {
        IMU imu = hardwareMap.tryGet(IMU.class, "imu");
        if (imu == null) {
            telemetry.addLine("No IMU named \"imu\" in the configuration.");
            telemetry.update();
            waitForStart();
            return;
        }
        // any orientation works: only the angle between two readings is used
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP, RevHubOrientationOnRobot.UsbFacingDirection.FORWARD)));

        File settings = new File(Recorder.defaultDir(), "cog-settings.txt");
        double[] size = load(settings);
        double wheelbase = size[0], track = size[1];

        while (!isStarted() && !isStopRequested()) {
            if (edge(0, gamepad1.dpad_up)) wheelbase += 0.5;
            if (edge(1, gamepad1.dpad_down)) wheelbase = Math.max(5, wheelbase - 0.5);
            if (edge(2, gamepad1.dpad_right)) track += 0.5;
            if (edge(3, gamepad1.dpad_left)) track = Math.max(5, track - 0.5);
            telemetry.addLine("ASA Center of Gravity");
            telemetry.addData("wheelbase (dpad up/down)", "%.1f cm", wheelbase);
            telemetry.addData("track (dpad left/right)", "%.1f cm", track);
            telemetry.addLine("Measure wheel contact to wheel contact, then START.");
            telemetry.update();
            idle();
        }
        save(settings, wheelbase, track);

        double[][] taken = new double[STEPS.length][];
        double[] pBuf = new double[25], rBuf = new double[25];
        int n = 0, step = 0;
        CenterOfGravity result = null;
        String saved = "";
        while (opModeIsActive()) {
            YawPitchRollAngles a = imu.getRobotYawPitchRollAngles();
            pBuf[n % pBuf.length] = a.getPitch(AngleUnit.DEGREES);
            rBuf[n % rBuf.length] = a.getRoll(AngleUnit.DEGREES);
            n++;
            int k = Math.min(n, pBuf.length);
            double pm = 0, rm = 0;
            for (int i = 0; i < k; i++) { pm += pBuf[i]; rm += rBuf[i]; }
            pm /= k; rm /= k;
            double wobble = 0;
            for (int i = 0; i < k; i++) wobble = Math.max(wobble, CenterOfGravity.tiltBetween(pm, rm, pBuf[i], rBuf[i]));
            boolean steady = k == pBuf.length && wobble < 0.5;
            double tilt = taken[0] == null ? 0 : CenterOfGravity.tiltBetween(taken[0][0], taken[0][1], pm, rm);

            if (result == null && edge(4, gamepad1.a) && steady) {
                taken[step] = new double[]{pm, rm};
                step++;
                if (step == STEPS.length) {
                    double[] deg = new double[4];
                    for (int i = 1; i < STEPS.length; i++) deg[i - 1] = CenterOfGravity.tiltBetween(taken[0][0], taken[0][1], taken[i][0], taken[i][1]);
                    result = CenterOfGravity.compute(wheelbase, track, deg[0], deg[1], deg[2], deg[3]);
                    saved = write(result, deg);
                }
            }
            if (edge(5, gamepad1.b) && step > 0 && result == null) taken[--step] = null;
            if (edge(6, gamepad1.y)) { step = 0; result = null; saved = ""; java.util.Arrays.fill(taken, null); }

            if (result == null) {
                telemetry.addLine("Step " + (step + 1) + "/" + STEPS.length + ": robot " + STEPS[step]);
                telemetry.addLine(steady ? "steady: press A" : "hold still...");
                if (taken[0] != null) telemetry.addData("tilt", "%.1f deg", tilt);
                telemetry.addLine("B back, Y start over");
            } else {
                telemetry.addLine("Center of gravity (cm):");
                for (String l : result.lines()) telemetry.addLine(l);
                telemetry.addLine(saved);
                telemetry.addLine("Y = measure again (e.g. with the lift up)");
            }
            telemetry.update();
        }
    }

    private String write(CenterOfGravity c, double[] deg) {
        try {
            File dir = Recorder.defaultDir();
            dir.mkdirs();
            File f = new File(dir, new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "_cog.txt");
            FileWriter w = new FileWriter(f);
            w.write(String.format(Locale.US, "wheelbase %.1f cm, track %.1f cm%nangles rear %.2f front %.2f right %.2f left %.2f deg%n",
                    c.wheelbase, c.track, deg[0], deg[1], deg[2], deg[3]));
            for (String l : c.lines()) w.write(l + "\n");
            w.close();
            return "saved " + f.getName();
        } catch (Exception e) {
            return "not saved: " + e.getMessage();
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

    private boolean edge(int i, boolean now) {
        boolean r = now && !last[i];
        last[i] = now;
        return r;
    }
}
