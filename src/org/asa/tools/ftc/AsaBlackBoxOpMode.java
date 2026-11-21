package org.asa.tools.ftc;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

import org.asa.tools.core.Recorder;
import org.firstinspires.ftc.robotcore.external.Telemetry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * The one ASA Black Box program on the Driver Hub. No code to write.
 *
 * INIT: LB / RB = mode (Health check, Center of gravity, Recording test), X = language (EN, RU, RO), Y = automatic
 * recording on / off, then the mode's own settings. The screen also says whether the automatic recording is hooked in,
 * the newest recording and whether the results page runs.
 * START runs the chosen mode. The results page starts by itself: http://192.168.43.1:8099/ on the robot Wi-Fi.
 */
@TeleOp(name = "ASA Black Box", group = "ASA")
public class AsaBlackBoxOpMode extends LinearOpMode {

    static final String[] LANGS = {"EN", "RU", "RO"};
    /** Language of the Driver Hub text: 0 EN, 1 RU, 2 RO. Also read by the web page (lang.txt). */
    static int lang = Math.max(0, java.util.Arrays.asList(LANGS).indexOf(read(new File(Recorder.defaultDir(), "lang.txt")).toUpperCase()));

    /** The same text in English, Russian and Romanian; picks the chosen language. */
    static String tr(String en, String ru, String ro) { return lang == 1 ? ru : lang == 2 ? ro : en; }

    private final boolean[] last = new boolean[4];

    @Override
    public void runOpMode() {
        File dir = Recorder.defaultDir();
        File langFile = new File(dir, "lang.txt"), modeFile = new File(dir, "mode.txt");
        lang = Math.max(0, java.util.Arrays.asList(LANGS).indexOf(read(langFile).toUpperCase()));
        String saved = read(modeFile);
        int mode = "cog".equals(saved) ? 1 : "test".equals(saved) ? 2 : 0;
        AsaWeb web = AsaWeb.start();
        boolean atBoot = AsaAutoRecorder.installed();   // hooked in when the robot app started (not only now)
        AsaAutoRecorder.installFromOpMode();   // in case the robot was not restarted since the library was installed
        String lastRec = AsaAutoRecorder.lastRecording();
        boolean auto = AsaAutoRecorder.enabled();

        telemetry.setDisplayFormat(Telemetry.DisplayFormat.HTML);   // colors on the Driver Hub (as the SDK's own samples do)
        AsaHealthCheck health = new AsaHealthCheck(this);
        AsaCenterOfGravity cog = new AsaCenterOfGravity(this);
        while (!isStarted() && !isStopRequested()) {
            if (edge(0, gamepad1.left_bumper)) mode = (mode + 2) % 3;
            if (edge(1, gamepad1.right_bumper)) mode = (mode + 1) % 3;
            if (edge(2, gamepad1.x)) { lang = (lang + 1) % LANGS.length; write(langFile, LANGS[lang].toLowerCase()); }
            if (edge(3, gamepad1.y)) { auto = !auto; AsaAutoRecorder.setEnabled(auto); }
            String[] modes = {tr("Health check", "Проверка робота", "Verificare robot"), tr("Center of gravity", "Центр тяжести", "Centrul de greutate"),
                    tr("Recording test", "Проверка записи", "Test înregistrare")};
            StringBuilder menu = new StringBuilder();
            for (int i = 0; i < modes.length; i++) menu.append(i > 0 ? Html.gap() : "").append(i == mode ? Html.pick(modes[i]) : Html.dim(modes[i]));
            telemetry.addLine(Html.title("ASA Black Box"));
            telemetry.addLine(Html.dim(tr("Mode", "Режим", "Mod") + " (LB / RB)"));
            telemetry.addLine(menu.toString());
            telemetry.addLine(Html.dim(tr("Language (X): ", "Язык (X): ", "Limba (X): ")) + LANGS[lang] + Html.gap()
                    + Html.dim(tr("Record matches (Y): ", "Запись матчей (Y): ", "Înregistrare meciuri (Y): "))
                    + (auto ? Html.c(Html.GREEN, "● " + tr("ON", "ВКЛ", "PORNIT")) : Html.c(Html.GRAY, "○ " + tr("OFF", "ВЫКЛ", "OPRIT"))));
            // state of the robot's setup, so a team sees at once that everything is in place
            telemetry.addLine(!auto ? Html.dim("○ " + tr("Automatic recording is off (Y)", "Автозапись выключена (Y)", "Înregistrarea automată e oprită (Y)"))
                    : atBoot ? Html.lv('g', tr("Every match is recorded (hooked in at power-on)", "Каждый матч записывается (подключено при включении робота)", "Fiecare meci se înregistrează (conectat la pornire)"))
                    : Html.lv('y', tr("Recording hooked in only now: press Restart Robot once", "Запись подключилась только сейчас: один раз нажмите Restart Robot", "Înregistrarea s-a conectat abia acum: apăsați o dată Restart Robot")));
            telemetry.addLine(Html.dim(lastRec == null ? tr("No recordings yet", "Записей пока нет", "Încă nu sunt înregistrări")
                    : tr("Newest recording: ", "Последняя запись: ", "Ultima înregistrare: ") + lastRec));
            telemetry.addLine(web.error().isEmpty() ? Html.dim(FtcBlackBox.resultsHint()) : Html.lv('r', web.error()));
            telemetry.addLine("");
            if (mode == 0) health.initLoop();
            else if (mode == 1) cog.initLoop();
            else telemetry.addLine(Html.dim(tr("Checks that the automatic recording does not slow a program like yours and really records. No motor turns. About 16 s.",
                    "Проверяет, что автозапись не замедляет программу вроде вашей и действительно пишет. Моторы не крутятся. Около 16 с.",
                    "Verifică că înregistrarea automată nu încetinește un program ca al vostru și chiar înregistrează. Motoarele nu se rotesc. Cam 16 s.")));
            telemetry.update();
            idle();
        }
        write(modeFile, mode == 0 ? "health" : mode == 1 ? "cog" : "test");
        if (isStopRequested()) return;
        if (mode == 0) health.run(); else if (mode == 1) cog.run(); else AsaAutoRecorder.selfTest(this);
    }

