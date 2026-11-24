"""Showcase demo data for the store screenshots (runbook: knowledge/ops/store-screenshots.md).
Runs inside the tatrak-prosody container; tools/test-server/seed_showcase.sh copies it in, registers
the accounts and runs it:

  users                      print the account names (for the wrapper's prosodyctl calls)
  setup                      nicknames, avatars, ME's roster with mutual subscriptions, the two
                             rooms (members-only, non-anonymous, archived) and ME's bookmarks
  history --now 09:00 --tz +04:00
                             one-to-one chats and group history dated today (before --now),
                             yesterday and earlier this week; ME must be logged in on the
                             emulator and joined to the rooms (the app keeps a sender's
                             <delay/> only for live messages, an archive fetch dates every
                             message by the server's clock); skips chats and rooms that
                             already have history
  online --minutes 30 [--typing aram]
                             keeps some contacts online; --typing sends "composing" from one
  reset                      destroys the rooms (the wrapper then deletes the accounts)

Ani's one-to-one chat is not seeded: it is typed on two emulators so it is OMEMO-encrypted.
"""
import argparse, asyncio, datetime, http.client, mimetypes, os, socket, ssl, sys, urllib.parse, uuid
from xml.etree import ElementTree as ET
import slixmpp

DOMAIN, PASSWORD = "10.0.2.2", "secret123"
MUC = f"conference.{DOMAIN}"
CERTIFICATE = f"/var/lib/prosody/certs/{DOMAIN}.crt"
MEDIA = "/tmp/showcase"  # avatars/ and photos/ from make_media.py, copied in by the wrapper

ME, ME_NAME, ME_NICK = "hayk", "Hayk Arakelyan", "Hayk"
PEOPLE = {  # account: (roster name, XEP-0172 nick, room nick)
    "ani": ("Ani Petrosyan", "Ani Petrosyan", "Ani"),
    "aram": ("Aram Hakobyan", "Aram Hakobyan", "Aram"),
    "davit": ("Davit Sargsyan", "Davit Sargsyan", "Davit"),
    "lilit": ("Lilit Grigoryan", "Lilit Grigoryan", "Lilit"),
    "karen": ("Karen Avetisyan", "Karen Avetisyan", "Karen"),
    "narek": ("Narek Mkrtchyan", "Narek Mkrtchyan", "Narek"),
    "sona": ("Sona Harutyunyan", "Sona Harutyunyan", "Sona"),
    "gor": ("Gor Vardanyan", "Gor Vardanyan", "Gor"),
    "mom": ("Mom", "Anahit Arakelyan", "Mom"),
}
ROOMS = {  # room: (name, owner, members besides the owner and ME)
    "family": ("Family", "mom", ["lilit", "gor", "narek"]),
    "devteam": ("Dev team", "karen", ["davit", "sona", "aram"]),
}
ONLINE = ["aram", "lilit", "sona", "mom"]

# One-to-one chats: (days ago, local time, "me" or "them", text). Last line = the chat list preview.
CHATS = {
    "aram": [
        (1, "19:20", "them", "Are we still meeting for coffee tomorrow?"),
        (1, "19:24", "me", "Yes! 9:45 at the usual place?"),
        (0, "08:41", "them", "Running ten minutes late, grab us a table by the window ☕"),
    ],
    "lilit": [
        (2, "13:05", "them", "I finished the book you lent me. The ending! 😭"),
        (0, "07:52", "them", "Can I keep it until the weekend? I want to reread the last part"),
        (0, "07:58", "me", "Of course, keep it as long as you like 📚"),
    ],
    "mom": [
        (1, "12:30", "them", "Did you eat something warm today?"),
        (1, "12:41", "me", "Yes, Mom, soup for lunch 🙂"),
        (1, "20:47", "them", "Call me when you're free ❤️"),
    ],
    "karen": [
        (1, "21:02", "me", "How was the hike?"),
        (1, "21:14", "them", "Long but worth it. The photos came out great, sending them tonight"),
    ],
    "sona": [
        (2, "18:10", "them", "Thanks for the concert tickets, it was wonderful 🎻"),
        (2, "18:32", "me", "So glad you liked it! Next time the jazz evening"),
    ],
    "narek": [
        (3, "10:15", "them", "Can you send me the address of the workshop?"),
        (3, "10:21", "me", "14 Abovyan Street, second floor. See you there!"),
    ],
    "gor": [
        (4, "16:40", "them", "Chess on Friday? I've been practising ♟️"),
    ],
}

