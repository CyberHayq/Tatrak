---
type: Runbook
title: Prosody setup for permanent sticker packs
description: How to add a stickers.<domain> upload component to Prosody so shared sticker packs don't expire with the regular upload cleanup; tested on Prosody 13.0.1.
tags: [ops, prosody, stickers, http-upload]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-31T00:00:00Z }
sources:
  - id: prosody-doc
    resource: https://prosody.im/doc/modules/mod_http_file_share
    title: Prosody mod_http_file_share documentation
---

# Why

Regular uploads (`mod_http_file_share`) are deleted after `http_file_share_expires_after`
(default one week), which breaks shared sticker pack links. Tatrak uploads "Share my
stickers" to an upload component whose address starts with `stickers.` when the server has one
(`HttpUploadManager.uploadSticker`), and never uses that component for ordinary files. Without
it, the app warns that the link is temporary.

# Prosody 13.x

```lua
-- keep the regular service as it is
Component "upload.example.org" "http_file_share"
    http_file_share_expires_after = "2 weeks"

-- sticker packs: kept forever, only stickers and pack files, small and capped
Component "stickers.example.org" "http_file_share"
    http_file_share_expires_after = "never"
    http_file_share_allowed_file_types = { "image/webp", "application/json" }
    http_file_share_size_limit = 1024*1024          -- one sticker / pack file
    http_file_share_daily_quota = 50*1024*1024      -- per user per day
    http_file_share_global_quota = 2*1024*1024*1024 -- whole store
```

Prosody **0.12.x**: the expiry is a number of seconds; `-1` disables it
(`http_file_share_expires_after = -1`), and `"never"` is a config error there.

The new component needs DNS (`stickers.example.org`) and a TLS certificate like the upload
component, or share one HTTP host: `http_host = "example.org"` plus a distinct path per
component, e.g. `http_paths = { file_share = "/stickers" }`.

By default only local users may upload (`http_file_share_access`), so people on other servers
can import packs but not host them here.

# Check

1. `prosodyctl check` and restart.
2. In the app: Stickers tab → share; Prosody logs
   `stickers.example.org:http_file_share info Issuing upload slot …` and the link contains the
   sticker host / path. Sending a sticker into a chat still uses the regular component.
3. A PDF to the sticker component is refused with "File type not allowed".

Verified on 2026-10-30 against Prosody 13.0.1 (Debian trixie package) with both components on
one HTTP host.

# Notes

- Files on the sticker component live until deleted on the server; there is no in-app way to
  remove a shared pack from the server yet.
- An old Prosody nightly without channel binding is rejected by the app ("No channel binding");
  that is the app's downgrade protection, not a sticker issue.
