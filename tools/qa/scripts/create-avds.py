"""Create two isolated AVD definitions; existing AVDs and their data are preserved."""
import os
import argparse
from pathlib import Path

sdk = Path(os.environ["ANDROID_HOME"])
parser = argparse.ArgumentParser()
parser.add_argument("--include-login", action="store_true")
args = parser.parse_args()
avds = Path(os.environ["ANDROID_AVD_HOME"])
image = sdk / "system-images/android-31/default/x86_64"
avds.mkdir(parents=True, exist_ok=True)
names = ["Tatrak_QA_A", "Tatrak_QA_B"]
if args.include_login:
    names.append("Tatrak_QA_Login")
for name in names:
    target = avds / f"{name}.avd"
    config = target / "config.ini"
    if config.exists():
        print(f"Preserving existing {name}")
        continue
    target.mkdir(parents=True, exist_ok=True)
    settings = {
        "AvdId": name, "avd.ini.displayname": name, "avd.ini.encoding": "UTF-8",
        "abi.type": "x86_64", "tag.id": "default", "tag.display": "Default",
        "image.sysdir.1": str(image) + "/", "disk.dataPartition.size": "2G",
        "hw.cpu.arch": "x86_64", "hw.cpu.ncore": "2", "hw.ramSize": "1536",
        "hw.lcd.width": "720", "hw.lcd.height": "1280", "hw.lcd.density": "320",
        "hw.gpu.enabled": "yes", "hw.gpu.mode": "swiftshader",
        "hw.keyboard": "yes", "hw.battery": "yes", "hw.gps": "yes",
        "hw.initialOrientation": "Portrait", "hw.audioInput": "no",
        "showDeviceFrame": "no", "fastboot.forceColdBoot": "yes",
        "PlayStore.enabled": "false", "runtime.network.latency": "none",
        "runtime.network.speed": "full", "vm.heapSize": "256",
    }
    config.write_text("".join(f"{key}={value}\n" for key, value in settings.items()))
    (avds / f"{name}.ini").write_text(f"avd.ini.encoding=UTF-8\npath={target}\ntarget=android-31\n")
    print(f"Created {name}")
