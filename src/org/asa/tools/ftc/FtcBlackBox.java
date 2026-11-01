package org.asa.tools.ftc;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.Servo;

import org.asa.tools.blackbox.BlackBox;
import org.asa.tools.core.Dashboard;
import org.asa.tools.core.Recorder;
import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;

import java.util.List;

/** Black Box on an FTC robot: record the whole hardware map, show warnings on the Driver Hub. */
public final class FtcBlackBox {

    private FtcBlackBox() {}

    /**
     * Records every motor (power, position, velocity, current) and servo position in the hardware map,
     * the battery, and adds standard rules: stall, encoder lost, brownout, slow loop.
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
        bb.brownoutRule("battery", 8.0).slowLoopRule(50);
        return bb;
    }

    public static BlackBox attachAll(BlackBox bb, HardwareMap hw) { return attachAll(bb, hw, 5.0); }

    /** Black Box status + newest warnings on the Driver Hub (and FTC Dashboard graphs when installed). */
    public static void show(Telemetry t, BlackBox bb) {
        List<String> w = bb.warnings();
        String err = bb.rec.writeError();
        if (err != null) t.addLine("BlackBox NOT SAVING: " + err);
        t.addData("BlackBox", "%d warnings  loop %.1f ms%s", w.size(), bb.loopMs(), AsaWeb.url().isEmpty() ? "" : "  " + AsaWeb.url());
        for (int i = 0; i < Math.min(3, w.size()); i++) t.addLine(w.get(i));
        // live graphs in FTC Dashboard too, if the team has it (no-op otherwise)
        Dashboard.send(bb.names(), bb.values(), w.size() > 3 ? w.subList(0, 3) : w, 50);
    }

    private static String name(HardwareMap hw, Object device) {
        for (String n : hw.getNamesOf((HardwareDevice) device)) return n;
        return null;
    }
}
