# How P71 Link learned the watch protocol

P71 Link controls the watch through its existing Bluetooth Low Energy (BLE) interface.
The work recovered the command language that Pubu Wear uses, then checked selected commands on one P71J.

Two separate questions guided the work:

1. Where does the phone send commands and receive replies?
2. What do the bytes in those messages mean?

## 1. Use Android as the Bluetooth bridge

Remote access reached the Android phone through its existing private network connection.
Android Debug Bridge (ADB) provided the phone-side tools. The phone's Bluetooth radio connected to the watch.
Initial command tests used nRF Connect automation. P71 Link later replaced that bridge with direct Android BLE calls.
The native app does not require ADB, Tailscale, nRF Connect, or a Pubu Wear account.

This access path does not expose the watch directly to the internet.

## 2. Discover the channels

A BLE device exposes services, which group characteristics.
A characteristic is a named channel for reads, writes, or asynchronous notifications.
Android service discovery lists these channels and their properties.
The [Android GATT API](https://developer.android.com/reference/android/bluetooth/BluetoothGatt) describes these operations.

For the short-command protocol, the identified channels are:

| Role | UUID |
| --- | --- |
| Service | `6e40fff0-b5a3-f393-e0a9-e50e24dcca9e` |
| Write commands | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| Receive replies | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |

These characteristics are the Bluetooth equivalent of communication endpoints.
Many commands share the same write characteristic. Each function does not need a separate characteristic.
Service discovery reveals the available channels, but it does not explain a vendor's private payload format.
A UUID alone also does not identify the watch's chipset.

## 3. Inspect the companion app's command builders

Static inspection examined Pubu Wear `com.pubu.dafit`, version 1.0.0.39, version code 46.
Androguard 4.1.3 exposed the packaged bytecode, request classes, response classes, and command constants.
The [capability map](CAPABILITIES.md#source-identity-and-evidence-boundary) records the inspected APK identity and evidence boundary.

The request classes showed how the app builds outgoing messages.
The response classes showed which reply bytes the app interprets.
This produced a candidate command map before direct device tests.

| Command | Meaning in the inspected protocol |
| --- | --- |
| `0x03` | Battery query |
| `0x06` | Scheduled DND settings |
| `0x16` | Automatic heart-rate settings |
| `0x72` | Text notification |

Static code establishes what the app can request. It does not prove that this watch supports every request.

## 4. Recover the packet format

Each short request has 16 bytes:

```text
command          payload                  checksum
1 byte           14 bytes                 1 byte
```

The checksum is the sum of the first 15 bytes, modulo 256.
For example, a battery query contains command `03`, fourteen zero payload bytes, and checksum `03`:

```text
03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03
```

The command selects the operation. The payload supplies its parameters.
Several settings use action `1` for a read and action `2` for a write. Individual command schemas define exceptions.
A reply arrives through the notification characteristic, then the app checks its length, command, and checksum.
The checksum checks packet consistency. It is not encryption or proof of sender identity.

Larger resource transfers use additional framing and state.
The 16-byte format does not describe every watch-face image packet or firmware-transfer packet.
The original implementations are [ShortProtocol.java](android/protocol/src/main/java/dev/p71link/protocol/ShortProtocol.java)
and [BulkProtocol.java](android/protocol/src/main/java/dev/p71link/protocol/BulkProtocol.java).

## 5. Compare code with observed exchanges

Selected Pubu diagnostic logs captured a normal connection exchange.
The observed requests and replies helped check the command map, packet format, and connection sequence.
The [connection report](NORMAL_CONNECTION.md) describes the capture and its limits.
It is a filtered app-log excerpt, not a complete over-the-air Bluetooth capture.
Log order does not establish radio order.

The normal connection included reads and state synchronization, such as clock and time-format writes.
It also returned capability flags. An advertised flag does not establish complete feature operation.

## 6. Validate selected commands on the watch

Tests started with understood reads. Reversible control tests preserved the original state.
DND and automatic health-control tests changed settings, read them back, then restored them.
The tester separately confirmed a visible text message, face selection and restoration, and the Orbit dragon background.

Three forms of evidence remain separate:

| Evidence | What it establishes |
| --- | --- |
| Reply received and checked | The watch responded through the tested protocol path |
| Setting read back and restored | The tested configuration changed and returned to its original value |
| Visible result confirmed | The tester saw the requested display result |

For example, the tested brightness query returned an error.
A declared command in the app therefore does not guarantee support on this target.
No malformed-packet exploit or encryption break was required for this work.

## 7. Implement the independent companion

P71 Link implements the recovered protocol in original Java code.
The app connects directly, serializes requests, validates replies, and exposes supported controls.
It preserves received health records and labels derived summaries. Missing data does not receive synthetic replacement values.

The discovery and implementation flow was:

```text
Discover channels -> inspect app bytecode -> compare diagnostic logs
                  -> test reads and reversible controls -> implement and verify
```

## What remains unknown

Protocol control does not establish arbitrary memory access or firmware modification.
No stock firmware image or complete installed-firmware readback path has been obtained.
The watch's exact internal command dispatch and sensor algorithms remain unknown.
Command success does not establish physiological accuracy, lower energy use, or support on other watch models.

See [firmware access limits](FIRMWARE_ACCESS.md), [health data rules](HEALTH_INTEGRITY.md),
and the [feature tracker](FEATURES.md) for the separate evidence and open checks.
This repository does not distribute the proprietary APK, disassembly, credentials, or private device captures.
