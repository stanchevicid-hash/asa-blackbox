package org.asa.tools.blackbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Center of gravity from balance angles. Tip the robot onto one set of wheels until it balances by itself: the
 * center of gravity is then right above the wheels' ground contact, so tan(angle) = horizontal distance / height.
 * Balancing both ways along an axis gives two equations for the two unknowns (where along the axis, how high).
 * Doing it front/back and left/right gives the height twice, independently, which checks the measurement.
 *
 * All lengths in the unit you give the wheelbase and track in (cm on the Driver Hub); angles in degrees.
 */
public class CenterOfGravity {

    public double wheelbase, track;                          // ground contact distance front-back, left-right
    public double fromRear, fromRight, height;               // results
    public double heightFrontBack, heightLeftRight;          // the two independent heights
    public final List<String> notes = new ArrayList<String>();

    /**
     * @param rearDeg  balance angle on the rear wheels (front lifted)
     * @param frontDeg balance angle on the front wheels (rear lifted)
     * @param rightDeg balance angle on the right wheels (left side lifted)
     * @param leftDeg  balance angle on the left wheels (right side lifted)
     */
    public static CenterOfGravity compute(double wheelbase, double track, double rearDeg, double frontDeg, double rightDeg, double leftDeg) {
        CenterOfGravity c = new CenterOfGravity();
        c.wheelbase = wheelbase;
        c.track = track;
        double tr = Math.tan(Math.toRadians(rearDeg)), tf = Math.tan(Math.toRadians(frontDeg));
        double tR = Math.tan(Math.toRadians(rightDeg)), tL = Math.tan(Math.toRadians(leftDeg));
        c.heightFrontBack = wheelbase / (tr + tf);
        c.fromRear = c.heightFrontBack * tr;
        c.heightLeftRight = track / (tR + tL);
        c.fromRight = c.heightLeftRight * tR;
        c.height = (c.heightFrontBack + c.heightLeftRight) / 2;
        double spread = Math.abs(c.heightFrontBack - c.heightLeftRight) / c.height;
        if (spread > 0.10)   // hand-held balance is good to ~1 deg, which moves the two heights by a few % at most
            c.notes.add(String.format(Locale.US, "the two heights differ by %.0f%%: redo the balance tests more carefully", spread * 100));
        return c;
    }

    /** Hardest braking (m/s^2) before the robot tips onto its nose. */
    public double maxBrake() { return 9.81 * (wheelbase - fromRear) / height; }
    /** Hardest acceleration before a wheelie. */
    public double maxAccel() { return 9.81 * fromRear / height; }
    /** Hardest sideways acceleration (strafe stop, hit) before tipping sideways. */
    public double maxSideways() { return 9.81 * Math.min(fromRight, track - fromRight) / height; }
    /** Steepest ramp (degrees) the robot can stand on without tipping, in its weakest direction. */
    public double maxRampDeg() {
        double edge = Math.min(Math.min(fromRear, wheelbase - fromRear), Math.min(fromRight, track - fromRight));
        return Math.toDegrees(Math.atan(edge / height));
    }

    public List<String> lines() {
        List<String> l = new ArrayList<String>();
        l.add(String.format(Locale.US, "height %.1f  (front/back %.1f, left/right %.1f)", height, heightFrontBack, heightLeftRight));
        l.add(String.format(Locale.US, "from rear wheels %.1f of %.1f (%.0f%%)", fromRear, wheelbase, 100 * fromRear / wheelbase));
        l.add(String.format(Locale.US, "from right wheels %.1f of %.1f (%.0f%%)", fromRight, track, 100 * fromRight / track));
        l.add(String.format(Locale.US, "tips at: brake %.1f m/s2, accel %.1f, sideways %.1f, ramp %.0f deg", maxBrake(), maxAccel(), maxSideways(), maxRampDeg()));
        l.addAll(notes);
        return l;
    }

    /**
     * Angle in degrees between two "up" directions given as FTC yaw/pitch/roll readings. Only the angle between the
     * two is used, so it does not matter how the hub is mounted or which orientation the IMU was told.
     */
    public static double tiltBetween(double pitch0Deg, double roll0Deg, double pitchDeg, double rollDeg) {
        double[] a = up(pitch0Deg, roll0Deg), b = up(pitchDeg, rollDeg);
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }

    /** World "up" seen in the robot frame, for FTC's yaw (Z), pitch (X), roll (Y) order; yaw does not change it. */
    static double[] up(double pitchDeg, double rollDeg) {
        double p = Math.toRadians(pitchDeg), r = Math.toRadians(rollDeg);
        return new double[]{-Math.sin(r) * Math.cos(p), Math.sin(p), Math.cos(p) * Math.cos(r)};
    }
}
