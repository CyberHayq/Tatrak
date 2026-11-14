"""Send messages as a test user, e.g. from the host:
  podman exec tatrak-prosody python3 /send.py friend tester "Hi" "Second message"
  podman exec tatrak-prosody python3 /send.py friend tester --file /path/in/container.webp
Users are <name>@10.0.2.2 with password secret123 (registered by start.sh)."""
import asyncio, mimetypes, os, ssl, sys, urllib.request, uuid
from xml.etree import ElementTree
import slixmpp

DOMAIN, PASSWORD = "10.0.2.2", "secret123"
CERTIFICATE = f"/var/lib/prosody/certs/{DOMAIN}.crt"  # made by start.sh, covers IP 10.0.2.2


class Sender(slixmpp.ClientXMPP):
    def __init__(self, user, to, items):
        super().__init__(f"{user}@{DOMAIN}/send", PASSWORD)
        self.to, self.items = f"{to}@{DOMAIN}", items
        self.ssl_context.check_hostname = False
        self.ssl_context.verify_mode = ssl.CERT_NONE
        self.register_plugin("xep_0030")
        self.register_plugin("xep_0066")
        self.register_plugin("xep_0363")
        self.add_event_handler("session_start", self.start)

    async def start(self, _):
        self.send_presence()
        if self.items[0:1] == ["--bulk"]:
            count = int(self.items[1])
            previous_id = None
            for i in range(count):
                message = self.make_message(mto=self.to, mbody=f"Performance message {i}", mtype="chat")
                message["id"] = uuid.uuid4().hex
                if i > 0 and i % 3 == 0:
                    message.xml.append(ElementTree.Element("{urn:xmpp:reply:0}reply", {"id": previous_id}))
                message.send()
                previous_id = message["id"]
                await asyncio.sleep(0.01)
            await asyncio.sleep(1)
            self.disconnect()
            return
        files = iter(self.items)
        for item in files:
            if item == "--file":
                url = await self.upload(next(files))
                m = self.make_message(mto=self.to, mbody=url, mtype="chat")
                m["oob"]["url"] = url
                m.send()
            else:
                self.send_message(mto=self.to, mbody=item, mtype="chat")
            await asyncio.sleep(0.3)
        self.disconnect()

    async def upload(self, path):
        """XEP-0363 by hand: slixmpp's upload_file checks against the system CAs and rejects
        the self-signed certificate, so PUT with that certificate as the only trusted one."""
        size = os.path.getsize(path)
        kind = mimetypes.guess_type(path)[0] or "application/octet-stream"
        slot = await self["xep_0363"].request_slot(
            f"upload.{DOMAIN}", os.path.basename(path), size, content_type=kind)
        put = slot["http_upload_slot"]["put"]
        headers = {"Content-Type": kind, "Content-Length": str(size)}
        headers.update({h["name"]: h["value"] for h in put["headers"]})
        request = urllib.request.Request(
            put["url"], data=open(path, "rb").read(), headers=headers, method="PUT")
        context = ssl.create_default_context(cafile=CERTIFICATE)
        await asyncio.to_thread(urllib.request.urlopen, request, context=context, timeout=20)
        return slot["http_upload_slot"]["get"]["url"]


if __name__ == "__main__":
    user, to, *items = sys.argv[1:]
    xmpp = Sender(user, to, items)
    xmpp.connect(host="127.0.0.1", port=5222)
    asyncio.get_event_loop().run_until_complete(xmpp.disconnected)
