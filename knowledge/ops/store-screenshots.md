---
type: Runbook
title: Store and README screenshot cards
description: How the six store / README cards are made - demo data on the test server, two emulators (one per account), clean status bar, per-scene settings and navigation, the capture command, and compose.py.
tags: [ops, screenshots, store, readme, emulator, prosody, runbook]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-24T19:00:00Z }
sources:
  - id: compose
    resource: ../../tools/store-screenshots/
    title: compose.py, make_media.py and their README
  - id: seed
    resource: ../../tools/test-server/seed_showcase.py
    title: Showcase demo data (accounts, roster, groups, history, presence)
  - id: testenv
    resource: ./test-environment.md
    title: Emulators and the Prosody test server
---

Six cards (1080x1920) replace the four AI-redrawn README cards and the store screenshots. Each
is a real emulator capture laid on the "Federation" background by
[`tools/store-screenshots/compose.py`](../../tools/store-screenshots/README.md). Card texts and
order were chosen by the owner (2026-11-24); they live in the `CARDS` table of `compose.py`.

| # | Card (kicker / headline) | Scene | Device | Style, theme |
|---|---|---|---|---|
| 1 | ORGANISED / Keep your chats in order | Chat list, floating bars and dock, one contact typing | A (hayk) | Slate and Sea, light |
| 2 | ENCRYPTED BY DEFAULT / Talk freely, privately | Chat with Ani, OMEMO (lock icons), pattern wallpaper | A | Glass, dark |
| 3 | GROUPS / Bring everyone together | Group "Family": names, avatars, mention, photo, reactions | A | Slate and Sea, dark |
| 4 | YOUR STYLE / Make it yours | Settings → Appearance: style cards, accents, Floating bars switch | A | Tinted Glass, dark |
| 5 | OPEN NETWORK / No phone number. Any server. | Welcome screen before sign-in | B (before ani logs in) | Slate and Sea, light |
| 6 | COMPATIBLE / Conversations and monocles backups | Backup import list showing a Conversations and a monocles chat backup | B (before ani logs in) | Slate and Sea, light |

Capture `NN.png` is card `NN`. Keep the raw captures outside the repository (a scratch folder).

The screenshots stay screenshots; the feature list is the F-Droid feature graphic
(`fastlane/metadata/android/en-US/images/featureGraphic.png`, 1024x500, shown above the
screenshots): icon, name, tagline and four lines on the same background (owner, 2026-11-24, over
a text card in place of the first screenshot). It needs no captures; `compose.py` without
arguments writes only it.

# Accounts and demo data

The showcase uses its own account `hayk@10.0.2.2` ("Hayk Arakelyan"), not `tester`: tester's
archive holds days of test traffic ("Performance message N", "secret case1 EDITED") that a fresh
install pulls in by MAM catch-up. Contacts (password `secret123` for all): `ani` (Ani Petrosyan,
second emulator), `aram`, `davit`, `lilit`, `karen`, `narek`, `sona`, `gor`, `mom`. Groups
"Family" (owner mom; lilit, gor, narek, hayk) and "Dev team" (owner karen; davit, sona, aram,
hayk): members-only, non-anonymous, archived. Generated avatars for ani, aram, lilit, gor and
karen; the others keep the app's letter avatars. Names, texts and times are the tables at the
top of `seed_showcase.py`.

```sh
tools/test-server/seed_showcase.sh setup     # idempotent: accounts, nicks, avatars, roster (both ways), rooms, bookmarks
tools/test-server/seed_showcase.sh history --now 09:00 --tz +0400   # only with hayk online in the app, see below
tools/test-server/seed_showcase.sh online --minutes 40 --typing aram  # keeps aram, lilit, sona, mom online
tools/test-server/seed_showcase.sh reset     # destroys the rooms (and their archive), deletes the showcase accounts
```