# Group history: (days ago, local time, account or "me", kind, text, ref). kind: text | photo |
# reply (ref = index of the answered line) | react (ref = index, text = the emoji).
HISTORY = {
    "family": [
        (1, "20:05", "mom", "text", "Who is coming for dinner on Sunday?", None),
        (1, "20:07", "lilit", "text", "Me! I'll bring the dessert 🍰", None),
        (1, "20:11", "gor", "text", "Count me in", None),
        (1, "20:15", "me", "text", "Me too, and I'll bring Grandma's favourite pomegranate juice", None),
        (1, "20:16", "mom", "react", "❤️", 3),
        (0, "07:45", "mom", "photo", "mountains.jpg", None),
        (0, "07:46", "mom", "text", "First snow on the mountains this morning ❄️", None),
        (0, "07:51", "narek", "text", "Wow, beautiful! 😍", None),
        (0, "07:52", "lilit", "react", "😍", 5),
        (0, "07:53", "gor", "react", "❤️", 5),
        (0, "08:20", "gor", "reply", "We should drive up there next weekend", 5),
        (0, "08:40", "lilit", "text", "Hayk, can you pick up Grandma on the way on Sunday? 🙏", None),
        (0, "08:52", "mom", "text", "And everyone take warm jackets, it's cold up there 🧥", None),
    ],
    "devteam": [
        (1, "17:40", "karen", "text", "The new build is on the test server, please try it tonight", None),
        (1, "18:02", "sona", "text", "Installed. Scrolling the chat list is so smooth now 👌", None),
        (1, "18:03", "karen", "react", "🎉", 1),
        (0, "08:05", "davit", "text", "Morning! Stand-up at 10:00 as usual", None),
        (0, "08:10", "aram", "reply", "I'll join from the café, running a bit late", 3),
        (0, "08:15", "davit", "text", "Release notes are ready for review 📝", None),
        (0, "08:16", "karen", "react", "👍", 5),
    ],
}

NS_DELAY, NS_SID = "urn:xmpp:delay", "urn:xmpp:sid:0"


def jid(user):
    return f"{user}@{DOMAIN}"


class Client(slixmpp.ClientXMPP):
    def __init__(self, user, resource="showcase"):
        super().__init__(f"{jid(user)}/{resource}", PASSWORD)
        self.user = user
        self.ssl_context.load_verify_locations(CERTIFICATE)  # the server's self-signed certificate only
        for p in ("xep_0030", "xep_0045", "xep_0060", "xep_0084", "xep_0172", "xep_0363"):
            self.register_plugin(p)
        self.roster.auto_authorize = True
        self.roster.auto_subscribe = False
        self.stanza_ids = {}  # message id -> the room's stanza-id, from the reflections
        self.add_filter("in", self._reflection)  # a filter: slixmpp has no event for body-less messages

    def _reflection(self, st):
        if st.name == "message" and st["type"] == "groupchat" and st["id"]:
            sid = st.xml.find(f"{{{NS_SID}}}stanza-id")
            if sid is not None:
                self.stanza_ids[st["id"]] = sid.get("id")
        return st

    async def start(self):
        self.connect(host="127.0.0.1", port=5222)
        await self.wait_until("session_start", 30)
        await self.get_roster()
        self.send_presence()
        return self

    async def stop(self):
        self.disconnect()
        await asyncio.wait_for(self.disconnected, 10)


