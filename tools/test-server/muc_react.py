"""MUC test helper (slixmpp). One process = one user in one room, running its actions in order.

usage: muc_react.py <user> <room> <nick> <action>...      (room is a localpart on conference.10.0.2.2)
actions:
  say:<origin-id>:<text>      groupchat message; prints "STANZA-ID <origin-id> <id the room assigned>"
  react:<id>:<e1>,<e2>        reactions to a groupchat message (id = stanza id or origin id); empty emoji list retracts
  pm:<nick>:<id>:<text>       private message through the room
  pmreact:<nick>:<id>:<e,..>  reaction in that private chat
  watch:<seconds>             print incoming groupchat messages with their ids
  sleep:<seconds>
Runs on the host (needs slixmpp; connects to 127.0.0.1:5222). Leaving happens when the actions end (unavailable presence). Example:
  muc_react.py vika t1 vika say:m1:hello react:<id>:👍,❤ sleep:2
"""
import asyncio, ssl, sys
from xml.etree import ElementTree as ET
import slixmpp
R = "urn:xmpp:reactions:0"
class C(slixmpp.ClientXMPP):
    def __init__(s, user, room, nick, acts):
        super().__init__(f"{user}@10.0.2.2/muc", "secret123"); s.room = f"{room}@conference.10.0.2.2"
        s.nick, s.acts, s.mine = nick, acts, {}
        s.ssl_context.check_hostname = False; s.ssl_context.verify_mode = ssl.CERT_NONE
        s.register_plugin("xep_0045"); s.add_event_handler("session_start", s.go)
        s.add_event_handler("groupchat_message", s.gc)
    def gc(s, m):
        sid = next((e.get("id") for e in m.xml.iter("{urn:xmpp:sid:0}stanza-id")), None)
        print("GC", m["mucnick"], m["id"], sid, m["body"], flush=True)
        if m["mucnick"] == s.nick and m["id"] in s.mine: print("STANZA-ID", m["id"], sid, flush=True)
    def rx(s, m, ids, emojis):
        r = ET.Element(f"{{{R}}}reactions", {"id": ids})
        for e in filter(None, emojis.split(",")): ET.SubElement(r, f"{{{R}}}reaction").text = e
        m.xml.append(r); m.xml.append(ET.Element("{urn:xmpp:hints}store")); m.send()
    def out(s, to, typ, mid, body):
        m = s.make_message(mto=to, mbody=body, mtype=typ); m["id"] = mid; m.send()
    async def go(s, _):
        s.send_presence(); await s["xep_0045"].join_muc_wait(s.room, s.nick)
        for a in s.acts:
            k, *p = a.split(":", 3 if a.startswith(("pm:", "pmreact:")) else 2)
            if k == "say":
                s.mine[p[0]] = 1; s.out(s.room, "groupchat", p[0], p[1])
            elif k == "react": s.rx(s.make_message(mto=s.room, mtype="groupchat"), p[0], p[1])
            elif k == "pm": s.out(f"{s.room}/{p[0]}", "chat", p[1], p[2])
            elif k == "pmreact": s.rx(s.make_message(mto=f"{s.room}/{p[0]}", mtype="chat"), p[1], p[2])
            await asyncio.sleep(float(p[0]) if k in ("sleep", "watch") else 0.7)
        s.disconnect()
if __name__ == "__main__":
    x = C(*sys.argv[1:4], sys.argv[4:]); x.connect(host="127.0.0.1", port=5222)
    asyncio.get_event_loop().run_until_complete(x.disconnected)
