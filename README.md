# Icon Mixer

**Fill the gaps in your icon pack.** Most icon packs only cover ~80% of the apps on a phone,
so system apps and newer apps keep their stock icons and the home screen looks inconsistent.
Icon Mixer applies an icon pack, shows you exactly which apps it misses, lets you pick an
icon for each of them from **any** icon pack you have installed, and then **exports the whole
thing as a new, installable icon pack** that your launcher can apply.

Built with ColorOS / OxygenOS in mind (their launchers accept icon packs but offer no way to
fix the gaps in bulk), and meant for any launcher that supports icon packs: Nova, Lawnchair,
Smart Launcher, Niagara, Good Lock Theme Park on One UI, and others.

## Download

Get the latest APK from [Releases](../../releases) and install it (allow "install unknown
apps" when Android asks). Android 8.0 or newer.

## Features

- **App scanner**: every launchable app, user and system, with its current icon.
- **Icon pack detection**: finds installed packs (anything made for Nova/ADW-style launchers,
  e.g. CandyCons, Whicons, Delta, Lines, Crayon, Pix Material You) with author, icon count and
  how much of *your* phone each one covers.
- **Auto matching**: create a theme from a pack and every icon it has is assigned. Coverage is
  shown as apps / themed / missing / %.
- **Auto-fill (one tap)**: fills the rest from icons your other packs made for those apps, then
  best guesses by name, then generates icons in your pack's style (its back plate, mask and
  scale). Guesses and generated icons go to a "To review" list.
- **Missing icon manager**: the editor lists unthemed apps; for each one you get icons other
  packs made for that exact app, name-based suggestions, and searchable browsing of every
  installed pack, with a preview next to your other icons. Or keep the original, or use any
  image.
- **Theme editor**: grid or list view, filters (missing / auto / manual / new / system),
  multi-select bulk edits, undo, reset an app or the whole theme.
- **App colours**: wallpaper colours (Android 12+) or one of 12 colours, each a full Material You palette; pure black dark theme.
- **Icon shape for adaptive icons**: circle, squircle, rounded square, teardrop or square,
  even on launchers that can't change icon shape.
- **Adjust icons**: size and a coloured background plate, so icons from different packs blend.
- **Home-screen preview** on your wallpaper colours (or a photo), with missing icons highlighted.
- **Live Material You colours (experimental)**: icons from packs like Pix Material You keep
  following your wallpaper in the exported pack.
- **Calendar icons** that show today's date, when the source pack has them.
- **Light / dark icons**: packs with separate dark-mode icons keep both; your launcher switches with the phone's theme.
- **In-app updates** from GitHub releases (Settings → App updates).
- **Themes**: save, rename, duplicate, delete; ZIP backup and import (also imports exported APKs).
- **New apps**: when you install an app it's themed automatically from your packs if
  possible; otherwise you get a notification to pick an icon. The installed pack then updates
  itself (silently on Android 12+).
- **Export**: builds a real icon-pack APK on the phone. Re-exporting updates the installed
  pack in place. Themes can be shared as a ZIP and opened straight into Icon Mixer.

## Using it

1. Open Icon Mixer → **New theme** → choose your main icon pack.
2. Tap **Fix N** and pick icons for the apps the pack doesn't cover.
3. **Export → Build icon pack → Install**.
4. Apply it in your launcher:
   - ColorOS / OxygenOS: long-press the home screen → Icons (or Wallpapers & style → Icons).
   - Nova / Lawnchair / Smart / Niagara: launcher settings → Icon pack.
   - One UI: Good Lock → Theme Park → Icon.

After changing icons (or your wallpaper, for Material You style packs), export again; the
installed pack updates.

## Good to know

- Exported icons are images, except with **Live Material You colours** turned on: then icons
  from Material You packs stay live (shape and size adjustments don't apply to those).
- Exported packs are signed with a key created on your phone. If you reinstall Icon Mixer or
  clear its data, uninstall packs exported earlier before installing new ones.
- Private by design: no accounts, no analytics. The only network access is checking GitHub for
  a new Icon Mixer version (once a day, can be turned off in Settings) and downloading it when you tap Update.

## Building from source

Requirements: JDK 17 and the Android SDK (platform 37). Then:

```sh
./gradlew :app:assembleRelease        # Windows: gradlew.bat :app:assembleRelease
```

Without a private `keystore/key.properties` the build is signed with the Android debug key.

Tech: Kotlin, Jetpack Compose (Material 3), MVVM, Room, WorkManager, apksig, MaterialKolor.
Third-party licences: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

| Code | What |
|---|---|
| `app/src/main/java/com/iconforge/app/core/` | Icon pack detection/parsing, app scanner, icon loading & shapes, theme repository (matching, undo, new-app sync) |
| `…/export/` | On-device APK builder: binary XML + `resources.arsc` writers, aligned zip, v2 signing, ZIP backup/import, installer |
| `…/data/` | Room database (apps, packs, themes, mappings) and settings |
| `…/ui/` | Compose screens and ViewModels |

The internal package id is `com.iconforge.app` (the app's working title).

Every push is built and tested by GitHub Actions (the APK is attached to the run as an
artifact, debug-signed). Release APKs are signed separately.

## Status

Early version. Scanning, pack detection, matching and editing have been used on a real
phone; exporting to different launchers still needs wider testing — reports via Issues are
welcome.
