package org.asa.tools.examples;

import com.qualcomm.robotcore.eventloop.opmode.Disabled;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;

import org.asa.tools.blackbox.BlackBox;
import org.asa.tools.ftc.AsaWeb;
import org.asa.tools.ftc.FtcBlackBox;

/**
 * Black Box in a normal TeleOp. The whole integration: create + attachAll, start, update, stop.
 * Copy into your own TeleOp, change "lift" to your motor, remove @Disabled.
 */
@TeleOp(name = "ASA Black Box TeleOp (example)", group = "ASA")
@Disabled
public class ExampleTeleOpBlackBox extends LinearOpMode {

    static final double INCHES_PER_TICK = 0.0177;

    double target, liftInches;

    @Override
    public void runOpMode() {
        DcMotorEx lift = hardwareMap.get(DcMotorEx.class, "lift");
        lift.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);

        BlackBox bb = BlackBox.create("teleop");
        FtcBlackBox.attachAll(bb, hardwareMap);                  // every motor, servo, battery + standard rules
        bb.channel("lift").signal("target", () -> target).signal("inches", () -> liftInches);
        bb.notReachingRule("lift-slow", "lift.target", "lift.inches", 1.0, 1.5);
        AsaWeb.start();                                           // http://192.168.43.1:8099/
        AsaWeb.publish(bb);

        waitForStart();
        bb.start();
        bb.event("TELEOP_START");
        try {
            while (opModeIsActive()) {
                liftInches = lift.getCurrentPosition() * INCHES_PER_TICK;
                if (gamepad1.dpad_up) { target = 25; bb.event("LIFT_UP"); }
                if (gamepad1.dpad_down) { target = 0; bb.event("LIFT_DOWN"); }
                lift.setPower(Math.max(-1, Math.min(1, 0.15 * (target - liftInches))));

                bb.update();
                FtcBlackBox.show(telemetry, bb);
                telemetry.update();
            }
        } finally {
            for (String line : bb.stop()) telemetry.addLine(line);   // summary + comparison with past sessions
            telemetry.update();
            AsaWeb.publish(null);
        }
    }
}
