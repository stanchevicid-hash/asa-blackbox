import org.asa.tools.blackbox.AsaRemover;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

/**
 * "Remove ASA" must delete ASA Black Box and nothing else: never a team's file, never a file ASA SysId still needs,
 * and nothing at all while the team's own code uses Black Box.
 */
public class RemoverTest {
    static int failures;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    /** The OnBot bundle's 17 files, as onbot.py names them. */
    static final String[] LIB = {"AsaAutoRecorder", "AsaBlackBoxOpMode", "AsaCenterOfGravity", "AsaHealthCheck", "AsaRemover",
            "AsaBatteryHealth", "BlackBox", "AsaCenterOfGravityMath", "FtcBlackBox", "AsaRule", "AsaWeb", "AsaWebPage", "AsaDashboard",
            "AsaFtc", "AsaLive", "AsaRecorder", "AsaWpiLog"};

    static File write(File dir, String name, String text) throws Exception {
        dir.mkdirs();
        File f = new File(dir, name);
        FileWriter w = new FileWriter(f);
        w.write(text);
        w.close();
        return f;
    }

    static File onbot(File src) throws Exception {
        File team = new File(src, "org/firstinspires/ftc/teamcode");
        for (String n : LIB) write(team, n + ".java", AsaRemover.MARK + ". Remove ASA deletes it.\npackage org.firstinspires.ftc.teamcode;\nclass " + n + " {}\n");
        return team;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("ONBOT: ours deleted, the team's files kept");
        File src = Files.createTempDirectory("rm1").toFile();
        File team = onbot(src);
        // the team's own files, some with the same names as ours
        File teleop = write(team, "TeleOpMain.java", "class TeleOpMain { void loop() { drive(); } }");
        File noted = write(team, "Auto.java", "// we tried BlackBox once\n/* FtcBlackBox.attachAll */\nclass Auto {}");
        // the team's own Rule.java and Recorder.java: the bundle no longer even has these names
        File ownRule = write(team, "Rule.java", "package org.firstinspires.ftc.teamcode;\nclass Rule { int points; }");
        File ownRec = write(team, "Recorder.java", "package org.firstinspires.ftc.teamcode;\nclass Recorder { void save() {} }");
        AsaRemover.Plan p = AsaRemover.plan(src);
        check(p.usedBy.isEmpty(), "a comment that mentions Black Box does not count as using it");
        check(p.delete.size() == LIB.length, "deletes our " + LIB.length + " marked files (planned " + p.delete.size() + ")");
        check(!p.delete.contains(ownRule) && !p.unmarked.contains(ownRule) && !p.delete.contains(ownRec), "the team's own Rule.java and Recorder.java are not touched");
        int n = AsaRemover.apply(p, src);
        check(n == LIB.length, "deleted " + n);
        check(teleop.isFile() && noted.isFile() && ownRule.isFile() && ownRec.isFile(), "the team's files are all still there");

        System.out.println("TEAM CODE USES BLACK BOX: nothing deleted");
        src = Files.createTempDirectory("rm2").toFile();
        team = onbot(src);
        File uses = write(team, "TeleOpMain.java", "class TeleOpMain { BlackBox bb = BlackBox.create(\"t\"); }");
        p = AsaRemover.plan(src);
        check(p.usedBy.contains(uses), "TeleOpMain.java is named as using Black Box");
        check(AsaRemover.apply(p, src) == 0 && new File(team, "BlackBox.java").isFile(), "no file deleted while it is used");

        System.out.println("ASA SYSID INSTALLED: shared files stay");
        src = Files.createTempDirectory("rm3").toFile();
        team = onbot(src);
        write(team, "AsaSysIdOpMode.java", "abstract class AsaSysIdOpMode { AsaWeb w; Recorder r; }");
        p = AsaRemover.plan(src);
        check(p.keepShared && p.usedBy.isEmpty(), "SysId detected, its use of AsaWeb / Recorder does not block");
        AsaRemover.apply(p, src);
        check(!new File(team, "BlackBox.java").exists() && new File(team, "AsaRecorder.java").isFile()
                && new File(team, "AsaWeb.java").isFile() && new File(team, "AsaSysIdOpMode.java").isFile(), "Black Box gone, SysId and the shared files kept");

        System.out.println("PACKAGE LAYOUT (org/asa/tools/...)");
        src = Files.createTempDirectory("rm4").toFile();
        File pkg = new File(src, "org/asa/tools");
        write(new File(pkg, "blackbox"), "BlackBox.java", "package org.asa.tools.blackbox; public class BlackBox {}");
        write(new File(pkg, "blackbox"), "Rule.java", "package org.asa.tools.blackbox; public class Rule {}");
        write(new File(pkg, "ftc"), "FtcBlackBox.java", "package org.asa.tools.ftc; public class FtcBlackBox { BlackBox b; }");
        write(new File(pkg, "core"), "Recorder.java", "package org.asa.tools.core; public class Recorder {}");
        File mine = write(new File(src, "org/firstinspires/ftc/teamcode"), "Drive.java", "class Drive {}");
        p = AsaRemover.plan(src);
        check(p.usedBy.isEmpty(), "our own files referencing each other do not count as the team's use");
        AsaRemover.apply(p, src);
        check(!pkg.exists() && !new File(src, "org/asa").exists(), "org/asa removed completely");
        check(mine.isFile(), "the team's Drive.java kept");

        System.out.println("OLD VERSION WITHOUT THE MARK: shown, never deleted");
        src = Files.createTempDirectory("rm5").toFile();
        File old = write(new File(src, "org/firstinspires/ftc/teamcode"), "BlackBox.java", "package org.firstinspires.ftc.teamcode;\n/** ASA Black Box */\npublic class BlackBox {}");
        p = AsaRemover.plan(src);
        check(p.unmarked.contains(old) && p.delete.isEmpty(), "listed for the team to check");
        AsaRemover.apply(p, src);
        check(old.isFile(), "not deleted");

        System.out.println("ANDROID STUDIO (nothing in OnBot)");
        p = AsaRemover.plan(Files.createTempDirectory("rm6").toFile());
        check(p.nothingInOnBot(), "nothing to delete is reported as such");

        System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
