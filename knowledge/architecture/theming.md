---
type: Concept
title: Theming and accent colours
description: How Tatrak colours both the Compose UI and the classic XML screens from one set of accent presets.
tags: [ui, theme, compose, accent]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-31T01:00:00Z }
sources:
  - id: accents
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/theme/Accents.kt
    title: Accent presets and resolution
  - id: generator
    resource: ../../art/tatrak/gen_accents.py
    title: Accent colour generator
---

# One source for both UIs

- Fourteen presets: seven ordinary (blue default, cyan, green, violet, pink, orange, red) and seven signature themes (Ember, Steel, Crimson, Gold, Teal, Forest, Copper), setting
  `tatrak_accent` (`AppSettings.ACCENT`).
- Colours are resources in `res/values{,-night}/tatrak_accent_colors.xml`, generated from one
  seed per accent by `art/tatrak/gen_accents.py` (re-run after changing a seed). Blue keeps the
  exact Compose colours the app shipped with, so existing screenshots did not change.
- `res/values/tatrak_accent_themes.xml`: `ThemeOverlay.Tatrak` (neutral Telegram day / night
  surfaces) and `ThemeOverlay.Tatrak.Accent.<Name>` (Material3 primary / secondary / tertiary
  families plus custom attrs `tatrakLink`, `tatrakBubbleOutgoing`, … from `tatrak_attrs.xml`).
- XML screens: `BaseActivity.onCreate` applies both overlays before `super` unless dynamic
  colours are on, and recreates in `onStart` when the accent changed (like night mode).
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
- Per-house neutral surfaces (Ember and the other five houses): each house has its own night
  surfaces (top bar, list, chat background, incoming bubble, date chips, unread divider, sheets) and
  a tinted day set (surfaces, chat wallpaper, date chips). `gen_accents.py` is the single source: it
  writes the XML neutrals (`tatrak_accent_<house>_neutral_*`, applied by the house overlay) and
  `ui/theme/HouseNeutrals.kt` (generated, `neutralsOf(accent, dark, elegant)`), which `TatrakTheme`
  lays over the default Telegram palette (`ChatColors.withNeutrals`, `ColorScheme.withNeutrals`).
  Ember is hand-picked (black night `0B0B0C`, chat `070707`, incoming `1A1516`, outgoing `6B1117`,
  primary `D8323A`; its day keeps the plain light neutrals) and its output is unchanged by the
  generalisation. The others derive from a hue `T` washing grey levels:
  Steel (slate, icy near-black night, day seed `3877A4`), Crimson (crimson `B01E2F` with gold
  secondary, warm night), Gold (gold `D4A017`, dark text on gold, brown-black night), Teal
  (deep-sea teal `1E7C83`, teal-black night), Forest (green `497F36` with gold secondary, forest
  night), Copper (sun orange `C54A18`, sand day, ember night). Day seeds are nudged until white on
  the accent reaches AA (4.5, `fit()` in the generator); night accents that are light take dark
  `on_primary` automatically; link / time / badge colours are fitted to AA on their surfaces.
  The default wallpaper follows `chatColors.chatBackground` in both modes, so each house has its own.
- Elegant dark (opt-in, `tatrak_elegant_dark`, `ElegantDark`, a switch in Appearance, off by default so
  nothing changes for existing users): charcoal layers (list `121214`, top bar `1A1A1D`, chat
  `0B0B0D`, incoming bubble `1E1E22`, variant `1C1C1F`), off-white `ECE9E6` text, muted `A7A4A1`.
  It is the default neutral night palette that a house overrides: XML screens get
  `ThemeOverlay.Tatrak.Elegant` applied by `BaseActivity` between `ThemeOverlay.Tatrak` and the accent
  overlay (equal to the light neutrals by day), Compose gets `neutralsOf(...)` = house night, else
  elegant when on. Not applied with dynamic colours (the switch is hidden then). The preference is
  read by key, `AppSettings` is untouched. `BaseActivity.onStart` recreates an activity whose elegant
  flag differs from the setting, as for accent and style. Compose `onSurface`/`onBackground` take
  the neutrals' `text` (elegant `ECE9E6`, Slate `E6EAEE`/`14191F`), so Compose text matches the bubbles
  and XML screens. The Telegram style card previews the elegant night when it is on.
