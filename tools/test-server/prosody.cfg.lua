-- Test server for the Android emulator: the host is 10.0.2.2 from inside the emulator,
-- so that IP is the XMPP domain (no DNS needed). Self-signed certificate; accept it in the app.
-- an admin sees the server's admin commands (XEP-0133) in the chat with 10.0.2.2
admins = { "tester@10.0.2.2" }
modules_enabled = {
    "roster"; "saslauth"; "tls"; "disco"; "carbons"; "pep"; "private"; "blocklist";
    "vcard4"; "vcard_legacy"; "version"; "uptime"; "time"; "ping"; "register";
    "mam"; "offline"; "csi_simple"; "smacks"; "bookmarks"; "server_contact_info"; "lastactivity";
    "invites"; "invites_register"; "admin_shell"; "adhoc"; "admin_adhoc";
}
allow_registration = true
-- invite tokens are checked when given (an invalid one is refused), plain registration stays open
registration_invite_only = false
c2s_require_encryption = true
s2s_require_encryption = true
authentication = "internal_hashed"
storage = "internal"
archive_expires_after = "never"
log = { info = "*console" }
pidfile = "/run/prosody/prosody.pid"

http_ports = { }
https_ports = { 5281 }
http_interfaces = { "*" }
https_interfaces = { "*" }

-- in the data volume, so the app keeps trusting the same certificate after a rebuild
certificates = "/var/lib/prosody/certs"

VirtualHost "10.0.2.2"

Component "conference.10.0.2.2" "muc"
    modules_enabled = { "muc_mam" }

-- regular uploads
Component "upload.10.0.2.2" "http_file_share"
    http_host = "10.0.2.2"
    http_external_url = "https://10.0.2.2:5281/"
    http_paths = { file_share = "/upload" }

-- permanent sticker packs (see knowledge/ops/prosody-stickers.md)
Component "stickers.10.0.2.2" "http_file_share"
    http_host = "10.0.2.2"
    http_external_url = "https://10.0.2.2:5281/"
    http_paths = { file_share = "/stickers" }
    http_file_share_expires_after = "never"
    http_file_share_allowed_file_types = { "image/webp", "application/json" }
    http_file_share_size_limit = 1024*1024
