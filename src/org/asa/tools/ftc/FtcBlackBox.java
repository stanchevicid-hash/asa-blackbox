package org.asa.tools.ftc;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.AnalogInput;
import com.qualcomm.robotcore.hardware.DistanceSensor;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.TouchSensor;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.Servo;

import org.asa.tools.blackbox.BlackBox;
import org.asa.tools.core.Dashboard;
import org.asa.tools.core.Recorder;
import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.TempUnit;
import org.firstinspires.ftc.robotcore.external.navigation.VoltageUnit;

import com.qualcomm.robotcore.hardware.DcMotorController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Black Box on an FTC robot: record the whole hardware map, show warnings on the Driver Hub. */
public final class FtcBlackBox {

    private FtcBlackBox() {}

    /**
     * Records every motor (power, position, velocity, current), servo position, IMU, distance sensor, analog input
     * and touch sensor in the hardware map, the battery and the hubs, and adds the standard rules: stall, encoder
     * lost, motor overheating, drivetrain wheel dragging, brownout, low battery at start, slow loop, loop pause,
     * frozen sensor, hub reset, hub not responding.
     * Turns on AUTO bulk caching on hubs where it is OFF, so encoder reads cost one bulk read per loop.
     * Motor current is not in the bulk read (~2 ms each), so it is sampled every 100 ms.
     *
     * @param stallAmps current above which a motor that does not move counts as stalled (5 A suits most FTC motors)
     */
    public static BlackBox attachAll(BlackBox bb, HardwareMap hw, double stallAmps) {
        for (LynxModule hub : hw.getAll(LynxModule.class))
            if (hub.getBulkCachingMode() == LynxModule.BulkCachingMode.OFF) hub.setBulkCachingMode(LynxModule.BulkCachingMode.AUTO);

        bb.signal("battery", Ftc.battery(hw), 100);
        for (final DcMotor m : hw.getAll(DcMotor.class)) {
            String n = name(hw, m);
            if (n == null || bb.rec.has(n + ".power")) continue;
            BlackBox.Channel c = bb.channel(n);
            c.signal("power", new Recorder.Source() { public double get() { return m.getPower(); } });
            c.signal("pos", new Recorder.Source() { public double get() { return m.getCurrentPosition(); } });
            if (m instanceof DcMotorEx) {
                final DcMotorEx e = (DcMotorEx) m;
                c.signal("vel", new Recorder.Source() { public double get() { return e.getVelocity(); } });
                c.signal("amps", new Recorder.Source() { public double get() { return e.getCurrent(CurrentUnit.AMPS); } }, 100);
                bb.motorRules(n, stallAmps, 20);
            }
        }
        for (final Servo s : hw.getAll(Servo.class)) {
            String n = name(hw, s);
            if (n != null && !bb.rec.has("servo." + n)) bb.signal("servo." + n, new Recorder.Source() { public double get() { return s.getPosition(); } });
        }
        List<String> powers = new ArrayList<String>(), drive = new ArrayList<String>();
        for (String n : bb.rec.names()) if (n.endsWith(".power")) powers.add(n);
        for (String n : powers) {
            String m = n.substring(0, n.length() - 6);
            if (bb.rec.has(m + ".amps")) bb.thermalRule(m, 5.0, 120);
            if (isDriveMotor(m)) drive.add(m);
        }
        if (drive.size() == 2 || drive.size() == 4) bb.imbalanceRule(drive.toArray(new String[0]));
        bb.brownoutRule("battery", 8.0).lowBatteryAtStartRule(idleBattery(hw), 12.4)   // INIT: robot idle
          .slowLoopRule(50).pauseRule(150);
        attachSensors(bb, hw, powers.toArray(new String[0]));
        attachHubs(bb, hw);
        return bb;
    }

    private static double idleBattery(HardwareMap hw) { try { return Ftc.battery(hw).get(); } catch (Exception e) { return Double.NaN; } }

