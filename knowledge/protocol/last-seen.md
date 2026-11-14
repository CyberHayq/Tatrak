---
type: Concept
title: Offline last seen (XEP-0012)
description: How Tatrak shows when an offline contact was last online, what the server must support and how to check it.
tags: [protocol, xep-0012, last-seen, privacy, prosody]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-04T17:00:00Z }
sources:
  - id: manager
    resource: ../../src/main/java/eu/siacs/conversations/xmpp/manager/LastActivityManager.java
    title: LastActivityManager
  - id: snapshot
    resource: ../../src/main/kotlin/am/cyberhayq/chat/ui/chat/ChatSnapshot.kt
    title: Chat header (subtitle)
---

# Behaviour

The chat header subtitle is, in order: typing, "online" / idle time from the contact's presence
(XEP-0319, existing), and, when the contact has **no presence**, the answer to an XEP-0012
`jabber:iq:last` query sent to the contact's bare address ("last seen 5 min ago").

- Only for contacts we are subscribed to (`Contact.Options.TO`), account connected, not over Tor.
- One query per contact per 10 minutes, per connection; a failed query is not retried for that time.
- Any error, a missing or negative `seconds`, or `seconds="0"` shows nothing.
- Still gated by the existing "broadcast last activity" setting (see monocles' `last_activity`):
  you see others' last seen if you share your own.
- The contact is not told about the query: the contact's server answers by itself and applies its
  own rule (Prosody: only to subscribers).
- Tatrak does not publish anything new; the server alone decides what it reveals.

# Is my server supporting it?

1. Ask the server: a disco#info on the **domain** lists `jabber:iq:last` only when the module is
   loaded (Prosody `mod_lastactivity`, ejabberd `mod_last`). Prosody's `mod_uptime` also advertises
   it for the domain itself, so this check alone is weak.
2. Ask for a **user**: from a second account that is subscribed to the contact, send
   `<iq type='get' to='contact@domain'><query xmlns='jabber:iq:last'/></iq>`. Expected: `seconds`
   with a plausible number after the contact logged out. `service-unavailable` = module missing
   for users; `forbidden` = not subscribed. `seconds="0"` for an offline contact = server does not
   record the logout (Prosody stock module only records an explicit `unavailable`).
3. In the app: with the setting on and a contact in the roster, open the offline contact's chat;
   the subtitle should appear within a second. The debug log line `no last activity from <jid>: …`
   gives the error.

The test server script `/q.py` (see `tools/test-server`) is not kept; use any XMPP client that can send
raw IQs, or the app.

# Test server

`tools/test-server/prosody.cfg.lua` enables `lastactivity`. `lastlog2` is not shipped in the
container image (Prosody 13.0.1) and was not used.
