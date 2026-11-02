# P71 Link

An independent Android companion app and BLE protocol toolkit for the COLMI P71J.
The app uses native Java and direct Android Bluetooth. It has no third-party runtime libraries or account requirement.

## Compatibility

- Android 13 or later; target API 36.
- Tested watch: P71J, hardware M71J_V1.0, firmware M71J_1.00.27_231223.
- This development build connects only to the exact Bluetooth name `P71J_CEC9`. Other P71 watches are ignored.
- To target another watch, inspect `TARGET_NAME` in `WatchManager.java`, rebuild, and validate its protocol compatibility.
- RTL8763EWE is a working chipset identification. The physical P71J board remains unconfirmed.

This is a companion app, not replacement watch firmware. It has no firmware flash or backup operation.

## Download

The [0.4.0 development release](https://github.com/adxoxo/p71-link/releases/tag/v0.4.0-dev) contains the signed APK and its SHA-256 checksum.
This release uses versionCode 5 and includes the four-tab redesign, Orbit artwork and Davao heat-index tools.
Allow APK installation from your chosen browser or file manager, then open P71 Link and choose Connect.
Disconnect Pubu Wear and other BLE clients first. Preserve their data.
The APK uses a development certificate. A local build creates its own private key.
An update must use the same certificate as the installed app. A different certificate requires an uninstall, which removes local app data.

## Features and status

Watch settings include scheduled DND, wrist wake, display timeout, time, units, goals and supported reminders.
Faces include a visual picker with installed-face cards and selection, the bundled Orbit background with the aquryu logo,
custom photo crop, resource transfer, file import and an optional vendor catalog.
Installed faces show catalog artwork only after an opt-in load and an exact file-name match. Other faces say Preview unavailable.
Health pages preserve received activity, heart-rate and sleep records, with local storage and explicit JSON export.
Optional phone functions include selected notifications, call alerts, contacts, weather, music controls and find-phone events.
The Weather page also calculates a Davao heat-index estimate from fresh Open-Meteo temperature and humidity data.
It can send the estimate as watch text. Optional scheduled sends are off by default and use separate local settings.
These are forecast-derived estimates, not watch sensor values or PAGASA station observations.

Camera and oxygen features are excluded. Blood-pressure measurements and synthetic health values are also excluded.
Photo selection for face backgrounds uses the system picker and needs no camera permission.
Missing samples stay unavailable. Sleep stages remain watch classifications, and summaries identify derived values.
No sensor accuracy or battery-life improvement is established.

- **Code verified:** 148 protocol checks, 25 heat-index tests, 3076 independent LZO decode cases and 7 Python tests passed for the reviewed source.
- **Live path verified:** direct connection, identity, reversible setting readbacks, text display, face selection and restoration, history persistence and export.
- **Live path verified (user):** Orbit applied from the 0.2 test build shows the dragon face on the watch.
- **Live acceptance open:** vendor face uploads, catalog preview display, the 0.3 redesign on the phone, calls, notification forwarding, contacts, weather, reminders, music and find-phone events.
- **Device issue open:** an Open-Meteo fetch error was reported on the phone. Its cause and heat-index text display remain unverified.
- **Production outcome open:** no store release or broad device validation.

See [FEATURES.md](FEATURES.md) for the BLE feature tracker. It separates implementation, offline checks and physical results.
The heat-index status above covers the new experimental weather path.
Full Pubu Wear parity is not claimed.

## Build

Use a Linux shell, Python 3.10+, JDK 21, Android SDK platform 36 and build-tools 36.0.0.
The build uses aapt2, javac, d8, zipalign and apksigner. It does not use Gradle.

```sh
export JDK=/path/to/jdk-21
export ANDROID_SDK=/path/to/android-sdk
VERSION_CODE=5 VERSION_NAME=0.4.0-dev sh android/build.sh
# Output: android/build/p71link-debug.apk
```

Set both paths explicitly. Use the version overrides above for the 0.4.0 release.
The scripts retain the earlier version defaults and paths from the original development environment.
The first build creates a development key in ignored `android/.local/`. Keep it private for compatible updates.
No signing key or password is included in this repository.

## Tests

The independent LZO check uses the separate `lzo1x` Node package as a test dependency. It is not bundled into the app.

```sh
npm install --prefix /tmp/p71-link-test lzo1x@1.0.1
export JDK=/path/to/jdk-21
export LZO1X_CJS=/tmp/p71-link-test/node_modules/lzo1x/dist/index.cjs
sh android/test.sh
python3 -m unittest -v test_colmi.py
```

The Android test command fails with exit 2 if the independent decoder is absent. It does not claim that skipped checks passed.
Regenerate the feature tracker with `python3 -I android/tools/features.py .`.

## Data and connections

Connect and history sync are explicit actions. There is no idle polling.
Optional persistent connection uses a connected-device foreground service. Disconnect stops it.
A lost write or timeout closes the session; commands are not replayed. Established controls use separate readbacks.
Notifications are off by default with an empty app list. Forwarded notification text is not stored or logged.
Health records stay in private app storage until an explicit export.
The face catalog and vendor forecast use their external services through user-opened features.
Heat-index refresh and send use Open-Meteo. Enabled auto send makes a fresh request at each scheduled time.
Scheduled sends require an existing watch connection. Missed sends have no catch-up or automatic retry.
After a phone reboot, open Weather to arm the schedule again.
These optional services are external dependencies. Their availability is not guaranteed.

## Protocol research

- [Capability map](CAPABILITIES.md), [structured catalog](capabilities.json) and [command index](commands.csv).
- [Normal connection exchange](NORMAL_CONNECTION.md).
- [Firmware access limits](FIRMWARE_ACCESS.md).
- [Health data rules](HEALTH_INTEGRITY.md) and [bounded static sensor findings](SENSOR_DATA.md).

The implementation is original code based on protocol analysis and bounded device tests.
No proprietary APK, disassembly, firmware image, vendor face file or private health capture is distributed here.
The project is independent of COLMI and Pubu Wear.

## Legacy Python toolkit

`colmi.py` creates nRF Connect automation files and uses Android as a Bluetooth bridge.
It requires Python 3.10+, ADB and nRF Connect for Mobile. It has no Python package dependencies.
The native Android app does not require this bridge.

## Use

Replace the address and serial with your current device values.
ADB wireless ports can change after a reconnect.

```sh
python3 colmi.py plan info --address AA:BB:CC:DD:EE:FF
python3 colmi.py run info --address AA:BB:CC:DD:EE:FF --serial PHONE_IP:PORT
python3 colmi.py plan brightness --address AA:BB:CC:DD:EE:FF
python3 colmi.py plan notify --address AA:BB:CC:DD:EE:FF --text 'Time for a break'
python3 colmi.py run notify --address AA:BB:CC:DD:EE:FF --serial PHONE_IP:PORT --text 'Time for a break' --apply
python3 -m unittest -v
```

`plan` prints XML and sends nothing. The XML can also be imported into nRF Connect.
`run notify` and `run find` require `--apply` because they affect the watch.
Watch display and vibration require physical confirmation after a successful Bluetooth write.
Protocol queries require a captured response before a successful result can be claimed.
Use `decode COMMAND --hex PACKET` to check one response checksum and command identity.
Settings read responses must also contain the read action byte.
The toolkit retains task-owned files after a timeout because the phone test may still run.
Resolve a timed-out test before another operation.

## References

- [FT100 research method](https://lessonsec.com/posts/reversing_the_ft100_ble_fitness_bracelet/)
- [FT100 reference tools](https://github.com/0xless/FT100_fitness_bracelet_reversing)
- [nRF Connect automation](https://github.com/nordicsemi/Android-nRF-Connect/tree/main/documentation/Automated%20tests)

FT100 uses different transport UUIDs and frames. Its packet bytes are not used here.
