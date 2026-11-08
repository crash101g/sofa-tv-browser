# About this fork

This is a fork of [Sofa TV Browser](https://github.com/sonalder-darlene/sofa-tv-browser) with features from
[TVCouchBrowser](https://github.com/crash101g/tvcouchbrowser) added. Everything else is Sofa as upstream ships it,
and the fork follows upstream's new versions (see [Keeping up with Sofa](#keeping-up-with-sofa)).

It is **unofficial**: Sofa's developer doesn't make or support it. It shows as **Sofa (fork)** on the TV, says so in
Settings → About, is signed with its own key and updates only from
[this fork's releases](https://github.com/crash101g/sofa-tv-browser/releases). Report problems with it
[here](https://github.com/crash101g/sofa-tv-browser/issues).

**Tested on:** the Walmart onn 4K Pro Streaming Device (board jarvis2) and the TCL 40Q3K.

## What it adds

### Your phone as a remote, keyboard and trackpad

Menu → **Phone** shows a QR code. Scan it with the phone's camera and a page served by the TV opens: open a website
on the TV, type into the field that has focus there, use a D-pad remote, and use a **trackpad**. Nothing to install.

| On the trackpad | What it does |
|---|---|
| Drag one finger | Moves Sofa's pointer (faster flicks go further; **Speed** below the pad) |
| Tap | Click (a real touch, which captchas and press-and-hold buttons need) |
| Hold still, then drag | Press and drag: sliders, slide-to-unlock captchas, maps |
| Hold still, then let go | Long press: the link or image menu, or the page's own long press |
| Two fingers: drag / pinch / tap | Scroll what is under the pointer / zoom / long press |
| **Hold** button | Pressed for as long as your finger stays on it |
| **✂ Remove** / **🧹 Clean up** | Remove the element under the pointer / clean up the page |

The pointer shows as soon as the trackpad is touched, also on sites where the pointer is off ("Pointer: off" in the
menu). Away from the page (home screen, menu, dialogs, fullscreen video) the trackpad works like the D-pad: moves step
through the buttons and a tap presses OK.

Security, as in TVCouchBrowser:

- Off until the QR code is first shown; then it runs only while Sofa's browser screen is open.
- Every request needs a 128-bit random token, checked in constant time, stored encrypted with an Android Keystore key.
- Only devices on the TV's own home network (private or link-local addresses on its subnet) can connect; 5 wrong
  tokens from one address block it for 10 minutes. The trackpad socket checks the page's origin, allows two
  connections and drops messages past 120 a second.
- The endpoints take only an address (http/https), text or a key name; never script.
- **Forget paired phones** in the QR code dialog makes a new token and turns the phone page off again.
- It is plain HTTP on your home network. Don't send passwords through it on a network you don't trust.

The `/open?t=…` address in the dialog works with an iPhone "Send to TV" Shortcut; see TVCouchBrowser's
[docs/send-to-tv-shortcut.md](https://github.com/crash101g/tvcouchbrowser/blob/main/docs/send-to-tv-shortcut.md).

### Blocking what the lists miss

Menu → **Clean up**:

- **Clean up page** removes full-screen overlays, cookie and newsletter walls and sticky ad bars, and makes a
  scroll-locked or blurred page usable again. It keeps the site's own header and navigation and anything holding a
  video.
- **Remove element**: point at the thing and press OK (Back cancels), or tap **✂ Remove** on the phone. Sofa
  remembers it for the site when it can name it safely; **Bring back removed elements** undoes that.
- **Clean up this site automatically** runs Clean up page on every load of the site.

### More ad blocking

- **Extra filter lists** (Settings): uBlock Origin's own lists (filters, privacy, badware, quick fixes, unbreak),
  AdGuard Popups and Peter Lowe's list in one switch, next to Sofa's "Stronger ad blocking". They block mostly whole
  servers, which is cheap in memory. Off by default; downloaded from their publishers when switched on. uBlock's
  cookie-notice and annoyance lists are in Settings → Filter lists too, off, as they are mostly costly element hiding.
- **YouTube**: the ad entries are taken out of the player's data before the player reads them, as uBlock Origin's
  scriptlets do. YouTube may change this or detect it. Only while ad blocking is on for YouTube.
- **Captchas are never blocked** (reCAPTCHA, hCaptcha, Cloudflare Turnstile, Arkose), so sites behind them open.
- **Dialog loops**: a page gets two alert, confirm or prompt boxes; more are dismissed. "Leave this page?" never
  traps Back.

Sofa already had the rest of what TVCouchBrowser does (pop-up blocking, element hiding, no `wv` in the user agent,
daily list updates), with its own, fuller filter engine, so those parts were not ported.

## Keeping up with Sofa

This repository has two remotes: `origin` is this fork, `upstream` is Sofa. When Sofa releases a new version:

    git fetch upstream
    git checkout main
    git merge upstream/main
    git push origin main

Or press **Sync fork** on GitHub, or run `gh repo sync <you>/sofa-tv-browser`. To see what is new first:
`git log main..upstream/main --oneline`.

The fork is built to merge cleanly: the new code is in files of its own (`phone/`, `adblock/PageTools.kt`,
`adblock/CleanUp.kt`, `adblock/RemovedElements.kt`, `adblock/Challenges.kt`, `assets/phone.html`,
`res/values/strings_fork.xml`), and Sofa's own files have only small hooks into it: `MainActivity`, `Browser`,
`CursorLayout`, `BrowserMenu`, `SettingsActivity`, `AdBlocker`, `FilterList`, two tests, `app/build.gradle.kts`,
`NOTICE.md` and the README's first lines.

**Updates inside the app**: the update checker still looks at Sofa's releases, and only offers them to copies signed
with Sofa's release key, so a build of this fork is never offered (or overwritten by) an upstream APK. The app id is
still `app.sofabrowser`, so Android won't install this fork over a Sofa from GitHub or F-Droid (the signatures
differ): uninstall that first, or change `applicationId` in `app/build.gradle.kts` to have both.

## Build

JDK 17 or newer and the Android SDK with platform 36 and build-tools 36.1.0:

    ./gradlew assembleStandardDebug    # app/build/outputs/apk/standard/debug/app-standard-debug.apk
    ./gradlew testStandardDebugUnitTest

## Releasing

Fork releases are numbered after the Sofa version they are built on: `1.2.4-tvcouch.2` is the fork's second release
on Sofa 1.2.4, and its versionCode is 1020402. The updater offers a higher fork number on the same Sofa version, and
any newer Sofa version.

Write the release's `## 1.2.4-tvcouch.2 (date)` section in CHANGELOG.md and its store changelog in
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, then:

    tools/release.sh 1.2.4-tvcouch.2 --dry-run   # builds and checks; the APK and notes land in build/release/
    tools/release.sh 1.2.4-tvcouch.2             # the same, then commits, tags, pushes and publishes the release

It needs `keystore.properties` and a logged-in `gh`, and stops before publishing if the APK has the wrong version,
isn't signed with the key the updater expects, or doesn't update from this fork.
