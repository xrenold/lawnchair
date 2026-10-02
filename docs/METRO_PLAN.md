# Metro Launcher — Full Plan

A Windows Phone 8.1-style launcher for the Samsung Galaxy S23 Ultra, built on a fork of Lawnchair.

> Windows Phone never had an "8.5" release. 8.1 is the last and most complete Metro Start screen: transparent tiles over the wallpaper, a 6-column grid and live tiles. This plan targets 8.1. A few Windows 10 Mobile touches (adjustable tile transparency) are included where they fit.

---

## 1. Goals

- **Start screen.** A single, vertically scrolling grid of square tiles, like WP 8.1. No pages, dock or search bar.
- **Tiles.** Small, medium, wide and large tiles that you can pin, unpin, drag, resize and reflow.
- **Styles.** Solid tiles or semi-transparent tiles, with adjustable opacity. Also the 8.1 "window" mode, where the Start background is black and the wallpaper only shows through the tiles.
- **Colors.** Tile colors follow Android's Monet (Material You) palette automatically and change when the wallpaper changes. You can also pick a fixed classic WP accent, or override the color per tile.
- **Live tiles.** Tiles flip to show content: notification counts, messages, calendar, photos, music, clock, weather. Any Android widget can also be embedded as a tile.
- **App list.** Swipe left from Start for the A–Z app list, with letter tiles, a jump grid and search.
- **Motion.** Turnstile app launch and return, tilt on press, tile flips, and staggered entrance animations.
- **Branding.** Rebranded as "Metro" everywhere, with Lawnchair credited as GPL-3.0 requires.
- **Performance.** Smooth at 120 Hz on the S23 Ultra.

## 2. Architecture decision

Launcher3's workspace is built around horizontal pages and a fixed cell grid. Bending it into a vertically scrolling Metro grid with free resizing and reflow would mean fighting it everywhere.

**Decision:** build our own Start screen and App list as new views inside Lawnchair's `DragLayer`, replacing the workspace, hotseat and drawer on screen. Lawnchair keeps doing what it does well:

| Keep from Lawnchair / Launcher3 | Replace with Metro |
|---|---|
| App loading, icon cache, package events | Workspace, hotseat and QSB → **StartView** |
| AppWidgetHost and widget binding | All-apps drawer → **AppListView** |
| Notification listener (dots) | Workspace grid model → **Metro tile DB** |
| App launch and activity plumbing | Folder UI (folders become tile groups later) |
| Settings infrastructure (Compose) | Settings screens → Metro-styled pages |

New code lives in `lawnchair/src/app/lawnchair/metro/`, organised into `start/`, `applist/`, `tiles/`, `live/`, `theme/`, `motion/` and `data/`.

**Rendering:** a custom `ViewGroup` (`TileGridView`) inside a vertical scroller. This uses plain Views rather than Compose, for precise control over drag, reflow, hardware layers and 120 Hz flip animations. Settings screens stay in Compose.

**Storage:** a Room table in Lawnchair's existing `AppDatabase`:

```
MetroTile(id, type[app|widget|live|group], componentKey, widgetId,
          col, row, size[SMALL|MEDIUM|WIDE|LARGE],
          colorMode[AUTO|ACCENT|CUSTOM], customColor, liveSource, liveEnabled)
```

On first launch, the current home screen items are imported as medium tiles.

## 3. Grid and tile sizes (WP 8.1)

The S23 Ultra screen is 1440 × 3088 px, about 412 dp wide.

| Setting | Columns of small tiles | Small | Medium | Wide | Large |
|---|---|---|---|---|---|
| "Show more tiles" ON (default) | 6 | 1×1 | 2×2 | 4×2 | 4×4 |
| "Show more tiles" OFF | 4 | 1×1 | 2×2 | 4×2 | 4×4 |

- The gutter is about 5 dp, with a 12 dp margin on the outer edges, as on WP.
- Labels sit bottom-left on medium, wide and large tiles. Small tiles show no label, only a badge count in the corner.
- Icons are monochrome white glyphs at about 40% of the tile's size. Android's themed/monochrome icon is used when the app provides one; otherwise the full-color icon is used.
- A count badge sits bottom-right on medium tiles and top-right on small tiles.

## 4. Tile styles and Monet colors

**Background modes**
1. **Window (WP 8.1 classic).** The Start background is black. Tiles are cut-outs that show the fixed wallpaper behind them, darkened slightly. As you scroll, the wallpaper stays still while the tiles move across it.
2. **Wallpaper.** The wallpaper shows behind everything, under semi-transparent tiles.
3. **Black.** A solid black background with solid tiles.

