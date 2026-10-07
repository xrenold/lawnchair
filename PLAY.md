# Publishing Pane on Google Play

Notes for the Play Console. The app itself is ready for Play in the `play` build
(`bundleLawnWithQuickstepPlayRelease`); CI builds it on every push as the `pane-play-bundle` artifact.

## 1. Upload key (once)

Play signs the app with its own key (Play App Signing); you sign uploads with an upload key.
Make one on your computer and keep the file and passwords somewhere safe, such as a password
manager. If they're lost, Play can reset the upload key, but it takes a few days.

```sh
keytool -genkeypair -v -keystore pane-upload.jks -alias pane-upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Add four repository secrets (GitHub › Settings › Secrets and variables › Actions):

| Secret | Value |
| --- | --- |
| `PANE_UPLOAD_KEYSTORE_B64` | output of `base64 -w0 pane-upload.jks` |
| `PANE_UPLOAD_STORE_PASSWORD` | the keystore password |
| `PANE_UPLOAD_KEY_ALIAS` | `pane-upload` |
| `PANE_UPLOAD_KEY_PASSWORD` | the key password |

The next CI run then signs the bundle, ready to upload.

## 2. App content answers

**Privacy policy URL:** https://github.com/xrenold/lawnchair/blob/metro/PRIVACY.md

**Data safety:** no data collected, no data shared. Everything Pane reads stays on the phone;
the one network request is the weather lookup (approximate location to Open-Meteo, not stored by
Pane, no identifier), sent over HTTPS. There are no accounts, so no deletion request flow is
needed.

**Sensitive permissions and APIs:**

- `QUERY_ALL_PACKAGES`: declare the use case "Device search / launcher". Pane is a home screen
  app and lists every installed app.
- Notification listener: not a declared permission, but explain it in the listing and in the app
  (the setup screen does). Used only to show notifications on tiles.
- `PACKAGE_USAGE_STATS` (usage access): granted by the user in system settings, used for auto
  layout. Mention it in the description.
- Location: approximate (coarse) only, foreground only, for weather. No background location.
- Photos: the Play build declares no photo permissions; it uses the system photo picker.

**Ads:** no. **Target audience:** 18+ is simplest (the app isn't for children).

## 3. Store listing

- Name: **Pane**. Short description idea: "A live-tile Start screen for Android."
- Don't use "Windows", "Windows Phone", "Metro" or "Live Tiles" as names or in the title; Play
  treats them as another company's trademarks. "Inspired by classic tile interfaces" is fine.
- Graphics: 512×512 icon (the cluster mark), a 1024×500 feature graphic, and 2–8 phone
  screenshots.

## 4. Testing before release

New personal developer accounts must run a closed test with at least 12 testers who stay opted
in for 14 days in a row before the app can be published to everyone. Start this early.

## Notes

- The GitHub build and the Play build share the package name `app.pane.launcher`. Android only
  installs one over the other when they're signed with the same key, so switching between them
  means uninstalling first (back up Start first: Settings › Backup & restore).
- Every CI run raises the version code, so each bundle can be uploaded.
