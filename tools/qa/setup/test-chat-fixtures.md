# Local test chat fixtures

The dedicated `tatrak-qa-prosody` container uses the public test-server definitions from
`../test-server/`. Its volume stores the generated TLS certificate, disposable accounts,
rosters and message archives. See [TESTING.md](../TESTING.md) for container creation.

`scripts/seed-test-chats.py` runs inside that container using its installed slixmpp package.
It connects only to container localhost, validates TLS against the exact local certificate with
hostname checks enabled, and touches the fixed `tester` and `friend` fixture accounts.
The password `secret123` is a public, disposable test-server default, not a personal credential.

```sh
podman cp scripts/seed-test-chats.py tatrak-qa-prosody:/seed-test-chats.py
podman exec tatrak-qa-prosody python3 /seed-test-chats.py
```

The script sets mutual contact display names (`friend` and `tester`), establishes `both`
subscriptions and exchanges unique `tatrak-qa-bootstrap-...` messages. It reports the server-observed
roster state and receipt of its bootstrap messages. This helper prepares fixtures; it is not an
application UI test or proof of OMEMO operation.

Seeding archived messages before first login alone did not create visible chats in the fresh app.
After both app clients signed in, the recorded run created a live conversation using the test-server
helper, then confirmed the row was visible on each client:

```sh
podman exec tatrak-qa-prosody python3 /send.py friend tester tatrak-qa-initial-live-chat
```

Before running pytest, verify both clients show their intended mutual chat and have empty composers.
Do not clear shared app data to prepare cases. The login case requires a separate fresh installation;
the completed run used `qa-login@10.0.2.2` on a third AVD.
