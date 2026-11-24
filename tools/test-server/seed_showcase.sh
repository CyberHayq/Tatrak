#!/bin/sh
# Showcase demo data for the store screenshots (knowledge/ops/store-screenshots.md).
#   tools/test-server/seed_showcase.sh setup              accounts, media, roster, rooms, bookmarks
#   tools/test-server/seed_showcase.sh history --now 09:00 --tz +0400
#   tools/test-server/seed_showcase.sh online --minutes 30 --typing aram
#   tools/test-server/seed_showcase.sh reset              rooms destroyed, showcase accounts deleted
# Every podman call has a time limit; CONTAINER overrides the container name.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
c=${CONTAINER:-tatrak-prosody}
timeout 30 podman cp "$here/seed_showcase.py" "$c:/seed_showcase.py"
users=$(timeout 30 podman exec "$c" python3 /seed_showcase.py users 2>/dev/null)
case "$1" in
  setup)
    media=$(mktemp -d)
    trap 'rm -rf "$media"' EXIT
    python3 -I "$here/../store-screenshots/make_media.py" "$media" >/dev/null
    timeout 30 podman exec "$c" rm -rf /tmp/showcase
    timeout 30 podman cp "$media" "$c:/tmp/showcase"
    for u in $users; do  # idempotent: registering again sets the same password
      timeout 30 podman exec "$c" prosodyctl register "$u" 10.0.2.2 secret123
    done
    timeout 300 podman exec "$c" python3 /seed_showcase.py "$@" ;;
  online)  # stays up for --minutes
    timeout 7200 podman exec "$c" python3 /seed_showcase.py "$@" ;;
  reset)
    timeout 120 podman exec "$c" python3 /seed_showcase.py reset
    for u in $users; do
      timeout 30 podman exec "$c" prosodyctl deluser "$u@10.0.2.2" || true
    done ;;
  *)
    timeout 300 podman exec "$c" python3 /seed_showcase.py "$@" ;;
esac
