---
type: Concept
title: Theming and accent colours
description: How Tatrak colours both the Compose UI and the classic XML screens from nine accent presets, the style (Telegram, Slate and Sea) and the night background (blue-grey, graphite, AMOLED), with a contrast check of every pair.
tags: [ui, theme, compose, accent]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-15T15:00:00Z }
sources:
  - id: accents
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/theme/Accents.kt
    title: Accent presets and resolution
  - id: generator
    resource: ../../art/tatrak/gen_accents.py
    title: Accent colour generator
  - id: night
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/theme/NightBackground.kt
    title: Night background setting
  - id: contrast
    resource: ../../art/tatrak/check_contrast.py
    title: Contrast check of every accent on every surface
---

# One source for both UIs

- Nine accents, all ordinary (owner, 2026-11-15): blue (default), cyan, sea, green, violet, pink,
  orange, red, gold, in that picker order; setting `tatrak_accent` (`AppSettings.ACCENT`). The
  signature themes (Ember, Steel, Crimson, Teal, Forest, Copper) and the editor palettes (One Dark,
  Dracula) were removed on 2026-11-15 without migration (no installs): `Accent.of` gives blue for
  their stored keys. Gold is an ordinary accent now. An accent only colours; the surfaces come from
  the style and the night background (below).
- Colours are resources in `res/values{,-night}/tatrak_accent_colors.xml`, generated from one
  seed per accent by `art/tatrak/gen_accents.py` (re-run after changing a seed; never edit the
  outputs). Each accent has a day and a night set, the night one lighter. Blue and Sea start from
  hand-picked colours (blue = the Compose colours the app shipped with); a contrast fit pass then
  changes only the slots that fail (below), so blue's day primary is now `247BB8` (was `298ACF`).
- `res/values/tatrak_accent_themes.xml`: `ThemeOverlay.Tatrak` (neutral Telegram day / night
  surfaces), the night-background overlays (below) and `ThemeOverlay.Tatrak.Accent.<Name>`
  (Material3 primary / secondary / tertiary families plus custom attrs `tatrakLink`,
  `tatrakBubbleOutgoing`, … from `tatrak_attrs.xml`; no surfaces).
- XML screens: `BaseActivity.onCreate` applies the overlays before `super` unless dynamic
  colours are on, and recreates in `onStart` when the accent, style or night background changed
  (like night mode).
- Wallpapers (`ui/chat/ChatWallpaper.kt`, `Wallpapers`): spec `default`, `preset:<id>` (nine
  presets, solid or two-colour gradient, each with a hand-picked night variant) or
  `photo:<file>` (picked picture scaled to ≤ 1920 px JPEG in `files/wallpapers`, dimmed at
  night by `AppSettings.WALLPAPER_DIM`, default 35 %, slider 0–80 % under the photo tiles).
  After a change `Wallpapers.deleteUnused` removes photo files that neither the app-wide setting
  nor any open or archived chat names (stage 7a). App-wide `AppSettings.WALLPAPER`, per chat `Conversation.tatrakWallpaper` (null =
  app-wide), chosen from the chat menu → Wallpaper sheet. Date chips are tinted from preset
  wallpapers (`LocalWallpaperTint`).
- Wallpaper patterns (2026-11-05): a preset spec may end in `#<pattern>` (`WallpaperPattern`:
  `sparkles`, `leaves`, `bubbles`; e.g. `preset:sky#leaves`, `default#bubbles`). Our own shapes,
  drawn with `android.graphics.Path` into one 110 dp tile (black 12 % by day, white 9 % at night)
  and repeated by an `ImageShader`, so a frame costs one shader draw. The sheet shows a "Pattern"
  row under the colours when the current wallpaper is a preset; picking another colour keeps the
  pattern. Photos have no pattern.
- Settings (stage 6c): `SettingsActivity` starts on `SettingsHomeFragment` (Compose, account
  header + grouped rows into the unchanged preference pages; `MainSettingsFragment` is no
  longer used but kept for upstream merges). `AppearanceFragment`: theme mode (existing
  `theme` setting and arrays), dynamic colours, accent, app-wide wallpaper with a live preview.