async def join(client, room_jid, nick):
    """Joins and returns True, or False when the room refuses (destroyed, members-only).
    join_muc_wait would wait for the subject, which a new (locked) room never sends."""
    done = asyncio.get_running_loop().create_future()

    def on_presence(p):  # a filter: slixmpp's "presence" event skips a self-presence like an earlier one
        if p.name == "presence" and p["from"].bare == room_jid and not done.done():
            if p["type"] == "error":
                done.set_result(False)
            elif p["from"].resource == nick and 110 in p["muc"]["status_codes"]:
                done.set_result(True)
        return p
    client.add_filter("in", on_presence)
    try:
        client["xep_0045"].join_muc(room_jid, nick)
        return await asyncio.wait_for(done, 15)
    except TimeoutError:
        print(client.boundjid.bare, "got no answer joining", room_jid)
        return False
    finally:
        client.del_filter("in", on_presence)


async def bookmarked_rooms(me):
    """Room name -> room JID from ME's bookmarks (setup may have picked family-2 and so on)."""
    try:
        result = await me["xep_0060"].get_items(jid(ME), "urn:xmpp:bookmarks:1", timeout=15)
    except Exception:
        return {}
    rooms = {}
    for item in result["pubsub"]["items"]:
        conf = item.xml.find("{urn:xmpp:bookmarks:1}conference")
        if conf is not None:
            rooms[conf.get("name")] = item["id"]
    return rooms


async def connect_all(users):
    clients = await asyncio.gather(*(Client(u).start() for u in users))
    return dict(zip(users, clients))


async def stop_all(clients):
    await asyncio.gather(*(c.stop() for c in clients.values()), return_exceptions=True)


# --- setup -----------------------------------------------------------------------------------

async def publish_profile(c):
    await c["xep_0172"].publish_nick(PEOPLE[c.user][1] if c.user in PEOPLE else ME_NAME)
    path = f"{MEDIA}/avatars/{c.user}.png"
    if os.path.isfile(path):
        data = open(path, "rb").read()
        aid = c["xep_0084"].generate_id(data)
        await c["xep_0084"].publish_avatar(data)
        await c["xep_0084"].publish_avatar_metadata([{"id": aid, "type": "image/png", "bytes": str(len(data)),
                                                     "width": "192", "height": "192"}])


async def until(check, what, seconds=10):
    for _ in range(seconds * 10):
        if check():
            return
        await asyncio.sleep(0.1)
    sys.exit(f"timed out waiting for {what}")


async def befriend(me, clients):
    """Mutual subscriptions, one direction at a time: subscribing both ways at once left the
    contact's side at "to" (no presence reached ME) in a test run."""
    sub = lambda a, b: a.client_roster[jid(b)]["subscription"]
    for user, c in clients.items():
        await me.update_roster(jid(user), name=PEOPLE[user][0])
        await c.update_roster(jid(ME), name=ME_NAME)
        for a, b, other in ((me, user, c), (c, ME, me)):
            if sub(a, b) not in ("to", "both") or sub(other, a.user) not in ("from", "both"):
                a.send_presence_subscription(pto=jid(b))  # the other side's auto_authorize answers
                await until(lambda: sub(a, b) in ("to", "both") and sub(other, a.user) in ("from", "both"),
                            f"{a.user} -> {b} subscription")


async def make_room(owner, room, name, members):
    # Prosody keeps a destroyed room's tombstone for 31 days, so after a reset take the next free name
    for n in range(1, 30):
        room_jid = f"{room}@{MUC}" if n == 1 else f"{room}-{n}@{MUC}"
        if await join(owner, room_jid, PEOPLE[owner.user][2]):
            break
    else:
        sys.exit(f"no free room name for {room}")
    form = await owner["xep_0045"].get_room_config(room_jid)
    wanted = {
        "muc#roomconfig_roomname": name, "muc#roomconfig_persistentroom": True,
        "muc#roomconfig_membersonly": True, "muc#roomconfig_whois": "anyone",
        "muc#roomconfig_publicroom": False, "muc#roomconfig_enablearchiving": True,
        "muc#roomconfig_changesubject": False,
    }
    fields = form.get_fields()
    missing = [var for var in wanted if var not in fields]
    if missing:
        print("room form has no", ", ".join(missing), "- available:", ", ".join(fields))
    for var, value in wanted.items():
        if var in fields:
            fields[var]["value"] = value
    form["type"] = "submit"
    await owner["xep_0045"].set_room_config(room_jid, form)
    for m in members + [ME]:
        await owner["xep_0045"].set_affiliation(room_jid, "member", jid=slixmpp.JID(jid(m)))
    return room_jid


