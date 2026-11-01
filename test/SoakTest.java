import org.asa.tools.blackbox.BlackBox;
import org.asa.tools.core.WpiLog;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;

/**
 * Long session: 10 simulated minutes at 100 Hz with 40 signals and events, written ~10x faster than real time.
 * Every row must reach the file intact (no drops, 41 fields each, times increasing), events and summary must be
 * written, memory must stay flat, and the WPILOG export of the whole session must stream without running out.
 *
 *   java -cp out SoakTest
 */
public class SoakTest {
    public static void main(String[] args) throws Exception {
        int fail = 0;
        File dir = new File(System.getProperty("java.io.tmpdir"), "asa-soak-" + System.nanoTime());
        BlackBox bb = BlackBox.create("soak").dir(dir);
        final double[] x = {0};
        for (int i = 0; i < 40; i++) { final int k = i; bb.signal("s" + i, () -> Math.sin(x[0] * 0.01 + k) * 100); }
        for (int m = 0; m < 8; m++) bb.motorRules("s" + m, 5, 20);
        bb.start();

        Runtime rt = Runtime.getRuntime();
        int rows = 60_000, events = 0;
        long memMid = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < rows; i++) {
            x[0] = i;
            bb.update(i * 0.01);
            if (i % 600 == 0) { bb.event("TICK", "minute " + i / 6000); events++; }
            if (i % 10 == 0) Thread.sleep(1);                   // ~10k rows/s: 10x a real 100 Hz loop
            if (i == rows / 2) { System.gc(); memMid = rt.totalMemory() - rt.freeMemory(); }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        System.gc();
        long memEnd = rt.totalMemory() - rt.freeMemory();
        bb.stop();
        System.out.printf("wrote %d rows in %.1f s (%.0f rows/s), dropped %d, heap mid %d KB end %d KB%n",
                rows, secs, rows / secs, bb.rec.dropped(), memMid / 1024, memEnd / 1024);
        if (bb.rec.dropped() != 0) { System.out.println("FAIL rows dropped"); fail++; }
        if (memEnd > memMid + 8 * 1024 * 1024) { System.out.println("FAIL memory grows"); fail++; }

        File csv = new File(dir, bb.rec.session() + ".csv");
        BufferedReader r = new BufferedReader(new FileReader(csv));
        String header = r.readLine();
        int n = 0, badFields = 0, backwards = 0;
        double last = -1;
        for (String line; (line = r.readLine()) != null; n++) {
            String[] p = line.split(",", -1);
            if (p.length != 42) badFields++;                    // t + 40 signals + loop_ms
            double t = Double.parseDouble(p[0]);
            if (t < last) backwards++;
            last = t;
        }
        r.close();
        System.out.printf("csv: %d data rows, header %d columns, %d malformed, %d out of order%n", n, header.split(",").length, badFields, backwards);
        if (n != rows || badFields != 0 || backwards != 0) { System.out.println("FAIL csv integrity"); fail++; }

        int ev = 0;
        BufferedReader e = new BufferedReader(new FileReader(new File(dir, bb.rec.session() + ".events.csv")));
        e.readLine();
        while (e.readLine() != null) ev++;
        e.close();
        System.out.printf("events: %d lines (%d TICK + session start/end)%n", ev, events);
        if (ev != events + 2) { System.out.println("FAIL events"); fail++; }
        if (!new File(dir, bb.rec.session() + ".summary.csv").isFile() || !new File(dir, bb.rec.session() + ".report.txt").isFile()) {
            System.out.println("FAIL summary/report missing"); fail++;
        }

        // the web UI streams this export; count bytes without holding them
        final long[] bytes = {0};
        OutputStream counter = new OutputStream() { public void write(int b) { bytes[0]++; } public void write(byte[] b, int o, int l) { bytes[0] += l; } };
        long w0 = System.nanoTime();
        WpiLog.convert(csv, counter);
        System.out.printf("wpilog: %.1f MB in %.1f s, csv %.1f MB%n", bytes[0] / 1e6, (System.nanoTime() - w0) / 1e9, csv.length() / 1e6);
        if (bytes[0] < 60_000L * 41 * 17) { System.out.println("FAIL wpilog too small"); fail++; }

        System.out.println(fail == 0 ? "SOAK OK" : fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