- Night background (owner, 2026-11-15; `NightBackground.kt`, preference `tatrak_night_background`:
  `blue_grey` default, `graphite`, `amoled`; unset or unknown = blue-grey). A plain preference read by
  key like `TatrakStyle`, `AppSettings` untouched. It replaces the old opt-in "elegant dark" switch
  (`tatrak_elegant_dark`, removed without migration) and the houses' own surfaces. Only the night
  changes; each style keeps one day set. Mapping (`neutralsOf(style, dark, night)` in the generated
  `ui/theme/Neutrals.kt`):

  | Night background | Telegram style | Slate and Sea |
  |---|---|---|
  | blue-grey | default night of `Theme.kt` (no neutrals) | `slateNight` (`SLATE_D`) |
  | graphite | `graphiteNight` (`GRAPHITE`: list `121214`, top bar `1A1A1D`, chat `0B0B0D`, incoming `1E1E22`, text `ECE9E6`) | `slateGraphiteNight` (`SLATE_GRAPHITE`: `111315`, `181B1E`, `0B0C0E`, `1E2124`, `E8EBED`) |
  | AMOLED black | `amoledNight` (`AMOLED`) | `slateAmoledNight` (the same set plus Slate's online green `4CC38A`) |

  AMOLED: background, surface and chat background `000000`; top bar `121212`; incoming bubble,
  surface variant, sheets / menus (`container`), drawer header and unread divider `1A1A1A`;
  highest container `262626`; dividers (`outline_variant`) `2E2E2E`; outline `666666` (3.7:1 on
  black); archive `3A3A3A`; text `E8E8E8`, muted `A0A0A0`, incoming time `999999`; date chip
  `CC262626`. Slate and Sea did not get a slate-tinted AMOLED set: every pair passes with the
  neutral one and on pure black a tint would only show in the raised layers.
  XML: `NightBackground.overlay(style)` names the overlay `BaseActivity` applies between
  `ThemeOverlay.Tatrak` and the accent: Telegram `0` (none) / `ThemeOverlay.Tatrak.Graphite` /
  `ThemeOverlay.Tatrak.Amoled`; Slate `ThemeOverlay.Tatrak.SlateSea` / `.SlateSea.Graphite` /
  `.SlateSea.Amoled`. Each equals its style's day neutrals by day, so the activity needs no
  night-mode check. Every overlay, the base `ThemeOverlay.Tatrak` included, also sets
  `colorSurfaceContainerLowest` .. `Highest` and `colorOutlineVariant` the way
  `ColorScheme.withNeutrals` maps them in Compose (Low = background, High = container), so classic
  Material dialogs and sheets get the same surfaces as Compose (before 2026-11-15 they inherited
  Material's `2B2930`); `NightOverlayTest` compares the resolved attributes with `neutralsOf`. With dynamic colours the setting is hidden in Appearance and not applied (its
  value is kept), as the elegant switch was.
- Contrast (2026-11-15, `python3 art/tatrak/check_contrast.py`, `-v` for every pair; exit 1 on a
  failure): 9 accents x {Telegram, Slate and Sea} x {day, blue-grey, graphite, AMOLED}, 1541
  pairs, all pass. Per surface set: text on background / top bar / incoming bubble / chat background
  / sheets / highest container / surface variant (4.5), muted text on the same except the bare chat
  background (4.5), top-bar muted, incoming time, warning bubble (4.5); for the generated sets also
  outline (3), date chip, drawer header (4.5) and the archive icon (3). Per accent: text, time and
  link on the outgoing bubble, link on background and incoming bubble, button text on primary (4.5);
  primary on background, badge text, glyphs on primary / FAB (secondary) / round file buttons,
  read check, `accent_in` / `accent_out` (3); the Telegram day drawer header on `primary_container`
  (4.5); reaction chips: the count on an own chip (`contentOn` on `accent_in` / `accent_out`) and on
  another's chip (the accent on itself at 14 % over the bubble), 3 as badge counts; Slate dock,
  every accent: selected label (`link`) on the top bar (4.5) and selected icon on its 14 % pill (3). The generator's fit pass makes them pass by construction (each slot is nudged away from
  the worst surface of the mode, in both styles and all night backgrounds); the script checks
  independently. Rules the fit pass applies:
  - Day `on_primary`: white, with the primary darkened until white reaches 4.5; light seeds (white
    below 3:1 on the seed: cyan, orange, gold) keep their hue and take dark text `1A1A1A` instead.
    Night `on_primary`: dark text on every lightened primary (Sea keeps its own `0B1F22`).
  - Day FAB (`secondary`) and badge under a white glyph are darkened to 3:1 for white.
  - `link` is the accent as text: fitted on backgrounds, bubbles and every top bar (4.5) and on its
    own 14 % pill (3). The Slate dock draws its selected item in `link`, not `primary` (Gold by day
    was 3.31:1 as primary); own reaction counts use `contentOn(accent)` instead of fixed white
    (Gold at night was 1.82:1).
  - Informational, not a failure: the primary used as text (text buttons, section titles) is 3.0
    to 4.0 on day surfaces for most accents (the script prints the list); the plan asks 3:1 for
    primary on background.