    // ---------------------------------------------------------------- Driver Hub text in ASA's colors
    // Only <font color> and <b>: the tags the FTC SDK's own HTML-telemetry sample uses. Text is escaped.

    static class Html {
        static final String PINK = "#FF2D8A", GREEN = "#3DDC97", YELLOW = "#FFD23F", RED = "#FF6A3D", GRAY = "#A4A1AE";

        static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
        static String c(String color, String text) { return "<font color='" + color + "'>" + esc(text) + "</font>"; }
        static String title(String text) { return "<b>" + c(PINK, text) + "</b>"; }
        static String pick(String text) { return "<b>" + c(PINK, "▶ " + text) + "</b>"; }
        static String dim(String text) { return c(GRAY, text); }
        static String gap() { return "&nbsp;&nbsp;&nbsp;&nbsp;"; }
        /** A traffic-light line: 'g' green, 'y' yellow, 'r' red, anything else plain. */
        static String lv(char level, String text) {
            return level == 'g' ? c(GREEN, "● " + text) : level == 'y' ? c(YELLOW, "● " + text) : level == 'r' ? "<b>" + c(RED, "● " + text) + "</b>" : esc(text);
        }
    }

    static String read(File f) {
        try { BufferedReader r = new BufferedReader(new FileReader(f)); String s = r.readLine(); r.close(); return s == null ? "" : s.trim(); }
        catch (Exception e) { return ""; }
    }

    static void write(File f, String s) {
        try { f.getParentFile().mkdirs(); FileWriter w = new FileWriter(f); w.write(s + "\n"); w.close(); } catch (Exception ignored) {}
    }

    private boolean edge(int i, boolean now) {
        boolean r = now && !last[i];
        last[i] = now;
        return r;
    }
}
