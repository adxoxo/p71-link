# P71J firmware package and installed-image access

Evidence date: 2026-11-02.

No exact stock firmware package has been obtained for M71J_V1.0 and M71J_1.00.27_231223.
No full-firmware readback path has been established through the current Bluetooth connection.
These results do not prove that no package or private readback command exists.

## What is available from the installed firmware

The earlier live GATT read returned `M71J_1.00.27_231223`.
The normal Pubu connection returned two identical capability replies.
Those replies describe features and limits, as recorded in [NORMAL_CONNECTION.md](NORMAL_CONNECTION.md).
The control tests also established several configuration reads and reversible writes.

These results come from firmware that executes on the watch.
They do not contain the firmware program bytes.
A firmware version string is metadata. It is not a firmware image or backup.

## Package search boundary

| Source | Result | Limit |
| --- | --- | --- |
| Last successful authenticated OTA lookup | No upgraded version; null data; no download URL | Latest-update lookup only |
| Exact hardware and firmware public search | No verified P71J binary candidate found | Indexed public results; not all vendor storage |
| Pubu external files directory | Five file names inspected; model matches were guide PNG files | File-name scan to depth six |
| Pubu external cache directory | Three file names inspected; no firmware candidate | File-name scan to depth six |
| Pubu private internal cache | Access denied because the app is not debuggable | No internal-cache contents inspected |
| Installed Pubu APK | No identified M71J firmware package | Candidate archive members and selected headers inspected |

The APK candidate named DebugProbesKt.bin has the Java class magic CAFEBABE.
It is not a P71J firmware image.
The nested assets/ae/res.zip contains global.db and a macOS sidecar.
No named firmware member exists inside that archive. The global.db resource remains opaque and has a GDB0 header.
No firmware identity has been established for that resource.

The last successful server check used the exact hardware and installed version with a guest token.
Further metadata probes used the phone locale, US, and the same hardware.
The expanded US lookups timed out. They supplied no new update-availability result.
The synthetic version-floor probe changes request metadata only. It does not change the watch.

## What the Pubu update handler reads

The inspected QC/Oudmon DfuHandle uses these steps:

1. checkFile opens a local phone file with RandomAccessFile.
2. It reads that file into the dfuData byte array.
3. readNextBigPocket copies up to 1024 bytes from that array.
4. sendPocketToBle sends those bytes toward the watch.

The word read in that method name refers to the phone-side update buffer.
It does not describe a flash download from the watch.
The inspected handler contains update control and reply paths, but no complete firmware export operation was found.

A focused method-name search also found no explicit firmware-backup, read-flash, or read-memory method in the inspected QC/Oudmon namespaces.
Method-name absence alone cannot exclude obfuscated code or an undocumented firmware command.
The existing command map does not define a flash-address and length request for a firmware dump.

## Realtek reference evidence

The [official OTA service source](https://github.com/rtkconnectivity/RTL87x3E-Common-SDK/blob/main/src/sample/common/app_ble_service/ota_service.c#L552) exposes reads for device and image metadata.
The inspected read callback returns addresses, device information, image versions, protocol information, and section sizes.
It does not export the image bytes.

The [reference DFU command handler](https://github.com/rtkconnectivity/RTL87x3E-Common-SDK/blob/main/src/sample/ota_demo/app_ota.c#L2000) does not establish a full flash export operation.
Its [readback buffer](https://github.com/rtkconnectivity/RTL87x3E-Common-SDK/blob/main/src/sample/ota_demo/app_ota.c#L1373) verifies a local flash write with a byte comparison.
It does not send the firmware back to a phone.

These are reference SDK implementations. They are not the P71J firmware source.
Their service identifiers also differ from the active Pubu QC transfer path.
They cannot establish which private commands the P71J supports.

## Remaining access routes

An exact stock or recovery package from the manufacturer could provide the program bytes without a device dump.
A physical debug interface or direct flash read could provide another route, if the board exposes it and protection permits access.
The exact pins, protection state, flash layout, and recovery method remain unknown for this board.
No watch teardown, debug-port connection, bootloader transition, or firmware write occurred during this investigation.

The installed firmware can still be examined through its protocol behavior.
Binary analysis of that firmware requires an actual image, which remains unavailable.

## Artifact identity

The source APK is Pubu Wear com.pubu.dafit 1.0.0.39, version code 46.
Its SHA-256 is `6f254bc8ffa8c6156072db2e30a40bf835431b955d8ca5873ea49fdbc2ee7814`.
The firmware cache scan, server responses, and readback source notes retain separate evidence scopes.