async def bookmark(me, room_jid, name):
    conf = ET.Element("{urn:xmpp:bookmarks:1}conference", {"name": name, "autojoin": "true"})
    ET.SubElement(conf, "{urn:xmpp:bookmarks:1}nick").text = ME_NICK
    options = me["xep_0004"].make_form("submit")
    options.add_field("FORM_TYPE", ftype="hidden", value="http://jabber.org/protocol/pubsub#publish-options")
    for var, value in (("pubsub#persist_items", "true"), ("pubsub#access_model", "whitelist"),
                       ("pubsub#max_items", "max"), ("pubsub#send_last_published_item", "never")):
        options.add_field(var, value=value)
    await me["xep_0060"].publish(jid(ME), "urn:xmpp:bookmarks:1", id=room_jid, payload=conf, options=options)


async def setup(_):
    me = await Client(ME).start()
    clients = await connect_all(list(PEOPLE))
    try:
        await asyncio.gather(publish_profile(me), *(publish_profile(c) for c in clients.values()))
        print("nicknames and avatars published")
        await befriend(me, clients)
        print("roster done")
        for room, (name, owner, members) in ROOMS.items():
            room_jid = await make_room(clients[owner], room, name, members)
            await bookmark(me, room_jid, name)
            print("room", room_jid, "members", ", ".join(members + [ME]))
        print("roster of", jid(ME), {u: me.client_roster[jid(u)]["subscription"] for u in PEOPLE})
    finally:
        await stop_all(dict(clients, me=me))


# --- history ---------------------------------------------------------------------------------

def stamper(now, tz):
    sign = -1 if tz.startswith("-") else 1
    hours, minutes = tz.lstrip("+-").split(":")
    zone = datetime.timezone(sign * datetime.timedelta(hours=int(hours), minutes=int(minutes)))
    today = datetime.datetime.now(zone).date()
    now = now or datetime.datetime.now(zone).strftime("%H:%M")
    anchor = datetime.datetime.combine(today, datetime.time.fromisoformat(now), zone)

    def stamp(days_ago, hhmm):
        t = datetime.datetime.combine(today - datetime.timedelta(days=days_ago), datetime.time.fromisoformat(hhmm), zone)
        if t >= anchor:
            sys.exit(f"{hhmm} {days_ago} day(s) ago is not before --now {now}: the app would clamp it")
        return t.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return stamp


def delayed(msg, stamp):
    msg.xml.append(ET.Element(f"{{{NS_DELAY}}}delay", {"stamp": stamp}))
    return msg


async def has_history(client, archive, with_jid=None):
    """True when the MAM archive (a room, or ours with with_jid) holds at least one message."""
    iq = client.make_iq_set(ito=archive if archive != jid(ME) else None)
    query = ET.SubElement(iq.xml, "{urn:xmpp:mam:2}query")
    form = ET.SubElement(query, "{jabber:x:data}x", {"type": "submit"})
    for var, value in (("FORM_TYPE", "urn:xmpp:mam:2"),) + ((("with", with_jid),) if with_jid else ()):
        ET.SubElement(ET.SubElement(form, "{jabber:x:data}field", {"var": var}), "{jabber:x:data}value").text = value
    ET.SubElement(ET.SubElement(query, "{http://jabber.org/protocol/rsm}set"), "{http://jabber.org/protocol/rsm}max").text = "1"
    result = await iq.send(timeout=15)
    return result.xml.find(".//{http://jabber.org/protocol/rsm}first") is not None


class ContainerHttps(http.client.HTTPSConnection):
    """The slot URL names 10.0.2.2 (the emulator's view of the host); inside the container the
    server is 127.0.0.1. Connect there, but check the certificate against the URL's host."""

    def connect(self):
        sock = socket.create_connection(("127.0.0.1", self.port), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)


