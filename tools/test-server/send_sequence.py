"""Send a XEP-0308/0424 stanza sequence with stable ids, e.g. from the host:
  podman exec tatrak-prosody python3 /send_sequence.py friend tester --order O,R,C --id case1
O = original message, R = retraction, C = correction (text "... EDITED"); 1 s between stanzas."""
import argparse, asyncio, ssl
import slixmpp

DOMAIN, PASSWORD = "10.0.2.2", "secret123"

STANZAS = {
    "O": '<message to="{to}" id="{id}" type="chat"><body>secret {id}</body>'
         '<origin-id xmlns="urn:xmpp:sid:0" id="{id}"/></message>',
    "R": '<message to="{to}" type="chat"><retract xmlns="urn:xmpp:message-retract:1" id="{id}"/>'
         '<fallback xmlns="urn:xmpp:fallback:0" for="urn:xmpp:message-retract:1"/>'
         "<body>/me retracted a previous message, but it's unsupported by your client.</body>"
         '<store xmlns="urn:xmpp:hints"/></message>',
    "C": '<message to="{to}" type="chat"><body>secret {id} EDITED</body>'
         '<replace xmlns="urn:xmpp:message-correct:0" id="{id}"/></message>',
}


class Sender(slixmpp.ClientXMPP):
    def __init__(self, user, to, order, id_):
        super().__init__(f"{user}@{DOMAIN}/seq", PASSWORD)
        self.to, self.order, self.id = f"{to}@{DOMAIN}", order, id_
        self.ssl_context.check_hostname = False
        self.ssl_context.verify_mode = ssl.CERT_NONE
        self.add_event_handler("session_start", self.start)

    async def start(self, _):
        self.send_presence()
        for step in self.order:
            self.send_raw(STANZAS[step].format(to=self.to, id=self.id))
            await asyncio.sleep(1)
        self.disconnect()


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("user"); p.add_argument("to")
    p.add_argument("--order", required=True); p.add_argument("--id", required=True)
    a = p.parse_args()
    xmpp = Sender(a.user, a.to, a.order.split(","), a.id)
    xmpp.connect(host="127.0.0.1", port=5222)
    asyncio.get_event_loop().run_until_complete(xmpp.disconnected)
