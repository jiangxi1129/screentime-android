# Screentime Reporter

A minimal Android app that reads today's per-app foreground usage from
`UsageStatsManager` and POSTs it to a self-hosted screentime server every
15 minutes.

## What it does

- Every 15 min, collect today's foreground time per app (filtering apps with
  < 60s of total foreground time)
- POST to `https://xixiclaire.top/api/screentime/report` as JSON
- Survives device reboot via WorkManager
- Works on Android 8+ (minSdk 26)

## How to install

1. Wait for GitHub Actions to finish (Actions tab → green check)
2. Go to Releases → download the latest `screentime-N.apk`
3. Sideload it to your phone (允许安装未知来源)
4. Open the app and tap the four buttons in order:
   1. **授权使用情况访问** — grant the special "Usage access" permission
   2. **启动定时上报** — schedule the periodic 15-min worker
   3. **立刻上报一次** — fire-and-forget test report
   4. **加入电池白名单** — exclude from vivo/Xiaomi/etc battery optimizer

## File layout

```
app/src/main/java/top/xixiclaire/screentime/
├── MainActivity.kt    # UI: 4 buttons, status display
├── UsageReader.kt     # reads UsageStatsManager
├── Reporter.kt        # POSTs JSON to /api/screentime/report
└── ReportWorker.kt    # WorkManager periodic worker
```
