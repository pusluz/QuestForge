# Zyng

A Meta Quest companion prototype with an amber-on-black interface, a liquid-glass-inspired surface option, and an animated domain-warp background.

## What works

- **App manager:** lists launcher-visible apps, opens them, and sends uninstall requests to Android's confirmation screen.
- **APK install:** choose a local APK and continue through Android's own installer and unknown-app permission screens.
- **Mod archive import:** choose a destination with Android's folder picker, then install a ZIP archive there. Existing files are backed up in a `Zyng Backups` folder; unsafe archive paths are skipped.
- **Device tools:** opens Android settings. Zyng does not silently change device settings or bypass Android access restrictions.
- **Interface:** overview, app manager, mods and files, device tools, and settings screens, designed for a wide Quest window and narrower layouts.

## Limits

Android decides which apps and folders Zyng can access. Newer Android versions may block protected game data and OBB folders even when a folder picker is used. There is no Shizuku/root/ADB bypass, remote mod catalog, or direct headset pairing in this version. Use only mods and APKs you trust, and only modify game files where allowed by the game publisher.

## Get the APK

The old `QuestForge.zip` archive has been removed. GitHub Actions builds a debug APK on pushes to `main`. Open **Actions → Build Zyng APK → latest successful run → Artifacts → zyng-debug-apk** to download it.

To build locally, use Java 17 and Gradle 8.7, then run:

```sh
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