- Slate and Sea has its own elegant night (owner, 2026-11-06): graphite with a light cool tint
  (`SLATE_ELEGANT` in the generator: list `111315`, top bar `181B1E`, chat `0B0C0E`, incoming
  `1E2124`, text `E8EBED`, muted `A0A6AB`; all Slate contrast pairs pass in `check_contrast.py`).
  `neutralsOf` gives `slateElegantNight` for Slate + dark + elegant; `BaseActivity` applies
  `ThemeOverlay.Tatrak.SlateSea.Elegant` instead of `ThemeOverlay.Tatrak.SlateSea` (equal by day).
  The switch shows in both styles (hidden only with dynamic colours); a house still wins over it,
  and the Slate card previews the graphite night when the switch is on.
- Editor palettes (owner, 2026-11-06): `Accent.ONEDARK` and `Accent.DRACULA` (`palette = true`, keys
  `onedark`, `dracula`) work like houses: an accent plus their own night **and** day neutrals
  (`NEU_N` / `NEU_L` in the generator, hand-picked with `pal_night` / `pal_day`), so they win over
  Slate and Sea and over the elegant switch and need no new style or preference. Night: One Dark
  (`21252B`, text `ABB2BF`, accent `61AFEF`), Dracula (`21222C`, `F8F8F2`, `BD93F9`). Day: One Light
  and Alucard (light variants). Muted text, time and outline colours are fitted to AA; the day
  accent is darker than the editor's own blue/purple (fit 6.0 on white, `325EBD` / `644AC9`), the
  night accent is the official one. `check_contrast.py` covers all four.
- The Appearance picker shows ordinary accents, an "Editor palettes" group, and under a
  "Signature" heading the signature themes (`Accent.signature`): plain colour circles; the selected
  circle shows the check.
- Compose: `TatrakTheme(accent = rememberAccent())` reads the same overlay through
  `resolveAccent` (a `ContextThemeWrapper` with the wanted night mode) and updates live via a
  SharedPreferences listener. Accent slots change; neutrals stay fixed except for a house's own surfaces and the elegant night.
  With dynamic colours on (API 31+, stage 7a) `TatrakTheme` takes the accent slots from
  `dynamicLight/DarkColorScheme` instead (`dynamicAccent`). Only the accent follows the wallpaper:
  Compose keeps the Telegram neutrals, and since 2026-11-05 `BaseActivity` applies
  `ThemeOverlay.Tatrak` (neutrals only) under dynamic colours too, so XML screens match (their
  `colorSurfaceContainer*`, used by dialogs and bottom sheets, still carry the wallpaper tint).
- Glyphs on accent fills (unread badges, folder-tab counters, scroll-down counter, FAB, round file
  buttons in bubbles, swatches) use `contentOn(fill)`: near-black `1A1A1A` on a fill with luminance
  > 0.45 (dynamic tone-80/90 colours at night, Sea at night, Gold gold), white otherwise.

## Slate and Sea style (2026-11-03)

Setting `tatrak_style` (`telegram` default, `slate_sea`), read by key in `ui/theme/TatrakStyle.kt` (not in
`AppSettings`). Design: design_draft.

- Precedence in `neutralsOf(style, accent, dark, elegant)` (generated): dynamic colours (neutrals
  dropped in `TatrakTheme`), a house that defines neutrals for the mode (Ember has none by day, so
  Slate shows), Slate and Sea, elegant night, default. XML: `ThemeOverlay.Tatrak.SlateSea` (generated, day
  and night) is applied in `BaseActivity` instead of Elegant, before the accent; the Ember day
  overlay no longer sets neutrals (its night style lives in the generated `values-night/tatrak_accent_themes.xml`).