    /** "frontLeft", "left_front_drive", "lf", "FL motor", "backRight"...: a drivetrain wheel by its name. */
    public static boolean isDriveMotor(String name) {
        String n = name.toLowerCase(java.util.Locale.US).replaceAll("motor|drive|wheel|[^a-z]", "");
        if ((n.contains("left") || n.contains("right")) && (n.contains("front") || n.contains("back") || n.contains("rear"))) return true;
        return n.matches("[lr][fbr]|[fbr][lr]") || n.equals("left") || n.equals("right");
    }

    /**
     * IMU heading, distance sensors, analog inputs and touch sensors. A hung IMU keeps returning its last heading:
     * the frozen rule reports it if the heading stops changing (not even noise) while the motors are driven.
     */
    static void attachSensors(BlackBox bb, HardwareMap hw, String[] motorPowers) {
        for (final IMU imu : hw.getAll(IMU.class)) {
            String n = name(hw, imu);
            if (n == null || bb.rec.has(n + ".yaw")) continue;
            bb.signal(n + ".yaw", new Recorder.Source() { public double get() { return imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES); } }, 100);
            bb.frozenRule(n + ".yaw", 5.0, motorPowers);       // 5 s: a robot pinned against a wall can hold a heading for a moment
        }
        for (final DistanceSensor d : hw.getAll(DistanceSensor.class)) {
            String n = name(hw, d);
            if (n == null || bb.rec.has(n + ".mm")) continue;
            bb.signal(n + ".mm", new Recorder.Source() { public double get() {
                double mm = d.getDistance(DistanceUnit.MM);
                return mm > 0 && mm < 2000 ? mm : Double.NaN;           // out of range: not a reading
            }}, 200);
            // ponytail: no frozen rule here; a 1 mm-resolution sensor watching a part inside the robot legitimately
            // reads the same value while driving. Add bb.frozenRule(n + ".mm", ...) yourself for one aimed at the field.
        }
        for (final AnalogInput a : hw.getAll(AnalogInput.class)) {
            String n = name(hw, a);
            if (n != null && !bb.rec.has(n + ".volts")) bb.signal(n + ".volts", new Recorder.Source() { public double get() { return a.getVoltage(); } }, 100);
        }
        for (final TouchSensor t : hw.getAll(TouchSensor.class)) {
            String n = name(hw, t);
            if (n != null && !bb.rec.has(n + ".pressed")) bb.signal(n + ".pressed", new Recorder.Source() { public double get() { return t.isPressed() ? 1 : 0; } });
        }
    }

    /**
     * The drivers' sticks and triggers on the same timeline as the robot (was it the driver or the robot?), and a
     * CRITICAL warning when the Driver Station drops a gamepad. Call with the OpMode's gamepad1, gamepad2 before start().
     */
    public static BlackBox attachGamepads(BlackBox bb, Gamepad... pads) {
        for (int i = 0; i < pads.length; i++) {
            final Gamepad g = pads[i];
            String p = "gamepad" + (i + 1);
            if (g == null || bb.rec.has(p + ".ok")) continue;
            bb.signal(p + ".ok", new Recorder.Source() { public double get() { return g.id != Gamepad.ID_UNASSOCIATED ? 1 : 0; } });
            bb.signal(p + ".ly", new Recorder.Source() { public double get() { return g.left_stick_y; } });
            bb.signal(p + ".lx", new Recorder.Source() { public double get() { return g.left_stick_x; } });
            bb.signal(p + ".rx", new Recorder.Source() { public double get() { return g.right_stick_x; } });
            bb.signal(p + ".lt", new Recorder.Source() { public double get() { return g.left_trigger; } });
            bb.signal(p + ".rt", new Recorder.Source() { public double get() { return g.right_trigger; } });
            bb.disconnectRule(p, p + ".ok");
        }
        return bb;
    }

    /**
     * Hub health: answering or not (a local flag, free), temperature (1 read/s), hub resets (all encoders of a hub
     * back to 0 at once), and battery health from the total current and the Control Hub voltage read together
     * every 150 ms (one read per hub plus one: ~2 ms each).
     */
    static void attachHubs(final BlackBox bb, HardwareMap hw) {
        final List<LynxModule> hubs = hw.getAll(LynxModule.class);
        if (hubs.isEmpty()) return;
        LynxModule parent = hubs.get(0);
        for (LynxModule h : hubs) if (h.isParent()) parent = h;
        final LynxModule main = parent;

        for (final LynxModule hub : hubs) {
            String n = hubName(hw, hub);
            if (bb.rec.has(n + ".ok")) continue;
            bb.signal(n + ".ok", new Recorder.Source() { public double get() { return hub.isNotResponding() ? 0 : 1; } });
            bb.signal(n + ".temp", new Recorder.Source() { public double get() { return hub.getTemperature(TempUnit.CELSIUS); } }, 1000);
            bb.hubLostRule(n);
        }

        if (!bb.rec.has("battery.amps"))
            bb.signal("battery.amps", new Recorder.Source() { public double get() {
                double amps = 0;
                for (LynxModule h : hubs) amps += h.getCurrent(CurrentUnit.AMPS);
                double volts = main.getInputVoltage(VoltageUnit.VOLTS);       // right after: the same instant
                bb.batteryHealth().feed(bb.rec.time(), volts, amps);
                return amps;
            }}, 150);

        // group encoders by the hub that reads them: a hub reset zeroes all of its encoders at once
        Map<DcMotorController, List<String>> byHub = new LinkedHashMap<DcMotorController, List<String>>();
        Map<DcMotorController, String> firstMotor = new HashMap<DcMotorController, String>();
        for (DcMotor m : hw.getAll(DcMotor.class)) {
            String n = name(hw, m);
            if (n == null || !bb.rec.has(n + ".pos")) continue;
            DcMotorController c = m.getController();
            if (!byHub.containsKey(c)) { byHub.put(c, new ArrayList<String>()); firstMotor.put(c, n); }
            if (!byHub.get(c).contains(n + ".pos")) byHub.get(c).add(n + ".pos");
        }
        for (Map.Entry<DcMotorController, List<String>> e : byHub.entrySet())
            bb.hubResetRule("hub-of-" + firstMotor.get(e.getKey()), e.getValue().toArray(new String[0]));
    }

    private static String hubName(HardwareMap hw, LynxModule hub) {
        String n = name(hw, hub);
        return Recorder.safe(n == null ? "hub" + hub.getModuleAddress() : n);
    }

    public static BlackBox attachAll(BlackBox bb, HardwareMap hw) { return attachAll(bb, hw, 5.0); }

    /** Black Box status + newest warnings on the Driver Hub (and FTC Dashboard graphs when installed). */
    public static void show(Telemetry t, BlackBox bb) {
        List<String> w = bb.warnings();
        String err = bb.rec.writeError();
        if (err != null) t.addLine("BlackBox NOT SAVING: " + err);
        t.addData("BlackBox", "%d warnings  loop %.1f ms", w.size(), bb.loopMs());
        for (int i = 0; i < Math.min(3, w.size()); i++) t.addLine(w.get(i));
        String howTo = resultsHint();
        if (!howTo.isEmpty()) t.addLine(howTo);
        // live graphs in FTC Dashboard too, if the team has it (no-op otherwise)
        Dashboard.send(bb.names(), bb.values(), w.size() > 3 ? w.subList(0, 3) : w, 50);
    }

    /** One line telling the driver where the results are, or "" when the web page is not running. */
    public static String resultsHint() {
        String url = AsaWeb.url();
        return url.isEmpty() ? "" : "Results: connect a phone/laptop to the robot Wi-Fi, open " + url;
    }

    private static String name(HardwareMap hw, Object device) {
        for (String n : hw.getNamesOf((HardwareDevice) device)) return n;
        return null;
    }
}