**Tile fill**
- **Solid.** 100% opaque.
- **Semi-transparent.** An opacity slider from 20% to 90%, with an optional subtle blur on Android 12+ (`RenderEffect`).
- In window mode, the "fill" is the wallpaper plus an accent tint at a set strength.

**Color sources**
- **Auto (Monet).** Default.
  - **Single accent:** every tile uses `system_accent1_600`, which is closest to WP's single-accent look.
  - **Tonal mix:** each tile gets a hash-stable pick from accent1, accent2 and accent3 tones at shade 500–700, for more variety while staying on-palette.
  - **Live updates:** listen for wallpaper and theme changes (`WallpaperManager.OnColorsChangedListener` plus configuration changes) and animate every tile to its new color with a 300 ms crossfade. No restart needed.
- **Classic WP accents.** The 20 original colors: lime, green, emerald, teal, cyan, cobalt, indigo, violet, pink, magenta, crimson, red, orange, amber, yellow, brown, olive, steel, mauve and taupe.
- **Per-tile override.** Long-press a tile, then choose Color.
- Text contrast is always checked: white labels, with a darker shade automatically used on very light Monet tones.

## 5. Start screen interaction

- Tap a tile to launch the app with a turnstile animation.
- **Long-press a tile** to enter edit mode, the same as WP 8.1:
  - The selected tile lifts by scaling up slightly. The other tiles shrink to 95% and dim.
  - The tile shows an **unpin** button (top-right) and a **resize** button (bottom-right). The resize button cycles through small, medium, wide and large, skipping sizes the app doesn't support, such as large for most apps.
  - **Drag** to move the tile. Other tiles reflow live around it with 150 ms animations. The list auto-scrolls when you drag near the top or bottom edge.
  - Tap empty space or press Back to leave edit mode.
- **Reflow algorithm.** Each tile has an explicit (col, row). When you drop a tile, any tiles it overlaps are pushed down, then the whole grid is compacted upward column by column, without reordering tiles the user placed. This mirrors WP's gravity-to-top behaviour.
- **Pinning.** Long-press an app in the app list and choose "Pin to Start". The tile is added at the bottom of the grid as a medium tile.
- **Haptics.** Light tick on lift, resize and drop.
- **Gestures.** Swipe left or tap the → arrow at the bottom to open the App list. Swipe down for the notification shade. A double-tap to lock can be added (it needs the accessibility service).

## 6. App list (WP 8.1)

- A vertical alphabetical list grouped under letter headers. Each header is an accent-colored square with a white letter.
- Tap a letter header to open the **jump grid**: a full-screen grid of letters, with unused letters dimmed. Tap a letter to jump to it.
- A search box at the top filters as you type.
- Long-press an app for: Pin to Start, App info, Uninstall.
- Newly installed apps appear at the top under "new" until they're opened.
- It reuses Launcher3's app list data, so hidden apps and work profile apps still work.

## 7. Live tiles

**Engine** (`metro/live/`)
- Each live tile has a front face (icon and label) and one or more back faces (content).
- Tiles flip on a 3D X-axis rotation with a slight perspective, as on WP. The flip interval is 6–12 s, randomised per tile so tiles never flip in sync.
- Live tiles pause when Start isn't visible or the screen is off, and resume on return.
- Only tiles that are on screen animate, using hardware layers so animations stay at 120 Hz.
- Live tiles can be turned off per tile ("Turn live tile off"), as on WP.

**Content sources**

| Source | What the tile shows | Needs |
|---|---|---|
| Notification count | Badge with the number on the app's tile | Notification access (Lawnchair already has this) |
| Messaging peek | Sender, plus one line of text on the back face (privacy toggle, off by default) | Notification access |
| Clock / Date | Large time and date, day of week | — |
| Calendar | Next event title, time and location | `READ_CALENDAR` |
| Photos | Cycles through recent photos, Ken Burns style | `READ_MEDIA_IMAGES` |
| Music | Album art and track title for what's playing, from media sessions | Notification access |
| Weather | Temperature, condition and city, from Open-Meteo (free, no API key) | Location (approximate) |
| People | Mosaic of contact photos | `READ_CONTACTS` |
| Battery / System | Battery level and charging state | — |
| Any Android widget | The widget embedded at tile size, framed as a tile | Widget bind permission (already handled) |

