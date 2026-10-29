# Brief: QC lane $lane_id ($lane_title)

You are running one lane of a QC run. Your results go in `$results_file`;
evidence goes in `$run/evidence/`, named `<scenario id>-<device>-<what>.png`
(or `.txt`). You run only the rows in that file. Other lanes run at the same
time on other devices: never touch a device, emulator, account or worktree
that is not listed here.

## Your setup

- **Worktree:** your own git worktree of this repository (never the main
  checkout and never another lane's). Before the first Gradle command: copy
  `local.properties` from the main checkout and run
  `git submodule update --init --recursive`. Do not edit `local.properties`;
  it points at production.
- **Backend:** `$backend` (`qa/environments.toml`). Its URL and switches come
  from each build's `env` below, set in your shell only.
- **Tools:** $tools.

### Devices (yours alone)

$devices

Set `ANDROID_SERIAL` to the device's serial (`adb devices -l`) before every
`adb` or Gradle install command. A Gradle install task without it installs on
every attached device, including other lanes' emulators.

### Builds

$builds

### Accounts

$accounts

Passwords and second factors are not in this repository. Ask for them at the
first sign-in; never write one into a file, a screenshot or your results.

## Rules

1. **Never run `gradlew --stop`.** Other lanes' Gradle daemons share the
   machine.
2. **On a timeout, kill the whole process tree** of the command (Gradle, adb,
   the emulator's instrumentation) before you retry. A timed-out background
   command keeps its children running.
3. **Stop and ask at a human gate.** A scenario that lists a `human_gate` is
   done by the product owner at that step. Ask with the exact click or
   command, then wait. The gates in this lane:

$gates

4. **Never deploy, never write to Firestore, never run a migration with
   `--apply`.** Staging shares production's Firestore.
5. **Never `pm clear`, wipe or reset the Pixel 6**, and do not use it at all
   (`use = false` in `qa/environments.toml`). UI checks run on emulators.
6. **One agent per real phone.** If a real phone is listed above, no other
   lane has it; do not share it.
7. **Do not fix product code in this lane.** A fail is a result: record it
   with evidence, and say what you saw. A fix is a separate PR.

## Doing a row

1. `python scripts/qa/qa.py list --lane $lane_id` lists your scenarios; each
   one's `steps` and `expected` are in its table in `qa/suites/`.
2. Do the steps on the row's device and build. Compare with `expected`.
3. Fill the row: result, build, evidence, note.

## When you finish

Leave `$results_file` complete (every row filled, or blank with a note saying
why it was not run), then report: how many rows passed, failed, were blocked
or skipped; each fail with its evidence; each gate you are still waiting on.
The run's owner merges the lanes with
`python scripts/qa/qa.py report --run $run`.