- Sea accent (`0B6F7A`; night `5CC4CE` with dark text, outgoing bubble day `CFE9EC`, night `12414A`).
  The stored accent is never changed: `TatrakStyle.effectiveAccent` gives Sea only while the key is unset
  and the style is Slate and Sea (Compose `rememberAccent` and `BaseActivity` both use it).
- Avatars: `LocalAvatarShape` (circle, or rounded square 36%) used by `LetterAvatar`, `ChatAvatar`, the
  archive avatar; the online dot / check moves 3 dp into the corner (`LocalAvatarDotOffset`). Bubbles,
  badges, FAB, icon circles stay round / unchanged. Shapes also apply with dynamic colours.
- Appearance: a Style section (two radio rows), a note under dynamic colours, the elegant switch hidden
  under Slate (stored value kept). Unread-divider override now applies by day too (`ChatScreenTheme`).
- Palette (day / night) mapped to `Neutrals`: background and surface `EEF1F4` / `0F1316`; top bar, incoming
  bubble, sheets `F8FAFB` / `171C20` (bubble `1F262B`); chat background `E3E9EE` / `0F1316`; text and
  on-top-bar `14191F` / `E6EAEE`; muted, time and on-top-bar-muted `56616C` / `98A3AD`; variant `E3E9EE` / `1F262B`;
  highest container and outline-variant `DCE2E8` / `2A323A`,`232B31`; outline `7C8791` / `5E6B76`; archive and swipe
  `7F8C98`,`768390` / `3A444D`; drawer header `2B3A44` / `1F262B`; unread divider `F8FAFB` / `1F262B`;
  date chip `B326323B` / `CC1F262B`; online `2FA85A` / `4CC38A`. Source: `SLATE_L`, `SLATE_D` in `art/tatrak/gen_accents.py`.
- Contrast: `python3 art/tatrak/check_contrast.py` (reuses `cr()` of the generator; 4.5 text, 3 icons).
  Result: all pairs pass (body and muted text on every Slate surface 4.8 to 16.9, link 5.4 to 11.2, time on
  outgoing 6.0 / 6.6, badge and button text 5.9 / 8.3, date chip 5.6 / 16.1, check marks 4.6 and up, outline 3.2 / 3+).
  First run failed three pairs, fixed: white icon on archive / swipe by day (1.9 / 2.5, now darker), night
  outline (1.9), and the warning bubble text by day (3.77; `warningText` darkened to `8F5600`, which also
  changes the Telegram style slightly). Not a failure but worth knowing: other accents keep their own link /
  time colours on slate surfaces (cyan, green, pink, orange links are 3.0 to 4.0 on the incoming bubble, as in Telegram).

## Slate and Sea phase 2 (2026-11-04)

`TatrakTheme` provides `LocalTatrakStyle` and `LocalTatrakFont` (Manrope family in Slate, null otherwise); composables read the locals, never the preference. Slate-only branches (the Telegram code path is the old code verbatim): chat list `bottomBar` with `ChatListBottomBar` (floating search pill, compose button, dock of four shortcuts, hidden by `dockVisible` while searching, in the archive or selecting; no FAB; no top-bar search icon), `SlateComposer` in `Composer.kt` (pill over the wallpaper, insets outside the pill, reply or edit and emoji panels as separate blocks, recording halo and mic ring), `BubbleShape(soft = true)` with `bubbleRadii` (20 dp and 6 dp, no tail), a `Typography` with only the font family changed, `StyleCards` in Appearance (fixed preview colours mirroring the generator). The font is applied explicitly to `BodyStyle`, `MetaStyle` and the composer `BasicTextField`; XML screens keep the system font. Font facts: Manrope.

