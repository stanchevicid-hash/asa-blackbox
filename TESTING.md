# ASA Black Box 1.5.0: robot test

Time: about 1 hour. You need: a robot with a Control Hub (an Expansion Hub too, if you have one), a Driver Hub, a phone
or laptop, a charged battery, a stand so the wheels hang in the air.

The quotes below are in English: in the INIT of "ASA Black Box", press **X** to switch the language to English (or
compare by meaning in your own language).

How to record the result: mark every step ✅ (as written), ⚠️ (different, but works) or ❌ (does not work).
Take a photo of the Driver Hub screen and/or the page for every ⚠️ and ❌.

---

## 0. Setup

| # | Do | You should see |
|---|---|---|
| 0.1 | Laptop on the robot Wi-Fi, `http://192.168.43.1:8080` → OnBot Java. **Download a copy of all your team's code** (just in case) | the copy on the laptop |
| 0.2 | If ASA 1.4 was installed: delete its files `Rule`, `Live`, `Recorder`, `Ftc`, `Dashboard`, `WpiLog`, `BatteryHealth`, `CenterOfGravity` | the files are gone |
| 0.3 | Get this repository (`git clone https://github.com/stanchevicid-hash/asa-blackbox` or Code → Download ZIP). Upload all 17 files from its `onbot/` folder into `teamcode` (no subfolders). Build | "Build successful". On "bad source file", press Build again (up to 3 times) |
| 0.4 | Driver Hub: ⋮ → **Restart Robot** | **ASA Black Box** is in the program list |

## 1. The 7 main steps (from the README)

| # | Do | You should see |
|---|---|---|
| 1 | Pick **ASA Black Box**, INIT (not START) | 🟢 "Every match is recorded (hooked in at power-on)" and the line "RESULTS: … :8099" |
| 2 | Phone on the robot Wi-Fi, open the address from step 1 | the page with the ASA logo, a green "robot online" dot at the top |
| 3 | In ASA Black Box: **RB** to "Recording test", START. The robot does not move, ~16 s | three green lines: the loop with recording about the same as without; about the expected number of samples; encoders read on every motor |
| 4 | Robot on the stand. ASA Black Box → "Health check", START, **Y** | a green "OK … encoder counts up" for every motor. Red or yellow is a real finding: a wire, a port or a direction |
| 5 | Run **your own** TeleOp: INIT, START, drive a minute, STOP | live graphs on the page while driving (the "Live" tab); after STOP it opens the recording named after your TeleOp |
| 6 | INIT ASA Black Box again | "Newest recording: <your TeleOp>, time" |
| 7 | There, **Y** → "Automatic recording is off", run your TeleOp | no new recording. Then switch it back on (**Y**) |

**Always take a photo of step 3**: we need that screen in any case.

## 2. The fixed bugs

A code review found these bugs. The fixes pass the tests on a computer, but nobody has seen them on a real robot yet.

### 2.1 Encoders and direction (the most important)

| # | Do | You should see |
|---|---|---|
| 2.1.1 | Your TeleOp: drive **forward** 2-3 s, STOP. On the page open the recording → "Graphs for a detailed look" → `pos` of each wheel | every wheel's `pos` **goes up** (as `getCurrentPosition()` does in your code), not down |
| 2.1.2 | With an **Expansion Hub**: the same for its motors | the Expansion Hub motors have their own values, not a copy of the Control Hub motors |
| 2.1.3 | The report of that recording | no false "the encoder counts against the power" and no "a hub stopped answering" |

### 2.2 Your program is not slowed down

| # | Do | You should see |
|---|---|---|
| 2.2.1 | Your TeleOp with the IMU: INIT | INIT takes as long as without ASA. The IMU works (the angle changes when you turn) |
| 2.2.2 | Drive, compare with how it feels without ASA (switch it off with Y, drive again) | no difference |

### 2.3 Health check

| # | Do | You should see |
|---|---|---|
| 2.3.1 | Health check, START, **Y** (automatic test), during the test **hold B** for 2 s | all motors stop at once. **No motor turns backward** while B is held |
| 2.3.2 | **X** (report), then **Y** (test again) | the test starts again, the program does not crash |

### 2.4 Center of gravity

| # | Do | You should see |
|---|---|---|
| 2.4.1 | "Center of gravity", START, step 1 (robot flat) - **A** | step 1 accepted |
| 2.4.2 | Without tipping the robot, **A** again | the step is **not accepted**, the screen says "tip it more: at least 3°" |
| 2.4.3 | Do all the steps properly | a result with sensible numbers; the page draws a top and a side view. No `Infinity` or `NaN` anywhere |

### 2.5 The page during a match (the Driver Station's Wi-Fi)

| # | Do | You should see |
|---|---|---|
| 2.5.1 | On the laptop start downloading a big recording (the **WPILOG** of a long session), INIT your TeleOp right away | the download **stops**; the Driver Station does not lose the connection |
| 2.5.2 | While driving, open an old long recording on the page | "The robot is driving: big recordings open after it stops"; after STOP it opens |

### 2.6 Your own Black Box (if you use `FtcBlackBox.attachAll` or `BlackBox.create`)

| # | Do | You should see |
|---|---|---|
| 2.6.1 | Run a TeleOp that has its own Black Box | **one** recording per run, not two |

## 3. "Remove ASA" (at the very end, on a copy or when you no longer need ASA)

| # | Do | You should see |
|---|---|---|
| 3.1 | If your code uses Black Box: ASA Black Box → **RB** to "Remove ASA", START | red "Your code uses Black Box, so nothing is removed" and the list of your files. Nothing deleted |
| 3.2 | If old ASA 1.4 files without the mark are there | yellow "These files have ASA's names but not its mark", the list. Nothing deleted |
| 3.3 | Otherwise: hold **A + B** for 3 s | the bar fills up; releasing earlier cancels |
| 3.4 | Hold to the end, then STOP → OnBot Java → Build | "ASA removed". No ASA file left in OnBot Java, **all your files are there**, Build passes |
| 3.5 | If ASA SysId is installed too | SysId works, its constants (`sysid/*.ff`) are still there |

## 4. What to send us

- The photo of step 3 (always) and of every ⚠️ / ❌.
- The table with your marks.
- Motor models (goBILDA / REV HD Hex / others), the SDK version (Driver Hub → ⋮ → About), Expansion Hub yes/no.
- Form: https://github.com/stanchevicid-hash/asa-blackbox/issues/new?template=robot-result.yml

If something goes really wrong: Y switches the automatic recording off, and "Remove ASA" (section 3) takes everything
out. Your code is in the copy from step 0.1.