- Telegram day / blue-grey neutrals adjusted by the check (they live in `Theme.kt`, mirrored as
  `TELEGRAM_L` / `TELEGRAM_D` in the generator, keep both in sync): day muted text `75787A` ->
  `6C6E70`, day top-bar muted `848688` -> `747678`, day incoming time `A1AAB3` -> `71777D`, night
  muted `7F8B96` -> `96A0A9`, night incoming time `6D7F8F` -> `7D8D9B`; XML `on_surface_variant` day
  `6C6E70`, night `93A0AC`; graphite outline `3A3A3E` -> `616165` (was 1.7:1).
- The Appearance picker shows the nine accents as 48 x 48 dp touch targets (40 dp circles, 4 dp
  apart) in a `FlowRow`: one line where it fits (about 500 dp), two lines on a phone (6 + 3 at
  360 dp); the selected circle with a ring and a check, radio role and label per target. "Night background" (three radio rows) follows the theme radios and the dynamic-colours
  switch; it is hidden while dynamic colours are on. The style cards preview the chosen night.
- Compose: `TatrakTheme(accent = rememberAccent())` reads the same overlay through
  `resolveAccent` (a `ContextThemeWrapper` with the wanted night mode) and updates live via a
  SharedPreferences listener. Accent slots change; neutrals follow the style and the night background
  (`TatrakTheme(style, nightBackground)`).
  With dynamic colours on (API 31+, stage 7a) `TatrakTheme` takes the accent slots from
  `dynamicLight/DarkColorScheme` instead (`dynamicAccent`). Only the accent follows the wallpaper:
  Compose keeps the Telegram neutrals, and since 2026-11-05 `BaseActivity` applies
  `ThemeOverlay.Tatrak` (neutrals only) under dynamic colours too, so XML screens match; since
  2026-11-15 that includes `colorSurfaceContainer*` (dialogs and bottom sheets lost the wallpaper
  tint and use the Telegram containers, as Compose does).
- Glyphs on accent fills (unread badges, folder-tab counters, scroll-down counter, round file
  buttons in bubbles, swatches, saved-messages avatar) use `contentOn(fill)`: near-black `1A1A1A` on
  a fill with luminance > 0.30, white otherwise (was 0.45 until 2026-11-15). At 0.30 any fill
  reaches 3:1 (white at least 3.0 below, near-black at least 5.8 above); `glyph()` in the generator
  mirrors it. Effect: the night accents, cyan / orange / gold swatches and the grey muted badges
  take dark glyphs; blue's day badge `229AF0` keeps white (3.0).
  The chat list FAB (owner, 2026-11-15) instead has a white pencil on `fabFill(primary)`: the
  primary darkened until white reaches 4.5:1 (most day primaries unchanged; night blue `447AA9`).

## Slate and Sea style (2026-11-03)

Setting `tatrak_style` (`telegram` default, `slate_sea`), read by key in `ui/theme/TatrakStyle.kt` (not in
`AppSettings`). Design: design_draft.

- Precedence: dynamic colours (neutrals dropped in `TatrakTheme`), then `neutralsOf(style, dark,
  night)` (generated): Slate's day set by day, the night background's set of the style at night
  (table above). XML: `ThemeOverlay.Tatrak.SlateSea` or its graphite / AMOLED variant is applied in
  `BaseActivity` before the accent.