async def upload(client, path):
    """XEP-0363 by hand, trusting only the server's own certificate (as in send.py)."""
    size, kind = os.path.getsize(path), mimetypes.guess_type(path)[0] or "application/octet-stream"
    slot = await client["xep_0363"].request_slot(f"upload.{DOMAIN}", os.path.basename(path), size, content_type=kind)
    put = urllib.parse.urlsplit(slot["http_upload_slot"]["put"]["url"])
    headers = {"Content-Type": kind, "Content-Length": str(size)}
    headers.update({h["name"]: h["value"] for h in slot["http_upload_slot"]["put"]["headers"]})

    def send():
        conn = ContainerHttps(put.hostname, put.port or 443, timeout=20,
                              context=ssl.create_default_context(cafile=CERTIFICATE))
        conn.request("PUT", put.path + ("?" + put.query if put.query else ""), open(path, "rb").read(), headers)
        status = conn.getresponse().status
        conn.close()
        if status not in (200, 201):
            sys.exit(f"upload of {path} failed: HTTP {status}")
    await asyncio.to_thread(send)
    return slot["http_upload_slot"]["get"]["url"]


async def seed_chat(me, them, user, lines, stamp):
    for days, hhmm, who, text in lines:
        sender, to = (me, jid(user)) if who == "me" else (them, jid(ME))
        msg = delayed(sender.make_message(mto=to, mbody=text, mtype="chat"), stamp(days, hhmm))
        msg["id"] = uuid.uuid4().hex
        msg.xml.append(ET.Element("{urn:xmpp:chat-markers:0}markable"))
        msg.send()
        await asyncio.sleep(0.3)
        if who == "me":  # read by the contact: the chat list shows read ticks
            marker = them.make_message(mto=jid(ME), mtype="chat")
            marker.xml.append(ET.Element("{urn:xmpp:chat-markers:0}displayed", {"id": msg["id"]}))
            marker.send()
            await asyncio.sleep(0.2)


async def stanza_id(client, msg_id):
    for _ in range(50):
        if msg_id in client.stanza_ids:
            return client.stanza_ids[msg_id]
        await asyncio.sleep(0.1)
    sys.exit(f"no reflection with a stanza-id for {msg_id} to {client.boundjid} (has {list(client.stanza_ids)})")


async def seed_room(clients, room_jid, lines, stamp):
    nick = {u: PEOPLE[u][2] for u in PEOPLE}
    nick["me"] = ME_NICK
    sent = []  # (stanza-id, sender nick, text) per line
    for days, hhmm, who, kind, text, ref in lines:
        c = clients[who]
        msg = c.make_message(mto=room_jid, mtype="groupchat")
        msg["id"] = uuid.uuid4().hex
        if kind == "react":
            r = ET.Element("{urn:xmpp:reactions:0}reactions", {"id": sent[ref][0]})
            ET.SubElement(r, "{urn:xmpp:reactions:0}reaction").text = text
            msg.xml.append(r)
            msg.xml.append(ET.Element("{urn:xmpp:hints}store"))
        else:
            body = text
            if kind == "photo":
                body = await upload(c, f"{MEDIA}/photos/{text}")
                ET.SubElement(ET.SubElement(msg.xml, "{jabber:x:oob}x"), "{jabber:x:oob}url").text = body
            if kind == "reply":
                sid, to_nick, quoted = sent[ref]
                fallback = f"> {to_nick}: {quoted}\n"
                body = fallback + text
                msg.xml.append(ET.Element("{urn:xmpp:reply:0}reply", {"id": sid, "to": f"{room_jid}/{to_nick}"}))
                fb = ET.Element("{urn:xmpp:fallback:0}fallback", {"for": "urn:xmpp:reply:0"})
                ET.SubElement(fb, "{urn:xmpp:fallback:0}body", {"start": "0", "end": str(len(fallback))})
                msg.xml.append(fb)
            msg["body"] = body
            delayed(msg, stamp(days, hhmm))
        msg.send()
        sid = None if kind == "react" else await stanza_id(c, msg["id"])  # nothing refers to a reaction
        sent.append((sid, nick[who], "Photo" if kind == "photo" else text))
        await asyncio.sleep(0.3)


