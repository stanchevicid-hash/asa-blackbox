# ASA Black Box for FTC

[![tests](https://github.com/stanchevicid-hash/asa-blackbox/actions/workflows/tests.yml/badge.svg)](https://github.com/stanchevicid-hash/asa-blackbox/actions/workflows/tests.yml)

**A flight recorder and health monitor for your FTC robot.** It logs every motor, servo and the battery plus
your own signals and events. It detects problems while the robot drives and shows whether the robot is getting
worse from session to session.

Made by **ASA, FTC team 28284**. MIT license, free for every team.

## What you get

- **Whole robot in one line**: `FtcBlackBox.attachAll` records power, position, velocity and current for every
  motor, every servo position and the battery, plus the loop time.
- **Your own signals and events**: targets, states, `SCORE_ATTEMPT`, `INTAKE_JAM`, all on one timeline.
- **Automatic diagnostics** (standard rules added for you):
  - **stall**: high current, no motion, real command
  - **encoder lost**: an encoder that used to move reads 0 while the motor is driven
    (motors without an encoder never trigger it)
  - **brownout**: battery below 8 V
  - **slow loop**: over 50 ms for a second
  - write your own: `bb.rule("intake-jam").when(...).forTime(0.25)`
- **Baseline**: each session's motor currents, battery and loop time are compared with the median of the
  previous sessions, e.g. `lift.amps peak 5.2 vs 3.4 (+53%)`. It catches a mechanism wearing out before it fails.
- **Driver Hub**: warnings in telemetry. **Web UI** at `http://192.168.43.1:8099/`: live values,
  per-session graphs with event markers, reports.
- **Safe for matches**: file writing runs on a background thread, a failing sensor reads NaN, a full disk
  drops rows, and nothing throws into your loop.

## Install

Copy `src/org` into `TeamCode/src/main/java/` (Android Studio). No Gradle changes and no other dependencies.
It works with FTC SDK 9.x through 12.x.

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
- **Not yet verified on a physical Control Hub.** If you run it on a real robot, please open an issue.
- Code runs on the Control Hub (or an RC phone). The Driver Hub shows the telemetry.

Shares its core (`Recorder`, `Ftc`, `AsaWeb`) with [asa-sysid](https://github.com/stanchevicid-hash/asa-sysid). Use both: SysId tells
you how the robot *should* behave, and Black Box shows how it *does*.