Third-party Android apps don't publish WP-style tile data, so app tiles go live through their notifications (counts and peeks) or by embedding the app's widget.

> **Install note:** notification access and contacts are the permissions that trigger Play Protect's sideloading block in India. Keep installing with ADB (wireless works), or have the build skip the sources you don't want.

## 8. Motion (WP 8.1 feel)

- **Tilt.** Pressing a tile tilts it in 3D toward your finger (up to about 8°) and sinks it slightly.
- **Turnstile out.** On launch, tiles swing away column by column around a vertical axis on the left edge, staggered by about 20 ms, then the app opens.
- **Turnstile in.** When you return home, tiles swing back in.
- **Start entrance.** On unlock, tiles fly in, staggered by row.
- **Slide to app list.** A horizontal slide with slight parallax between Start and the list.
- **Edit mode.** Spring-based lift, drop and reflow, using `SpringAnimation` from AndroidX dynamic animation.
- Durations follow WP: short, crisp, with no overshoot except the lift.

## 9. Typography and branding

- **Font.** Selawik, Microsoft's open-source (OFL) metric-compatible substitute for Segoe UI. It's used for tile labels, the app list, headers and settings.
- **Rebrand:**
  - App name "Metro" and package `app.metro.launcher`. The package change means it installs as a new app; the current debug build can then be removed.
  - A new adaptive launcher icon: a white tile grid on the accent color.
  - Remove Lawnchair's onboarding, the "Set up Lawnchair" smartspace text, update prompts, the Telegram and Crowdin hooks and the Lawnchair name in all strings.
  - An About page that credits Lawnchair and AOSP Launcher3 and links to this fork's source. This is required by GPL-3.0 if the APK is ever shared.
- **Settings.** Rebuilt in a Metro style: lowercase pivot headers ("start", "tiles", "live tiles", "app list", "about"), flat lists and accent toggles. They're grouped under: Start, Tiles & colors, Live tiles, App list, Gestures, Backup, About.

## 10. S23 Ultra / One UI specifics

- The screen is 1440 × 3088 px at about 500 dpi, and the grid math uses dp so it scales correctly.
- 120 Hz: avoid allocating during draw, use hardware layers for flips and turnstile, and keep tile bitmaps cached.
- **Gestures.** On One UI, third-party launchers get Samsung's own recents screen, not Lawnchair's, so we leave recents alone.
- **Background limits.** Samsung's battery optimisation can kill background services. Live sources run only while Start is visible, and we prompt once to exclude Metro from "Sleeping apps".
- Keep Lawnchair's handling of display cutouts and insets for the status bar and gesture bar.

## 11. Phases

Every phase ends with a new APK on the `metro-latest` release.

| # | Phase | Delivers |
|---|---|---|
| 0 | Done | Fork, CI, release link, first tile drawing on the existing workspace |
| 1 | Foundation and rebrand | `app.metro.launcher` package, Metro name and icon, Selawik font, Lawnchair onboarding removed, `MetroTheme` color engine (Monet single and tonal, classic accents, live wallpaper-change updates) |
| 2 | Start screen | `StartView` and `TileGridView` replace workspace, hotseat and QSB. Tile DB with import from the current home screen. Four tile sizes, 4/6 columns, solid, semi-transparent and window modes, monochrome icons, labels and badges |
| 3 | Edit mode | Long-press lift, drag with live reflow, resize cycling, unpin, pin from app list, auto-scroll, haptics, persistence |
| 4 | App list | A–Z list, letter tiles, jump grid, search, "new" apps, swipe between Start and list |
| 5 | Live tiles | Flip engine, notification counts and peeks, clock and date, calendar, photos, music, weather, people, battery, embedded widgets |
| 6 | Motion | Tilt, turnstile in and out, Start entrance, list slide |
| 7 | Settings and polish | Metro settings pivots, per-tile options, backup and restore, cleanup of unused Lawnchair UI |

## 12. Decisions to confirm

1. **Package and name.** `app.metro.launcher` and "Metro". The package change means a fresh install, so the home layout is set up again after import.
2. **Default grid.** 6 columns ("show more tiles") or 4.
3. **Default look.** Window mode (8.1 classic) or semi-transparent over the wallpaper.
4. **Message peeks on tiles.** Showing message text on the home screen is a privacy risk, so it's off by default.
5. **Folders.** WP 8.1 added tile folders. Drop folders for now, or build them as a later phase.
