#!/bin/sh
set -e
mkdir -p /run/prosody /var/lib/prosody/certs
chown prosody:prosody /run/prosody
c=/var/lib/prosody/certs/10.0.2.2
if [ ! -f "$c.crt" ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=10.0.2.2" \
    -addext "subjectAltName=IP:10.0.2.2,DNS:upload.10.0.2.2,DNS:stickers.10.0.2.2,DNS:conference.10.0.2.2" \
    -keyout "$c.key" -out "$c.crt" 2>/dev/null
  chown prosody:prosody "$c.key" "$c.crt"
fi
for u in tester friend; do prosodyctl register "$u" 10.0.2.2 secret123 2>/dev/null || true; done
exec runuser -u prosody -- prosody -F
