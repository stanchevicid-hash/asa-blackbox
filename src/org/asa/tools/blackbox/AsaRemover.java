package org.asa.tools.blackbox;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes ASA Black Box from a robot, and nothing else. Used by "Remove ASA" in the ASA Black Box program.
 *
 * Only files that are certainly ours are deleted:
 * - in OnBot Java's teamcode folder, a file with one of our names AND our mark on its first line (a team may well have
 *   its own Recorder.java or Rule.java: without the mark it is never touched);
 * - in the org/asa/tools folders (the "keep the packages" install), our files by name.
 * Files shared with ASA SysId stay when SysId is installed. Nothing at all is deleted while the team's own code uses
 * Black Box (their build would break) or while files with our names but without the mark are present (an older ASA
 * version, or the team's own file: only the team can tell): they are told which files to look at first.
 */
public final class AsaRemover {

    /** First line of every library file in the OnBot bundle (written by onbot.py). */
    public static final String MARK = "// ASA Black Box library file";

    /** Black Box only (with the names the OnBot bundle gives them, "Asa..." in front of ordinary names). */
    static final List<String> OWN = Arrays.asList("AsaAutoRecorder", "AsaBlackBoxOpMode", "AsaCenterOfGravity", "AsaHealthCheck",
            "AsaRemover", "BatteryHealth", "BlackBox", "CenterOfGravity", "FtcBlackBox", "Rule",
            "AsaBatteryHealth", "AsaCenterOfGravityMath", "AsaRule");
    /** Also used by ASA SysId. */
    static final List<String> SHARED = Arrays.asList("AsaWeb", "AsaWebPage", "Dashboard", "Ftc", "Live", "Recorder", "WpiLog",
            "AsaDashboard", "AsaFtc", "AsaLive", "AsaRecorder", "AsaWpiLog");

    /** What a removal would do. Nothing is deleted until apply(). */
    public static final class Plan {
        /** Our files, to delete. */
        public final List<File> delete = new ArrayList<File>();
        /** The team's files that use Black Box: while any is left, nothing is deleted. */
        public final List<File> usedBy = new ArrayList<File>();
        /** Files with our names but without the mark that look like ASA (older version?): the team checks them first. */
        public final List<File> unmarked = new ArrayList<File>();
        /** ASA SysId is installed: the shared files stay. */
        public boolean keepShared;
        /** No library file found in OnBot Java (installed with Android Studio, or already removed). */
        public boolean nothingInOnBot() { return delete.isEmpty() && unmarked.isEmpty(); }
        /** Safe to delete: no team file uses Black Box and no unmarked look-alike is left to check. */
        public boolean canRemove() { return usedBy.isEmpty() && unmarked.isEmpty(); }
    }

    /** @param javaSrc OnBot Java's source folder: /sdcard/FIRST/java/src on the robot */
    public static Plan plan(File javaSrc) {
        Plan p = new Plan();
        File team = new File(javaSrc, "org/firstinspires/ftc/teamcode"), pkg = new File(javaSrc, "org/asa/tools");
        p.keepShared = new File(team, "AsaSysIdOpMode.java").isFile() || new File(team, "SysIdAnalyzer.java").isFile()
                || hasJava(new File(pkg, "sysid"));
        List<String> names = new ArrayList<String>(OWN);
        if (!p.keepShared) names.addAll(SHARED);

        for (String n : names) {
            File f = new File(team, n + ".java");
            if (!f.isFile()) continue;
            String head = read(f, 400);
            if (head.startsWith(MARK)) p.delete.add(f);
            else if (LOOKS_ASA.matcher(head).find()) p.unmarked.add(f);
        }
        for (String sub : new String[]{"blackbox", "ftc", "core"}) {
            File[] fs = new File(pkg, sub).listFiles();
            if (fs != null) for (File f : fs)
                if (f.getName().endsWith(".java") && names.contains(f.getName().substring(0, f.getName().length() - 5))) p.delete.add(f);
        }
        File[] ex = new File(pkg, "examples").listFiles();   // the Black Box example (SysId's examples stay with SysId)
        if (ex != null) for (File f : ex) if (f.getName().endsWith(".java") && f.getName().contains("BlackBox")) p.delete.add(f);

        // the team's own code: any file outside our folders that names a Black Box type in code (not in a comment or a
        // string). The bundle's renamed public types count too (BlackBox.rule() returns an AsaRule...), and the
        // shared ones when they are about to go.
        Pattern uses = Pattern.compile("\\b(FtcBlackBox|BlackBox|AsaAutoRecorder|AsaBlackBoxOpMode|AsaHealthCheck|AsaCenterOfGravity"
                + "|AsaRemover|AsaRule|AsaBatteryHealth|AsaCenterOfGravityMath"
                + (p.keepShared ? "" : "|AsaWeb|AsaWebPage|AsaRecorder|AsaLive|AsaFtc|AsaDashboard|AsaWpiLog") + ")\\b"
                + "|\\borg\\.asa\\.tools\\.blackbox\\b" + (p.keepShared ? "" : "|\\borg\\.asa\\.tools\\.(core|ftc)\\b"));
        List<File> all = new ArrayList<File>();
        collect(javaSrc, pkg, all);
        for (File f : all) {
            if (p.delete.contains(f) || p.unmarked.contains(f)) continue;
            String text = read(f, Integer.MAX_VALUE);
            if (text.startsWith(MARK)) {   // ours, wherever it was uploaded; shared files stay while SysId needs them
                String stem = f.getName().substring(0, f.getName().length() - 5);
                if (names.contains(stem)) p.delete.add(f);
                continue;
            }
            if (uses.matcher(codeOnly(text)).find()) p.usedBy.add(f);
        }
        return p;
    }

    /** "ASA" as a word (not inside NASA or BASALT), or our type names: a file with our name that may be ours. */
    private static final Pattern LOOKS_ASA = Pattern.compile("\\bASA\\b|BlackBox|Black Box|Recorder\\.Source");

    /** Code without string and char literals and comments ("http://..." in a string must not hide what follows). */
    static String codeOnly(String java) {
        return java.replaceAll("(?s)\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])'|//[^\\n]*|/\\*.*?\\*/", " ");
    }

    /** Deletes the planned files, and our package folders when they are empty. Returns how many files were deleted. */
    public static int apply(Plan p, File javaSrc) {
        if (!p.canRemove()) return 0;
        int n = 0;
        for (File f : p.delete) if (f.delete()) n++;
        File pkg = new File(javaSrc, "org/asa/tools");
        for (String sub : new String[]{"blackbox", "ftc", "core", "examples", ""}) {
            File d = sub.isEmpty() ? pkg : new File(pkg, sub);
            String[] left = d.list();
            if (left != null && left.length == 0) d.delete();
        }
        File asa = new File(javaSrc, "org/asa");
        String[] left = asa.list();
        if (left != null && left.length == 0) asa.delete();
        return n;
    }

    private static boolean hasJava(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".java")) return true;
        return false;
    }

    private static void collect(File dir, File skip, List<File> out) {
        if (dir.equals(skip)) return;   // our package folders are ours: never "the team's code"
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, skip, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    private static String read(File f, int max) {
        StringBuilder s = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            try {
                for (String line; s.length() < max && (line = r.readLine()) != null; ) s.append(line).append('\n');
            } finally { r.close(); }
        } catch (Exception ignored) {}
        return s.toString();
    }
}
