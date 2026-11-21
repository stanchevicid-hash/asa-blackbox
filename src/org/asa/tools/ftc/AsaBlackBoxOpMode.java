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
 * INIT: LB / RB = mode (Health check, Center of gravity, Recording test, Remove ASA), X = language (EN, RU, RO), Y = automatic
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
            if (edge(0, gamepad1.left_bumper)) mode = (mode + 3) % 4;
            if (edge(1, gamepad1.right_bumper)) mode = (mode + 1) % 4;
            if (edge(2, gamepad1.x)) { lang = (lang + 1) % LANGS.length; write(langFile, LANGS[lang].toLowerCase()); }
            if (edge(3, gamepad1.y)) { auto = !auto; AsaAutoRecorder.setEnabled(auto); }
            String[] modes = {tr("Health check", "Проверка робота", "Verificare robot"), tr("Center of gravity", "Центр тяжести", "Centrul de greutate"),
                    tr("Recording test", "Проверка записи", "Test înregistrare"), tr("Remove ASA", "Удалить ASA", "Șterge ASA")};
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
            else if (mode == 3) {
                telemetry.addLine(Html.lv('r', tr("Removes ASA Black Box from this robot: its recordings and settings (folder FIRST/asa) and its files in OnBot Java.",
                        "Удаляет ASA Black Box с этого робота: записи и настройки (папка FIRST/asa) и файлы библиотеки в OnBot Java.",
                        "Șterge ASA Black Box de pe acest robot: înregistrările și setările (folderul FIRST/asa) și fișierele bibliotecii din OnBot Java.")));
                telemetry.addLine(Html.dim(tr("Only files marked as ASA. Your code, your configuration and ASA SysId stay. After START: hold A and B for 3 s.",
                        "Только файлы с меткой ASA. Ваш код, конфигурация и ASA SysId остаются. После START: держите A и B 3 секунды.",
                        "Doar fișierele marcate ASA. Codul, configurația și ASA SysId rămân. După START: țineți A și B 3 secunde.")));
            }
            else telemetry.addLine(Html.dim(tr("Checks that the automatic recording does not slow a program like yours and really records. No motor turns. About 16 s.",
                    "Проверяет, что автозапись не замедляет программу вроде вашей и действительно пишет. Моторы не крутятся. Около 16 с.",
                    "Verifică că înregistrarea automată nu încetinește un program ca al vostru și chiar înregistrează. Motoarele nu se rotesc. Cam 16 s.")));
            telemetry.update();
            idle();
        }
        if (mode < 3) write(modeFile, mode == 0 ? "health" : mode == 1 ? "cog" : "test");   // deleting is never the default next time
        if (isStopRequested()) return;
        if (mode == 0) health.run(); else if (mode == 1) cog.run(); else if (mode == 2) AsaAutoRecorder.selfTest(this); else removeAsa();
    }

    /**
     * Remove ASA, after A and B are held together for 3 s: stops the recording and the page at once, deletes the folder
     * FIRST/asa and the library's own files in OnBot Java (AsaRemover decides which: only ours, never the team's).
     * If the team's code still uses Black Box, nothing is deleted and the files to change are listed.
     */
    private void removeAsa() {
        File javaSrc = new File("/sdcard/FIRST/java/src");
        org.asa.tools.blackbox.AsaRemover.Plan plan = org.asa.tools.blackbox.AsaRemover.plan(javaSrc);
        long heldSince = 0;
        java.util.List<String> result = null;
        while (opModeIsActive()) {
            if (result == null) {
                boolean both = gamepad1.a && gamepad1.b;
                if (!both) heldSince = 0; else if (heldSince == 0) heldSince = System.nanoTime();
                double held = heldSince == 0 ? 0 : (System.nanoTime() - heldSince) / 1e9;
                telemetry.addLine(Html.title(tr("Remove ASA", "Удалить ASA", "Șterge ASA")));
                if (!plan.usedBy.isEmpty()) {   // removing the library would break the team's build: say where it is used
                    telemetry.addLine(Html.lv('r', tr("Your code uses Black Box, so nothing is removed. First take it out of:",
                            "Ваш код использует Black Box, поэтому ничего не удаляется. Сначала уберите его из:",
                            "Codul vostru folosește Black Box, așa că nu se șterge nimic. Mai întâi scoateți-l din:")));
                    for (File f : plan.usedBy) telemetry.addLine("&nbsp;&nbsp;" + Html.esc(f.getName()));
                    telemetry.addLine(Html.dim(tr("then Build, and run Remove ASA again.", "потом Build и снова «Удалить ASA».", "apoi Build și din nou „Șterge ASA”.")));
                } else {
                    StringBuilder bar = new StringBuilder();
                    for (int i = 0; i < 10; i++) bar.append(i < held / 0.3 ? "■" : "□");
                    telemetry.addLine(Html.c(Html.RED, bar.toString()) + Html.gap() + Html.dim(tr("hold A + B", "держите A + B", "țineți A + B")));
                    telemetry.addLine(Html.dim(String.format(tr("%d library files, the folder FIRST/asa%s.", "%d файлов библиотеки, папка FIRST/asa%s.",
                            "%d fișiere ale bibliotecii, folderul FIRST/asa%s."), plan.delete.size(),
                            plan.keepShared ? tr(" (ASA SysId and its files stay)", " (ASA SysId и его файлы остаются)", " (ASA SysId și fișierele lui rămân)") : "")));
                    telemetry.addLine(Html.dim(tr("Release to cancel, or press STOP.", "Отпустите, чтобы отменить, или нажмите STOP.", "Eliberați ca să anulați, sau apăsați STOP.")));
                    if (held >= 3) result = doRemove(plan, javaSrc);
                }
            } else {
                for (String l : result) telemetry.addLine(l);
            }
            telemetry.update();
            idle();
        }
    }

    private java.util.List<String> doRemove(org.asa.tools.blackbox.AsaRemover.Plan plan, File javaSrc) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        AsaAutoRecorder.uninstall();   // nothing of ASA runs from now on, even before the restart
        AsaWeb.stop();
        File dir = Recorder.defaultDir();
        // only a folder named like ours, so a wrong path can never delete anything else
        int data = dir.getName().equals("asa") || dir.getName().equals("asa-logs") ? delete(dir) : 0;
        int code = org.asa.tools.blackbox.AsaRemover.apply(plan, javaSrc);
        out.add(Html.title(tr("ASA removed", "ASA удалена", "ASA ștearsă")));
        out.add(Html.lv('g', String.format(tr("Deleted: %d library files, %d recordings and settings.", "Удалено: %d файлов библиотеки, %d записей и настроек.",
                "Șterse: %d fișiere ale bibliotecii, %d înregistrări și setări."), code, data)));
        out.add(Html.lv('g', tr("Recording and the results page are stopped.", "Запись и страница результатов остановлены.", "Înregistrarea și pagina de rezultate sunt oprite.")));
        if (plan.nothingInOnBot())
            out.add(Html.lv('y', tr("No ASA files in OnBot Java: if you installed it with Android Studio, delete the folder org/asa there and install the app again.",
                    "В OnBot Java файлов ASA нет: если ставили через Android Studio, удалите там папку org/asa и установите приложение заново.",
                    "În OnBot Java nu sunt fișiere ASA: dacă ați instalat cu Android Studio, ștergeți acolo folderul org/asa și instalați din nou aplicația.")));
        for (File f : plan.unmarked)
            out.add(Html.lv('y', tr("Looks like an older ASA file, not deleted, check it yourself: ", "Похоже на файл старой версии ASA, не удалён, проверьте сами: ",
                    "Pare un fișier dintr-o versiune ASA mai veche, neșters, verificați-l: ") + f.getName()));
        out.add("");
        out.add("<b>" + Html.esc(tr("Last step: press STOP, then in OnBot Java press Build.", "Последний шаг: нажмите STOP, потом в OnBot Java нажмите Build.",
                "Ultimul pas: apăsați STOP, apoi în OnBot Java apăsați Build.")) + "</b>");
        out.add(Html.dim(tr("ASA Black Box then disappears from the list.", "После этого ASA Black Box исчезнет из списка.", "Apoi ASA Black Box dispare din listă.")));
        return out;
    }

    /** Deletes f and everything inside it; returns how many files were deleted. */
    static int delete(File f) {
        int n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += delete(k);
        boolean isFile = f.isFile();
        if (f.delete() && isFile) n++;
        return n;
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
