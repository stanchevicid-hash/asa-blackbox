// ASA Black Box library file. "Remove ASA" in the ASA Black Box program deletes it; keep your own code in other files.
package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.VoltageSensor;


/** FTC SDK glue shared by Black Box and SysId. */
public final class AsaFtc {

    private AsaFtc() {}

    /** Lowest hub battery voltage, re-read at most every 50 ms (a voltage read costs ~2 ms). */
    public static AsaRecorder.Source battery(final HardwareMap hw) {
        return new AsaRecorder.Source() {
            long lastNs; double cached = Double.NaN;
            public double get() {
                long now = System.nanoTime();
                if (Double.isNaN(cached) || now - lastNs > 50_000_000L) {
                    double min = Double.POSITIVE_INFINITY;
                    for (VoltageSensor s : hw.voltageSensor) {
                        double v = s.getVoltage();
                        if (v > 0 && v < min) min = v;
                    }
                    cached = min == Double.POSITIVE_INFINITY ? Double.NaN : min;
                    lastNs = now;
                }
                return cached;
            }
        };
    }
}
