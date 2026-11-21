// ASA Black Box library file. "Remove ASA" in the ASA Black Box program deletes it; keep your own code in other files.
package org.firstinspires.ftc.teamcode;

import java.util.List;

/** Something the web UI can show live: named values and warnings (Black Box implements it). */
public interface AsaLive {
    List<String> names();
    double[] values();
    List<String> warnings();
}
