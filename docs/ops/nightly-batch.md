# The nightly batch on your machine

The batch writes and renders tomorrow's script (SPEC §5.1). It needs to run once a night whether or not
the app is open.

## How it runs

- **The app is running:** it runs the batch itself at `radiance.batch.crons` (default 09:00 and 11:00
  UTC, which is 3–4 am and 5–6 am in Chicago). At startup, and every minute after that, it checks
  whether a scheduled run was missed and runs it in the background. The second run of the night
  finishes renders that a budget window parked, or does nothing.
- **The app isn't running:** an OS timer runs the one-shot command:

  ```bash
  ./gradlew batch
  ```

  It runs `batch nightly` and exits. If the app turns out to be running, it holds the writer lease,
  so the one-shot run records `DEFERRED` and exits 0. The app sees the overdue night and runs the
  batch itself.
- **A missed night or two:** the next run backfills the gap (FULL for the last 48 hours, REDUCED for
  2–7 days, SUMMARY for 7–30 days, then one TIME_SKIP), then writes the next script.

Every run leaves a row in `batch_run`: status (`DONE`, `PARTIAL`, `DEFERRED`, `FAILED`), the script
window, fidelity, counts, cost and a `report_json` with the script's plan hash and notes.

Keys come from `.env` in the repository root, as for the app. Pass `-Pprofile=offline` to run with no
keys at all (FakeLlm prose).

## Windows (Task Scheduler)

Run this once in a terminal opened in the repository root. It runs every night at 3:30 am local time:

```powershell
schtasks /Create /TN "Radiance nightly batch" /SC DAILY /ST 03:30 /TR "cmd /c cd /d %CD% && gradlew.bat batch >> backend\data\batch.log 2>&1"
```

To remove it later, run `schtasks /Delete /TN "Radiance nightly batch" /F`. If the machine is usually
asleep at that hour, open the task in Task Scheduler and tick **Run task as soon as possible after a
scheduled start is missed** (Settings tab).

## macOS (launchd)

Save as `~/Library/LaunchAgents/app.radiance.batch.plist`, with `/path/to/Radiance` replaced by your
repository path, then run `launchctl load ~/Library/LaunchAgents/app.radiance.batch.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>app.radiance.batch</string>
  <key>WorkingDirectory</key><string>/path/to/Radiance</string>
  <key>ProgramArguments</key><array><string>./gradlew</string><string>batch</string></array>
  <key>StartCalendarInterval</key><dict><key>Hour</key><integer>3</integer><key>Minute</key><integer>30</integer></dict>
  <key>StandardOutPath</key><string>/path/to/Radiance/backend/data/batch.log</string>
  <key>StandardErrorPath</key><string>/path/to/Radiance/backend/data/batch.log</string>
</dict>
</plist>
```

launchd runs a missed job when the Mac wakes.

## Linux (cron or systemd)

The cron line (`crontab -e`):

```
30 3 * * * cd /path/to/Radiance && ./gradlew batch >> backend/data/batch.log 2>&1
```

Or, to catch a run missed while the machine was off, a systemd user timer with `Persistent=true`:

```ini
# ~/.config/systemd/user/radiance-batch.service
[Service]
Type=oneshot
WorkingDirectory=/path/to/Radiance
ExecStart=/path/to/Radiance/gradlew batch

# ~/.config/systemd/user/radiance-batch.timer
[Timer]
OnCalendar=*-*-* 03:30
Persistent=true
[Install]
WantedBy=timers.target
```

Then run `systemctl --user enable --now radiance-batch.timer`.