- Sea accent (`0B6F7A`; night `5CC4CE` with dark text, outgoing bubble day `CFE9EC`, night `12414A`).
  The stored accent is never changed: `TatrakStyle.effectiveAccent` gives Sea only while the key is unset
  and the style is Slate and Sea (Compose `rememberAccent` and `BaseActivity` both use it).
- Avatars: `LocalAvatarShape` (circle, or rounded square 36%) used by `LetterAvatar`, `ChatAvatar`, the
  archive avatar; the online dot / check moves 3 dp into the corner (`LocalAvatarDotOffset`). Bubbles,
  badges, FAB, icon circles stay round / unchanged. Shapes also apply with dynamic colours.
  Since 2026-11-15 the shape is its own Appearance setting (`AvatarShape`, preference
  `tatrak_avatar_shape`: round / rounded square, `TatrakStyle.kt`); while it was never chosen it
  follows the style (Telegram round, Slate and Sea square), a stored choice wins in either style.
- Appearance: a Style section (cards), a note under dynamic colours. Unread-divider override now
  applies by day too (`ChatScreenTheme`).
- Palette (day / night) mapped to `Neutrals`: background and surface `EEF1F4` / `0F1316`; top bar, incoming
  bubble, sheets `F8FAFB` / `171C20` (bubble `1F262B`); chat background `E3E9EE` / `0F1316`; text and
  on-top-bar `14191F` / `E6EAEE`; muted, time and on-top-bar-muted `56616C` / `98A3AD`; variant `E3E9EE` / `1F262B`;
  highest container and outline-variant `DCE2E8` / `2A323A`,`232B31`; outline `7C8791` / `5E6B76`; archive and swipe
  `7F8C98`,`768390` / `3A444D`; drawer header `2B3A44` / `1F262B`; unread divider `F8FAFB` / `1F262B`;
  date chip `B326323B` / `CC1F262B`; online `2FA85A` / `4CC38A`. Source: `SLATE_L`, `SLATE_D` in `art/tatrak/gen_accents.py`.
- Contrast (2026-11-04 Slate-only run; since 2026-11-15 the script covers every accent and night, above):
  `python3 art/tatrak/check_contrast.py` (reuses `cr()` of the generator; 4.5 text, 3 icons).
  Result: all pairs pass (body and muted text on every Slate surface 4.8 to 16.9, link 5.4 to 11.2, time on
  outgoing 6.0 / 6.6, badge and button text 5.9 / 8.3, date chip 5.6 / 16.1, check marks 4.6 and up, outline 3.2 / 3+).
  First run failed three pairs, fixed: white icon on archive / swipe by day (1.9 / 2.5, now darker), night
  outline (1.9), and the warning bubble text by day (3.77; `warningText` darkened to `8F5600`, which also
  changes the Telegram style slightly). Not a failure but worth knowing: other accents keep their own link /
  time colours on slate surfaces (cyan, green, pink, orange links are 3.0 to 4.0 on the incoming bubble, as in Telegram).

## Slate and Sea phase 2 (2026-11-04)

`TatrakTheme` provides `LocalTatrakStyle` and `LocalTatrakFont` (Manrope family in Slate, null otherwise); composables read the locals, never the preference. Slate-only branches (the Telegram code path is the old code verbatim): chat list `bottomBar` with `ChatListBottomBar` (floating search pill, compose button, dock of four shortcuts, hidden by `dockVisible` while searching, in the archive or selecting; no FAB; no top-bar search icon), `SlateComposer` in `Composer.kt` (pill over the wallpaper, insets outside the pill, reply or edit and emoji panels as separate blocks, recording halo and mic ring), `BubbleShape(soft = true)` with `bubbleRadii` (20 dp and 6 dp, no tail), a `Typography` with only the font family changed, `StyleCards` in Appearance (fixed preview colours mirroring the generator). The font is applied explicitly to `BodyStyle`, `MetaStyle` and the composer `BasicTextField`; XML screens keep the system font. Font facts: Manrope.