async def history(a):
    stamp = stamper(a.now, a.tz)
    me = await Client(ME).start()
    clients = await connect_all(list(PEOPLE))
    clients["me"] = me
    try:
        await asyncio.sleep(2)
        apps = [r for r in me.client_roster[jid(ME)].resources if r != "showcase"]
        if not apps and not a.force:
            sys.exit(f"{jid(ME)} is not online on the emulator; log the app in first (or --force)")
        rooms = await bookmarked_rooms(me)
        for room, (name, owner, members) in ROOMS.items():
            room_jid = rooms.get(name) or sys.exit(f"{jid(ME)} has no bookmark for {name}: run setup first")
            for u in [owner] + members:
                if not await join(clients[u], room_jid, PEOPLE[u][2]):
                    sys.exit(f"{u} cannot join {room_jid}: run setup again")
            if await has_history(clients[owner], room_jid):
                print("skip", room_jid, "(already has history)")
                continue
            for _ in range(60):  # the app's occupant: its live copy keeps our timestamps
                if ME_NICK in clients[owner]["xep_0045"].get_roster(room_jid):
                    break
                await asyncio.sleep(1)
            else:
                if not a.force:
                    sys.exit(f"{ME_NICK} has not joined {room_jid}; open the app (bookmark autojoin) or --force")
            await join(me, room_jid, ME_NICK)
            await seed_room(clients, room_jid, HISTORY[room], stamp)
            print("seeded", room_jid, len(HISTORY[room]), "stanzas")
        for user, lines in CHATS.items():
            if await has_history(me, jid(ME), with_jid=jid(user)):
                print("skip chat with", user, "(already has history)")
                continue
            await seed_chat(me, clients[user], user, lines, stamp)
            print("seeded chat with", user, len(lines), "messages")
        await asyncio.sleep(2)
    finally:
        await stop_all(clients)


# --- online / reset --------------------------------------------------------------------------

async def online(a):
    clients = await connect_all(ONLINE + ([a.typing] if a.typing and a.typing not in ONLINE else []))
    print("online:", ", ".join(clients), f"for {a.minutes} min")

    def state(user, name):
        m = clients[user].make_message(mto=jid(ME), mtype="chat")
        m.xml.append(ET.Element(f"{{http://jabber.org/protocol/chatstates}}{name}"))
        m.send()
    try:
        end = asyncio.get_running_loop().time() + a.minutes * 60
        while asyncio.get_running_loop().time() < end:
            if a.typing:
                state(a.typing, "composing")
            await asyncio.sleep(20)
        if a.typing:
            state(a.typing, "active")
    finally:
        await stop_all(clients)


async def reset(_):
    """Destroys the bookmarked rooms (with their archive); the wrapper then deletes the accounts."""
    owners = await connect_all(sorted({o for _, o, _ in ROOMS.values()}) + [ME])
    try:
        rooms = await bookmarked_rooms(owners[ME])
        for room, (name, owner, _) in ROOMS.items():
            room_jid = rooms.get(name)
            if room_jid and await join(owners[owner], room_jid, PEOPLE[owner][2]):
                await owners[owner]["xep_0045"].destroy(room_jid, reason="showcase reset")
                print("destroyed", room_jid)
            else:
                print("no room to destroy for", name, room_jid or "(no bookmark)")
    finally:
        await stop_all(owners)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("users")
    sub.add_parser("setup")
    h = sub.add_parser("history")
    h.add_argument("--now", help="the emulator's clock (HH:MM, default: the current time in --tz); every seeded time is before it")
    h.add_argument("--tz", default="+04:00", help="the emulator's UTC offset, from: adb shell date +%%z")
    h.add_argument("--force", action="store_true", help="seed even if the app is not online or not joined")
    o = sub.add_parser("online")
    o.add_argument("--minutes", type=float, default=30)
    o.add_argument("--typing", choices=sorted(PEOPLE), help="this contact shows as typing in the chat list")
    sub.add_parser("reset")
    a = p.parse_args()
    if a.command == "users":
        print(" ".join([ME] + list(PEOPLE)))
        return
    if a.command == "history" and len(a.tz) == 5 and a.tz[0] in "+-":  # +0400 as date prints it
        a.tz = a.tz[:3] + ":" + a.tz[3:]
    asyncio.run({"setup": setup, "history": history, "online": online, "reset": reset}[a.command](a))


if __name__ == "__main__":
    main()
