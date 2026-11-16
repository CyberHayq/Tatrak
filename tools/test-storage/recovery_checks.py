#!/usr/bin/env python3
"""Reversible fault checks on encrypted, backed-up Tatrak debug emulator data."""
import hashlib
import pathlib
import subprocess
import sys

ADB = str(next(p for p in (pathlib.Path.home() / 'Android/platform-tools/adb', pathlib.Path.home() / 'Android/Sdk/platform-tools/adb') if p.exists()))  # the SDK layout differs between the dev machines
PACKAGE = 'am.cyberhayq.chat'
ROOT = 'no_backup/tatrak-databases'
DEVICE = sys.argv[1]
if not DEVICE.startswith('emulator-'):  # never a real phone
    raise SystemExit('Only test emulators are allowed')
OUTPUT = pathlib.Path('build/egm-implementation')
if not (OUTPUT / (DEVICE + '-before.tar')).is_file():
    raise SystemExit('Back up app data before running this fault test')

def adb(*args, input=None):
    return subprocess.check_output([ADB, '-s', DEVICE, *args], input=input)

def stop():
    adb('shell', 'am', 'force-stop', PACKAGE)

def read(path):
    return adb('exec-out', 'run-as', PACKAGE, 'cat', path)

def write(path, data):
    adb('shell', 'run-as', PACKAGE, 'sh', '-c', f"'cat > {path}'", input=data)

def run(mode, label, extra=()):
    result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'mode', mode, *extra,
                 PACKAGE + '/am.cyberhayq.chat.storage.StorageInstrumentation')
    (OUTPUT / (DEVICE + '-' + label + '.txt')).write_bytes(result)
    if b'INSTRUMENTATION_CODE: -1' not in result or b'PASS ' not in result:
        raise AssertionError(result.decode())
    print(label, result.decode().splitlines()[0])

stop()
active = read(ROOT + '/active')
generation = active.decode('ascii').split(':')[1]
key_path = ROOT + '/' + generation + '/key'
key = read(key_path)
for name, data in [('key', key), ('active', active)]:
    saved = OUTPUT / (DEVICE + '-original-' + name)
    saved.write_bytes(data)
    saved.chmod(0o600)
recovered = []
try:
    # The durable commit intent wins over a torn active pointer after interruption.
    write(ROOT + '/commit', active)
    write(ROOT + '/active', b'torn')
    run('check', 'pending-commit')
    stop()
    assert read(ROOT + '/active') == active
    assert adb('shell', 'run-as', PACKAGE, 'sh', '-c', f"'test ! -e {ROOT}/commit; echo $?' ").strip() == b'0'
    before = {name: hashlib.sha256(read(ROOT + '/' + generation + '/' + name)).digest()
              for name in ('history.db', 'push.db', 'key')}
    # Authenticated key unwrap failure must leave both DBs and selection intact.
    damaged = bytearray(key)
    damaged[-1] ^= 1
    write(key_path, damaged)
    run('recovery', 'key-authentication-failure')
    stop()
    assert read(ROOT + '/active') == active
    for name in ('history.db', 'push.db'):
        assert hashlib.sha256(read(ROOT + '/' + generation + '/' + name)).digest() == before[name]
    assert read(key_path) == damaged
    # Capture the user-facing recovery screen while the fault is present.
    adb('shell', 'cmd', 'statusbar', 'collapse')
    adb('shell', 'am', 'start', '-n', PACKAGE + '/eu.siacs.conversations.ui.ConversationActivity')
    import time
    time.sleep(3)
    (OUTPUT / (DEVICE + '-recovery.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    stop()
    run('restore', 'logical-key-loss-restore')
    stop()
    recovered.append(read(ROOT + '/active').decode('ascii').split(':')[1])
    assert recovered[-1] != generation
    # the media of the restored backup is encrypted for the recovered generation (the instrumentation read it back)
    listing = adb('shell', 'run-as', PACKAGE, 'ls', 'files/attachments').decode()
    assert any(name.startswith('tatrak_' + recovered[-1] + '_') for name in listing.split())
    write(ROOT + '/active', active)
    run('fresh', 'explicit-fresh-start')
    stop()
    recovered.append(read(ROOT + '/active').decode('ascii').split(':')[1])
    assert recovered[-1] != generation
finally:
    stop()
    write(key_path, key)
    write(ROOT + '/active', active)
    adb('shell', 'run-as', PACKAGE, 'rm', '-f', ROOT + '/commit')
run('check', 'restored-key', ('-e', 'cleanup', ','.join(recovered)) if recovered else ())
print('PASS: interrupted selection, key failure, logical restore with media, explicit fresh start and original reopen; test generations and their media removed')
