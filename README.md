# ASA Black Box for FTC

[![tests](https://github.com/stanchevicid-hash/asa-blackbox/actions/workflows/tests.yml/badge.svg)](https://github.com/stanchevicid-hash/asa-blackbox/actions/workflows/tests.yml)
[![android](https://github.com/stanchevicid-hash/asa-blackbox/actions/workflows/android.yml/badge.svg)](https://github.com/stanchevicid-hash/asa-blackbox/actions/workflows/android.yml)

**A flight recorder and health monitor for your FTC robot.** It logs every motor, servo and the battery plus
your own signals and events. It detects problems while the robot drives and shows whether the robot is getting
worse from session to session.

Made by **ASA, FTC team 28284**. MIT license, free for every team.

**Полная инструкция на русском: [GUIDE.ru.md](GUIDE.ru.md)** (установка, запуск, все предупреждения, как это работает).
**Ghid complet în română: [GUIDE.ro.md](GUIDE.ro.md)** (instalare, rulare, toate butoanele și avertismentele, cum funcționează).

## What you get

- **Whole robot in one line**: `FtcBlackBox.attachAll` records power, position, velocity and current for every
  motor, every servo position, the IMU heading, distance sensors, analog inputs, touch sensors, the hubs and the
  battery, plus the loop time. `FtcBlackBox.attachGamepads(bb, gamepad1, gamepad2)` adds the drivers' sticks.
- **Your own signals and events**: targets, states, `SCORE_ATTEMPT`, `INTAKE_JAM`, all on one timeline.
- **Automatic diagnostics** (standard rules added for you):
  - **stall**: high current, no motion, real command (0.5 s; 1 s for a motor without an encoder)
  - **encoder lost**: an encoder that used to move reads 0 while the motor is driven
    (motors without an encoder never trigger it)
  - **brownout**: battery below 8 V
  - **slow loop**: over 50 ms for a second; **loop pause**: a single loop over 150 ms
  - **motor overheating**: RMS current over ~2 minutes above 5 A
  - **drivetrain wheel dragging**: one wheel turning with the others at the same power but drawing 2x their current
    for 2 s (a bent axle, a rubbing wheel, a tight chain). Wheels are found by name (`frontLeft`, `lf`, ...)
  - **frozen IMU**: heading identical to the last bit for 5 s while the motors are driven (a hung I2C bus)
  - **gamepad disconnected** and **low battery at INIT** (below 12.4 V with the robot idle)
  - write your own: `bb.rule("intake-jam").when(...).forTime(0.25)`
- **Baseline**: each session's motor currents, battery and loop time are compared with the median of the
  previous sessions, e.g. `lift.amps peak 5.2 vs 3.4 (+53%)`. It catches a mechanism wearing out before it fails.
- **Driver Hub**: warnings in telemetry. **Web UI** at `http://192.168.43.1:8099/`: live values,
  per-session graphs with event markers, reports.
- **FTC Dashboard**: if your team uses it, every signal shows up there as a live graph automatically.
  It is found at runtime, so there is no dependency to add.
- **AdvantageScope**: each session downloads as a `.wpilog` from the web UI and opens in
  [AdvantageScope](https://github.com/Mechanical-Advantage/AdvantageScope) with every signal in a tree and the
  events on the timeline. It is validated with the official WPILib log reader. On a laptop:
  `java -cp <classes> org.asa.tools.core.WpiLog session.csv`.
- **Safe for matches**: file writing runs on a background thread, a failing sensor reads NaN, a full disk
  drops rows, and nothing throws into your loop.
- **Hub resets** (static discharge, loose power): reported the moment every encoder of a hub jumps back to 0, and
  whenever a hub stops answering. This is the usual cause of "the robot froze for half a second".
- **Battery health**: the internal resistance of the battery and its wiring, measured from normal driving and
  tracked per battery (`bb.battery("A")`). The report says which battery is getting worse than it used to be.
- **Loop profiler**: `bb.section("vision")` … `bb.section("drive")` … shows where the loop time goes
  (`loop 24 ms: vision 14 ms (59%), drive 3 ms …`).
- **Crash capture**: `bb.crash(e)` saves the error, its stack trace and the last value of every signal.

## Ready-made programs on the Driver Hub

These two appear in the Driver Hub's OpMode list as soon as the library is installed. No code to write.

| OpMode | What it does |
|---|---|
| **ASA Health Check** | Every hub (answering, temperature, voltage), each battery's health from past sessions, and every motor: hold A or B to turn the selected motor at 0.3 and see if its encoder counts and which way. A motor only turns while you hold the button. |
| **ASA Center of Gravity** | Measures where the center of gravity is (height, front/back, left/right) with the hub's IMU: tip the robot onto its rear, front, right and left wheels until it balances, pressing A each time. It reports how hard the robot can brake, accelerate and turn before it tips, and the steepest ramp it can stand on. Do it again with the lift up to see how much the lift moves it. No motor moves. |

## Install (2 minutes)

**Android Studio:** download `asa-blackbox-dropin.zip` from the
[latest release](https://github.com/stanchevicid-hash/asa-blackbox/releases/latest) and unzip it at the root of your
FtcRobotController project. The files land in `TeamCode/src/main/java/org/asa/tools/`. Or copy `src/org` there yourself.

**OnBot Java:** create the folders `org/asa/tools/core`, `org/asa/tools/ftc` and `org/asa/tools/blackbox`, then upload
the `.java` files from the matching folders in `src/`. The library itself uses plain Java 7 syntax; only the examples use lambdas.

No Gradle changes and no other dependencies. It works with FTC SDK 9.x through 12.x.
Using both ASA libraries? Install both. The shared files are identical, so overwriting them is fine.

## Use it: 4 lines

```java
BlackBox bb = BlackBox.create("teleop");
FtcBlackBox.attachAll(bb, hardwareMap);                       // before start()
bb.channel("lift").signal("target", () -> target);            // optional: your own signals
AsaWeb.start(); AsaWeb.publish(bb);                           // optional: web UI
waitForStart();
bb.start();
while (opModeIsActive()) {
    // ... your code ...
    bb.update();                                              // once per loop
    FtcBlackBox.show(telemetry, bb);                          // warnings on the Driver Hub
    telemetry.update();
}
bb.stop();                                                    // summary + baseline report
```

More:

```java
bb.event("INTAKE_JAM", "slot 2");
bb.rule("intake-jam").forTime(0.25).show("intake.amps", "intake.vel")
  .when(r -> r.get("intake.amps") > 4 && Math.abs(r.get("intake.vel")) < 50);
bb.notReachingRule("lift-slow", "lift.target", "lift.inches", 1.0, 1.5);
bb.baseline("lift.cycle_s", 0.05);                            // compare your own health metric between sessions
bb.battery("A");                                              // which battery is in: tracked across sessions
bb.profile("vision", "drive");                                // graph these sections over time
bb.section("vision"); /* ... */ bb.section("drive"); /* ... */ // time each part of the loop
bb.encodersReset();                                           // you reset encoders on purpose (not a hub reset)
try { /* loop */ } catch (RuntimeException e) { bb.crash(e); throw e; } finally { bb.stop(); }
```

See `src/org/asa/tools/examples/ExampleTeleOpBlackBox.java`.

## Loop cost

Bulk caching is switched to AUTO on hubs where it was OFF, so position and velocity cost one bulk read.
Motor current is not part of the bulk read (about 2 ms each), so it is sampled every 100 ms, and so is the battery.
Rate-limited reads are spread out: at most 2 per loop, so 8 motor currents never land in the same loop
(that would be a ~16 ms spike). Each `update()` is one pass over the signals; no file IO happens on your thread.
If the files cannot be written (no space, bad path), the Driver Hub shows `BlackBox NOT SAVING: ...` and the
robot keeps running.

## Files

In `/sdcard/FIRST/asa/`, one set per session:

| File | Content |
|---|---|
| `<date>_<tag>.csv` | every signal, every loop |
| `.events.csv` | your events + warnings with timestamps |
| `.summary.csv` | mean and peak of each signal |
| `.report.txt` | warning counts + changes vs previous sessions |

`sample-logs/` holds real sessions recorded in the ASA simulator. Open them in the web UI.

## How reliable are the warnings?

`test/BlackBoxFuzz.java` simulates full 150 s matches on random robots (2-8 motors, some without an encoder) with
realistic healthy driving: spin-up current spikes, reversals, a lift holding at 0.15-0.45 power with its encoder
still, idle motors, battery sag, and 8-25 ms loop jitter. It injects random faults: stalls, lost encoders,
brownouts and slow loops.

The v1.4 detectors (overheating, wheel dragging, loop pause, low battery at start) run in every match too and must
stay silent, since those matches are healthy for them.

| 9000 matches (3 seeds) | Result |
|---|---|
| Injected faults | **10163 of 10165 reported** by the right rule (the 2 misses: 0.6 s stalls, at the detection limit) |
| False alarms | **0** (including the new detectors) |

A stall is reported after 0.5 s of high current with no motion, in one direction (1 s for a motor without an
encoder, whose speed always reads 0). Stalls close to that limit may not be reported (the current is read every
~100 ms). `test/BlackBoxDetectorsTest.java` checks each new detector against its fault and a healthy look-alike.

`test/SoakTest.java` writes 10 simulated minutes (60000 rows × 40 signals) at 10× real speed: no row is dropped,
memory stays flat, every row is intact, and the whole session exports to WPILOG. Both run in CI on every push.

## Tests

`test/BlackBoxSelfTest.java` drives the rules with timed fake signals. It checks that the stall and encoder-lost rules each
fire exactly once, that a motor without an encoder never reports encoder-lost, that a throwing sensor reads NaN,
that the files are written, that the baseline reports a doubled stall current, that no loop does more than 2 slow
reads, and that a disk error is reported instead of thrown.

```bash
javac --release 8 -d out src/org/asa/tools/core/*.java src/org/asa/tools/blackbox/*.java test/BlackBoxSelfTest.java
java -cp out BlackBoxSelfTest
```

## Status

- Verified: compiles against the official FTC SDK **12.0.0** and **9.2.0** (RobotCore + Hardware from Maven),
  the self-test passes, and it ran in the ASA ftc-sim simulator with every device auto-discovered and the web UI live.
- Verified in CI on every push: only Java APIs present on **Android 7.1 (Control Hub)**, and it converts with **D8**.
- Speed: `update()` with 40 signals and 16 rules ≈ 8.5 µs on a laptop (well under 0.1 ms on a Control Hub).
- Web UI tested from a laptop: path-traversal attempts refused, the quit command accepted from the robot only,
  the port handed over after a code reload, and the WPILOG download valid per the official WPILib reader.
- **Not yet verified on a physical Control Hub.** If you run it on a real robot, please
  [send your result](https://github.com/stanchevicid-hash/asa-blackbox/issues/new?template=robot-result.yml).
- Code runs on the Control Hub (or an RC phone). The Driver Hub shows the telemetry.

Shares its core (`Recorder`, `Ftc`, `AsaWeb`) with [asa-sysid](https://github.com/stanchevicid-hash/asa-sysid). Use both: SysId tells
you how the robot *should* behave, and Black Box shows how it *does*.