- **Why `history` needs the app online.** The app keeps a sender's `<delay stamp>` only on a
  live message (`MessageParser`: a MAM result is dated by the archive's own stamp). The seed
  sends every message with a delay of "today 07:45", "yesterday 20:05" and so on, so hayk must
  be logged in on emulator A and joined to both groups (autojoin bookmarks) while it runs;
  `history` checks both and stops otherwise. Times are local to `--tz` and must be before
  `--now` (the app clamps a stamp later than its clock to "now").
- `history` skips a chat or group whose archive already has messages, so it is safe to rerun;
  to start over, `reset` then `setup`, and reinstall the app on both emulators.
- A destroyed room leaves a Prosody tombstone for 31 days, so after a `reset` `setup` creates
  `family-2@…`, `family-3@…` and so on; the other commands find the rooms through hayk's
  bookmarks. Only the room name ("Family") shows in the app.
- `online` stays up for `--minutes`; start it right before cards 1-3 (in the background of
  your shell tool, it ends by itself), so the list shows online dots and "typing…" in Aram's row.

Server-side checks without an emulator (2026-11-24): roster `both` on both sides for all nine
contacts, rooms report `muc_membersonly`, `muc_nonanonymous`, `muc_persistent`,
`urn:xmpp:mam:2`; a script client logged in as hayk received every seeded message live with
its delay stamp (chats, carbons of hayk's own messages, groups), the reply with
`urn:xmpp:reply:0`, the reactions and the read markers; the photo uploaded through
`upload.10.0.2.2`.

# Emulators

Follow [Keeping agent emulator checks cheap](test-environment.md#keeping-agent-emulator-checks-cheap):
captures are saved to files and opened only downscaled or cropped; find buttons with
`uiautomator dump` + `grep`. Check `free -m` first (at most three emulators and one build).
SDK path, AVD folder and serials of this machine are in the agent memory, not in git: check them
there (or ask the owner) and set `ADB`, `A` and `B` accordingly.

- **A**: an API 37 AVD with a Google APIs image (`adb root` works), account hayk.
- **B**: a second API 37 AVD, account ani.

Start both **with audio** (no `-no-audio`: the voice message needs it):

```sh
emulator -avd <AVD A> -no-snapshot-save -no-boot-anim &
emulator -avd <AVD B> -no-snapshot-save -no-boot-anim &
```

The AVDs boot from an old quick-boot snapshot (an old app install): uninstall and install the
current debug APK (`build/outputs/apk/conversationsFree/debug/*x86_64.apk`) on both, so every
start is clean. On both: gesture navigation (the cards show the gesture bar)
`$ADB -s $X shell cmd overlay enable com.android.internal.systemui.navbar.gestural`, and
`dumpsys deviceidle whitelist +am.cyberhayq.chat`.


## Clock and clean status bar

The cards show 09:30. Set A's real clock to 09:00 today so the live OMEMO chat with Ani is dated
09:0x-09:2x, after the seeded "today" messages and before the status bar's 09:30:

```sh
$ADB -s $A root && $ADB -s $A wait-for-device
$ADB -s $A shell settings put global auto_time 0
$ADB -s $A shell date "$(date +%m%d)0900$(date +%Y).00"
$ADB -s $A unroot
$ADB -s $A shell date +%z        # the --tz for history, e.g. +0400
```

If the clock cannot be set, leave it and pass the real time as `--now`, and use that time
(`hhmm`) for the demo clock below instead of 0930.

Demo mode, on A before each capture (and on B for cards 5 and 6):

```sh
$ADB -s $X shell settings put global sysui_demo_allowed 1
for c in "clock -e hhmm 0930" "battery -e level 100 -e plugged false" \
         "network -e wifi show -e level 4 -e fully true -e mobile show -e datatype none -e level 4" \
         "notifications -e visible false" "status -e bluetooth hide -e alarm hide -e volume hide"; do
  $ADB -s $X shell am broadcast -a com.android.systemui.demo -e command $c
done
$ADB -s $X shell am broadcast -a com.android.systemui.demo -e command enter
```

Exit with `-e command exit`; afterwards `settings put global auto_time 1` on A.

On API 37 `notifications -e visible false` does not hide notification icons (capture run of
2026-11-24): clear the shade ("Clear all") and, on A, hide Tatrak's own foreground-service icon
with `pm revoke am.cyberhayq.chat android.permission.POST_NOTIFICATIONS` (the app restarts;
answer "Don't allow"), and `pm grant` it back at the end. Without `-e fully true` the network
icons carry a "!"; the run of 2026-11-24 still showed "3G" and a half signal.

# Order of work

1. `seed_showcase.sh setup` (once; rerun is harmless).
2. **Card 5 on B**, before ani logs in. Install, then before the first launch give the app the
   Slate and Sea style and light theme through its debug-build preferences:
   ```sh
   printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' \
     '<string name="tatrak_style">slate_sea</string>' '<string name="theme">light</string>' '</map>' > prefs.xml
   $ADB -s $B exec-in "run-as am.cyberhayq.chat sh -c 'mkdir -p shared_prefs && cat > shared_prefs/am.cyberhayq.chat_preferences.xml'" < prefs.xml
   ```
   Launch, answer "How would you like to start?" with Easy, wait for the welcome screen, demo
   mode, capture `05.png`. If the welcome screen does
   not follow the style, capture it as it is and say so in the report.
   **Card 6** (still before ani logs in to Tatrak): see [backups of the other apps](#card-6-backups-of-the-other-apps).
3. **Log in** hayk on A and ani on B ([logging the app in](test-environment.md#logging-the-app-in):
   "I already have an account", certificate → Always, LAN permission on API 37). Skip the avatar
   step on A (hayk has none). On A, open the chat list once so both groups autojoin.
4. Set A's clock (above), then `seed_showcase.sh history --now 09:00 --tz <A's offset>`.
5. **Ani's chat**, typed on the two emulators (below), so it is OMEMO-encrypted.
6. `seed_showcase.sh online --minutes 40 --typing aram` in the background, then cards 1-4 on A.
7. `compose.py` (below), look at the six cards downscaled, then replace the committed images.

## Ani's chat (OMEMO, typed live)

Push the generated photo to B first:
`python3 -I tools/store-screenshots/make_media.py <scratch>/media`, then
`$ADB -s $B push <scratch>/media/photos/balcony.jpg /sdcard/Pictures/`.
Both accounts are new, so OMEMO keys are trusted blindly before verification and the composer
reads "Send encrypted message"; if it does not, choose OMEMO in the classic view's lock menu.
Wait a few seconds between messages so they arrive in order. `adb shell input text` cannot type
emoji and Gboard offers only its suggestions, so lines whose emoji cannot be entered go without
it. A caption typed with an attachment selected is sent as a separate message after the photo.
Reactions and replies come from the message menu (tap the bubble's edge); a right swipe closes
the chat.

| # | Who | What |
|---|---|---|
| 1 | Ani (B) | Good morning! ☀️ Are we still going to the exhibition today? |
| 2 | Hayk (A) | Morning! Of course, the tickets are for 12:00 🎟️ |
| 3 | Ani | Photo `balcony.jpg` (attach → File → Pictures) with the caption: The view from my balcony right now 🌄 |
| 4 | Hayk | Reaction ❤️ on the photo |
| 5 | Ani | Should I bring an umbrella? They say it might rain later |
| 6 | Hayk | Reply to 5 (message menu → Reply): Yes, better take it ☔ I'll wait for you at the entrance |
| 7 | Ani | A voice message of about 3 s (hold the microphone button); skip it if recording fails |
| 8 | Ani | Perfect, see you there! 🤗 |

On A, tap the photo's download button if it is not fetched by itself. Nothing test-like:
no "test", no numbering, no failed or pending states on screen.

## Card 6: backups of the other apps

The card shows Tatrak's backup import list with one backup made by Conversations and one by
monocles chat (each row names the app that wrote it, `BackupFileAdapter` shows the header's app).
The list skips backups of accounts already in Tatrak, so this runs on B before ani logs in.

1. On B install the F-Droid builds of Conversations and monocles chat (the versions of the
   interop check or newer; download the APKs from the F-Droid
   repository and check them with `apksigner verify`).
2. Log each in as `ani@10.0.2.2` / `secret123` (accept the certificate), let it fetch the
   history, then create a backup in each (Settings → Backup → Create backup; the password is
   the account password).
   monocles asks "PQ OMEMO2 / Legacy" (Legacy) at first login and "Disable all accounts" (Yes)
   when it makes the backup.
3. Put both files in one folder, for example `adb shell mkdir -p /sdcard/Download/Backups` and
   `cp` the two `.ceb` files there. API 37 scoped storage hides other apps' files from Tatrak and
   the welcome screen's import offers only "Open backup" (one file), so: log Tatrak in with a
   temporary account (`tester`), Settings → Backup → Backup location → choose that folder, then
   Restore backup lists both files.
4. Demo mode, capture `06.png` with both rows visible.
5. Uninstall Conversations and monocles chat, `pm clear am.cyberhayq.chat` (the folder grant goes
   with it), whitelist the app in deviceidle again, then log ani in to Tatrak as below. Their
   OMEMO devices stay in ani's device list; that does not change what A shows.

The rows show `ani@10.0.2.2` (the test server's domain); there is no other domain on the test
server. If the list does not show the monocles backup, stop and report instead of capturing a
different screen.

## Scenes on A

Settings → Appearance holds Style (cards: Telegram, Slate and Sea, Glass, Warm Glass, Tinted
Glass), Floating bars, Theme, Accent colour and Wallpaper. Set each scene's values there before
navigating to it; capture with demo mode on:

```sh
$ADB -s $A exec-out screencap -p > <captures>/NN.png
```

1. **Chat list**: Slate and Sea, theme Light, Floating bars on. Chats tab, scrolled to the top,
   no search, Aram's row showing "typing…" (`online --typing aram` running). Expect Ani at the
   top, then Family, Aram, Dev team, Lilit, Karen, Mom, Sona, Narek, Gor.
2. **Chat with Ani**: Glass, Dark, wallpaper with a pattern (Wallpaper → Pattern → Sparkles,
   Leaves or Bubbles and hearts; pick what reads best behind dark bubbles). Open Ani's chat,
   scroll to the bottom so the photo, the reply and the last message show; keyboard hidden.
3. **Family**: Slate and Sea, Dark. Open Family; scroll so the photo with its reactions, Gor's
   reply and Lilit's message to Hayk (the mention) are on screen. Open it once and go back
   first if the "Unread messages" divider covers them.
4. **Appearance**: Tinted Glass, Dark, Floating bars on. Settings → Appearance, scrolled so the
   style cards, the Floating bars switch and Blur under bars show (the accent colours sit below
   Theme, Night background and Avatar shape and do not fit on the same screen).

# Compose and replace

```sh
python3 -I tools/store-screenshots/compose.py <captures> --out <scratch>/cards   # trial
magick montage <scratch>/cards/fastlane/metadata/android/en-US/images/phoneScreenshots/*.png -tile 6x1 -geometry 360x640+6+0 <scratch>/row.png
python3 -I tools/store-screenshots/compose.py <captures>                         # into the repo
```

The final run writes `fastlane/metadata/android/en-US/images/phoneScreenshots/01.png` …
`06.png`; `README.md` shows these files, so the cards are stored once (2026-11-25; the first
commit also kept a copy in `docs/images`, about 10 MB). Update the image rows in `README.md` (two rows of three images at 200 px, with new alt
texts) and `CHANGELOG.md` in the same
change. Leave demo mode and restore A's `auto_time` at the end.

# Known gaps

- No call card (owner, 2026-11-25): a call between two emulators does not connect. On
  2026-11-24, with `-shared-net-id`, it went Ringing → Connecting → "Could not connect call"
  after about 12 s; only host candidates 10.0.2.15 and fec0::… were offered, no 10.1.2.x. A
  connected call needs a TURN relay on the test server.
- `send.py --file` uploads to `https://10.0.2.2:5281`, which inside the current container is
  refused (10.0.2.2 is the host there, and the port is published on 127.0.0.1 only);
  `seed_showcase.py` connects to 127.0.0.1 and checks the certificate against 10.0.2.2.
