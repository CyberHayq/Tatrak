---
type: Plan
title: Group topics (Telegram-style forum groups)
description: Idea record for groups with topics: options compared, the owner's preliminary choices (one MUC room per topic, link stored in a XEP-0402 bookmark extension) and the open question.
tags: [plan, groups, topics, muc, xep-0402, mix]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-03T12:00:00Z }
sources:
  - id: message-parser
    resource: ../../src/main/java/eu/siacs/conversations/parser/MessageParser.java
    title: MessageParser (only checks for a thread child today)
---

Owner request, 2026-11-03: groups with topics (sub-groups) like Telegram forums. Not started;
no code, protocol or server work done. Source: the assistant's code reading and general
protocol knowledge only; MIX server and client support was not verified.

# Options compared

1. `<thread>` inside one room (XEP-0201), as monocles does: no topic name, members or rights;
   other clients see one mixed stream. Not chosen for now.
2. **One MUC room per topic, grouped under a parent: preliminary choice.** Works on ordinary
   servers (Prosody test server included); other clients see plain rooms; `xmpp/` is barely
   touched. Needs our own convention for the parent link.
3. MIX (XEP-0369) channels: standard channels but few servers (reportedly ejabberd and
   MongooseIM, experimental; not Prosody) and almost no clients (not Conversations); large
   change in `xmpp/`. Not chosen.

# Decisions so far (preliminary, 2026-11-03)

- **Link between a group and its topics:** stored in an extension of the XEP-0402 bookmark
  (our own namespace). Other clients ignore the extension and show plain rooms.
- **What the "parent" is:** undecided (a separate empty room, or one topic room acting as the
  main one, e.g. "General").

# Open before implementation

- The parent question above, and the extension's namespace and format.
- Room-creation rights on the target servers; behaviour of other clients that join only some
  topic rooms.
- Unread counters, notifications, archive/mute and create/close/rename flows per topic; UI
  (topic list in the header or tabs).
- Any change to `xmpp/` or stanza parsers needs the owner's agreement first
  ([security untouched](../decisions/security-untouched.md)); bookmark extension handling may fall under it.
- OMEMO behaviour per topic room is unchecked.
