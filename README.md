# TV Volume Overlay

A Google TV app that shows a floating volume indicator whenever you press volume up or down. Built for setups where a soundbar is connected via HDMI eARC and the TV no longer shows its own volume display.

---

## The Problem

When you connect a soundbar to a TCL Google TV via HDMI eARC, the TV hands off audio control to the soundbar and stops showing any volume indicator on screen. There's no setting to fix this — the TV simply doesn't track volume anymore.

## The Solution

This app runs as an Accessibility Service and listens for the CEC `<Report Audio Status>` message your soundbar sends after every volume change. It reads the exact reported level and displays a floating overlay on screen. Works across every app — Netflix, YouTube, live TV, everything.

---

## Features

- 10 customisable overlay styles (strips, pills, circles, capsules and more)
- Accurate volume tracking via HDMI CEC — no drift, no counting
- Auto-scales to any soundbar (set your max: 20, 40, 50, 100, whatever)
- Overlay auto-hides after 2.5 seconds
- Manual sync button if the level ever gets out of step
- Persists across reboots

---

## Install

### Quick install (APK)

1. Download `app-debug.apk` from the [Releases](https://github.com/m3aldabb/tv-volume-overlay/releases) page
2. On your TV: **Settings → Privacy → Security & Restrictions** → enable Unknown Sources
3. Transfer the APK to your TV and install it, or use ADB:

```bash
adb connect YOUR_TV_IP:PORT
adb install app-debug.apk
```

### Build from source

1. Enable Developer Options on your TV: **Settings → System → About** → tap Build 7 times
2. Enable Wireless Debugging: **Settings → System → Developer Options → Wireless Debugging**
3. Open the project in Android Studio, connect via ADB, and hit Run

---

## Setup (one-time)

1. Open the app and tap **Enable**
2. Find **TV Volume Overlay** in Accessibility Settings and toggle it ON
3. Back in the app, tap **⬆ Max Vol** and enter your soundbar's max volume steps
4. Tap **Change** to pick an overlay style
5. Press volume up or down — the overlay appears

---

## Troubleshooting

**Overlay doesn't appear** — re-enable the Accessibility Service in Settings

**Style didn't change** — press volume once after switching; the overlay rebuilds on the next volume event

**ADB won't connect** — Google TV uses a random port each session, check the current one in Wireless Debugging settings
