# P71J capability map

Evidence date: 2026-11-02.

This map covers the QC/Oudmon protocol in Pubu Wear 1.0.0.39 and limited tests on one P71J.
It is a complete inventory of the selected request and response namespaces, with partial payload and live behavior coverage.
It does not establish every function inside the watch firmware.

The structured catalog is [capabilities.json](capabilities.json). The constant index is [commands.csv](commands.csv).
The [normal connection report](NORMAL_CONNECTION.md) now contains the target capability values.

## Device identity

| Item | Evidence |
| --- | --- |
| Bluetooth name | P71J_CEC9 |
| Hardware | M71J_V1.0, from Pubu diagnostic data |
| Firmware | M71J_1.00.27_231223, from a live GATT read |
| Working chipset | Realtek RTL8763EWE, from user research and COLMI generic P71 specifications |
| Exact P71J board | Unconfirmed |
| OS or RTOS | Unresolved |

The [COLMI P71 specification](https://pt.colmi.info/products/colmi-p71-smartwatch) names RTL8763EWE.
That model specification does not confirm the exact board revision inside this P71J.
Chip identity alone does not establish firmware write access, boot validation, or a recovery method.

## Confirmed target results

| Function | Opcode | Result | Remaining check |
| --- | --- | --- | --- |
| Capability report | 01 | Two identical valid replies during normal Pubu connection | Functional tests for advertised features |
| Battery | 03 | Valid reply: 38% at test time | Battery state is time dependent |
| Short notification | 72 | Valid reply; The tester saw test message | Long text and other notification types |
| Scheduled DND | 06 | Enable, readback, restore, and final read passed | Actual notification suppression |
| Automatic heart rate | 16 | Enable, readback, restore, and final read passed | Actual samples, interval units and limits |
| Automatic oxygen | 2C | Enable, readback, restore, and final read passed | Actual samples |
| Brightness | 1B | Read rejected: 9B EE | Another supported control path or protocol variant |

All three changed controls returned to their original off state.
The brightness result applies to this query. It does not prove that the screen lacks local brightness controls.

## Evidence labels

- `verified_capability_reply`: the target supplied a valid capability report; feature operation remains separate.
- `observed_response_opcode`: captured response metadata confirms the opcode; payload interpretation remains untested.
- `verified_read`: a captured reply passed command and checksum checks.
- `verified_write_readback_restored`: separate reads confirmed the change and original state after restoration.
- `user_confirmed_display`: The tester confirmed the visible result.
- `rejected_on_target`: the watch returned an error for the tested request.
- `sdk_only`: static app evidence exists; target behavior remains untested.
- `unverified`: the inventory contains a declaration; schema or target support remains unresolved.

A status applies to the named operation. It does not validate every alias with the same numeric value.

## Short command transport

The short command service is `6e40fff0-b5a3-f393-e0a9-e50e24dcca9e`.
The write characteristic is `6e400002-b5a3-f393-e0a9-e50e24dcca9e`.
The notify characteristic is `6e400003-b5a3-f393-e0a9-e50e24dcca9e`.
Each short request has one command byte, 14 payload bytes, and one checksum byte.
The checksum is the sum of the first 15 bytes, modulo 256.
In the schemas below, payload indexes exclude the command and checksum.

| Function | Schema |
| --- | --- |
| Battery (03) | Empty request. Reply payload[0] is battery percent. |
| Scheduled DND (06) | Read [1]. Write [2, flag, startHour, startMinute, endHour, endMinute]. Flag: 1 enabled, 2 disabled. Read reply: action, flag, four schedule bytes, manual-DND flag. |
| Automatic heart rate (16) | Read [1]. Write [2, flag, interval]. Flag: 1 enabled, 2 disabled. Read reply: action, flag, raw interval. Interval units and limits remain unresolved. |
| Brightness (1B) | SDK read [1]; SDK write [2, level]. Target rejected read with 9B EE. Range unknown. No write test. |
| Automatic blood oxygen (2C) | Read [1]. Write [2, enabled]. Enabled: 0 off, 1 on. Read reply: action, enabled. |
| Find watch (50) | SDK request [0x55, 0xAA]. Vibration and display untested. |
| Text notification (72) | Payload: type, chunk count, one-based chunk index, 11 UTF-8 bytes. Final chunk is zero padded. Only one short SMS-style message tested. |

The notification sender truncates Java text to 256 UTF-16 code units before UTF-8 fragmentation.
Notification types include call, SMS, QQ, WeChat, Facebook, WhatsApp, Twitter, Skype, and Line.
Only SMS type 1 has a confirmed visible result.
The app recognizes error opcodes as the requested command with bit 7 set for the inspected short-command path.
Only the captured brightness error establishes that behavior on this target.

The inferred firmware flow is: receive bytes, validate the frame, dispatch the command, apply its payload, and send a reply.
This flow is an inference from the app and captured replies. No firmware code or malformed-frame test confirms its internal implementation.

## Static request inventory

The selected namespaces contain 74 request classes and 58 response classes.
There are 75 declared CMD constants and 45 selected extended transport classes.
These counts include nested helpers and aliases. Some CMD constants represent metadata instead of wire opcodes.
CMD_DATA_LENGTH has an encoded default of zero. Constants.<clinit> sets its runtime value to 16.
The CSV records both values. CMD_DATA_LENGTH is frame-length metadata.
CMD_PACKAGE_LENGTH instead names the device response 2F. Its earlier metadata classification was incorrect.
PackageLengthRsp reads unsigned payload byte zero. PackageLengthListener sets the bulk fragment length to at least 20 bytes.
The normal capture retained two valid 2F response events, but omitted their payloads. The actual reported length remains unknown.
This short response differs from the large-protocol opcode 2F.
The table below excludes nested request classes. The JSON preserves every class and method signature.

| Request class | Fixed opcode | Target status |
| --- | --- | --- |
| BaseReqCmd | dynamic / unresolved | unverified |
| MixtureReq | dynamic / unresolved | unverified |
| AgpsReq | 30 | unverified |
| BatterySavingReq | 76 | unverified |
| BindAncsReq | 04 | unverified |
| BlackListReq | 2D | unverified |
| BloodOxygenSettingReq | 2C | verified_write_readback_restored |
| BpReadConformReq | 0E | unverified |
| BpSettingReq | 0C | unverified |
| BrightnessSettingsReq | 1B | rejected_on_target |
| CameraReq | 02 | unverified |
| DegreeSwitchReq | 19 | unverified |
| DeviceAvatarReq | 32 | unverified |
| DialIndexReq | 75 | unverified |
| DisplayClockReq | 12 | unverified |
| DisplayOrientationReq | 29 | unverified |
| DisplayStyleReq | 2A | unverified |
| DisplayTimeReq | 1F | unverified |
| DndReq | 06 | verified_write_readback_restored |
| FindDeviceReq | 50 | sdk_only |
| HeartRateSettingReq | 16 | verified_write_readback_restored |
| IntellReq | 09 | unverified |
| MenstruationReq | 2B | unverified |
| MusicSwitchReq | 1C | unverified |
| PalmScreenReq | 05 | unverified |
| PhoneGpsReq | 74 | unverified |
| PushMsgUintReq | 72 | user_confirmed_display |
| ReadAlarmReq | 24 | unverified |
| ReadBandSportReq | 13 | unverified |
| ReadDetailSportDataReq | 43 | unverified |
| ReadDrinkAlarmReq | 28 | unverified |
| ReadHeartRateReq | 15 | unverified |
| ReadPersonalizationSettingReq | 17 | unverified |
| ReadPressureReq | 14 | unverified |
| ReadSleepDetailsReq | 44 | unverified |
| ReadTotalSportDataReq | 07 | unverified |
| RealTimeHeartRate | 1E | unverified |
| SimpleKeyReq | dynamic / unresolved | unverified |
| RestoreKeyReq | dynamic / unresolved | unverified |
| SetANCSReq | 60 | unverified |
| SetAlarmReq | 23 | unverified |
| SetDrinkAlarmReq | 27 | unverified |
| SetSitLongReq | 25 | unverified |
| SetTimeReq | 01 | verified_capability_reply |
| StartHeartRateReq | 69 | unverified |
| StopHeartRateReq | 6A | unverified |
| TargetSettingReq | 21 | unverified |
| TestReq | C9 | unverified |
| TestReqClose | CA | unverified |
| TestResp | dynamic / unresolved | unverified |
| TimeFormatReq | 0A | unverified |
| WeatherForecastReq | 1A | unverified |

## App feature families

These families define the next investigation passes. App presence does not establish P71J support.

| Family | Static entry points | Target evidence or gap |
| --- | --- | --- |
| Connection and identity | BindAncsReq, battery constant, GATT identity | Connection, battery, and firmware observed |
| Calls and notifications | PushMsgUintReq, SetANCSReq, ContactActivity | Short text confirmed; calls, contacts, permissions, and events unresolved |
| Camera and find functions | CameraReq, FindDeviceReq, CMD_FIND_THE_PHONE | SDK only |
| Time, units, weather | SetTimeReq, TimeFormatReq, DegreeSwitchReq, WeatherForecastReq | Schemas need full annotation; clock write also returns capability flags |
| Display and gesture | DisplayTimeReq, DisplayClockReq, DisplayStyleReq, PalmScreenReq, DisplayOrientationReq | Brightness read rejected; other behavior unresolved |
| Reminders | SetAlarmReq, ReadAlarmReq, SetDrinkAlarmReq, ReadDrinkAlarmReq, SetSitLongReq | SDK only |
| Health configuration | HeartRateSettingReq, BloodOxygenSettingReq, BpSettingReq, MenstruationReq | HR and oxygen configuration confirmed; other behavior unresolved |
| Manual health and live data | StartHeartRateReq, StopHeartRateReq, RealTimeHeartRate, manual big-data beans | Command names exist; sample formats and device support unresolved |
| Activity, sleep, and history | ReadBandSportReq, ReadDetailSportDataReq, ReadTotalSportDataReq, ReadSleepDetailsReq, SportPlusHandle | Schemas, date fields, pagination, and target results unresolved |
| Phone location and GPS | PhoneGpsReq, AgpsReq, FileHandle GPS methods | Source and capability gates unresolved; no proof of onboard GPS |
| Faces and custom images | DialIndexReq, DeviceAvatarReq, custom-face beans, FileHandle | Capability flags, dimensions, resource format, and transfer protocol unresolved |
| Extended content | AlbumActivity, MusicManagerActivity, ECardListActivity, EbookHandle, RecordHandle | Other models use these paths; P71J support unresolved |
| Maintenance | DfuHandle, FileHandle delete, reboot and restore constants, test commands | No execution; no stock firmware package available |
| App services | Account, cloud, Google Fit, feedback, chat, themes, guides | App functions; not evidence of executable watch features |

The [COLMI model page](https://pt.colmi.info/products/colmi-p71-smartwatch) also lists watch-local utilities and settings.
Examples include a calculator, games, stopwatch, flashlight, sound, vibration, and menu options.
No companion command mapping for these local functions is established here.
A later watch menu inventory must compare local functions against the companion protocol.

## Capability reply layout

SetTimeRsp.acceptData interprets the 14 payload bytes below.
DeviceCmdInit stores these values and uses them to choose app features.
The normal connection capture now contains two identical valid target replies: `0100010127f0001c010106f101320062`.
No separate read-only capability request was found in the inspected path.
Pubu sent its normal clock-sync requests during this authorized connection capture.

| Payload byte | App interpretation | Target value |
| --- | --- | --- |
| 0 | temperature: equals 1 | false |
| 1 | watch_faces: equals 1 | true |
| 2 | menstruation: equals 1 | true |
| 8 | new_sleep_protocol: equals 1 | true |
| 3, bit 0 | custom_wallpaper: bit set | true |
| 3, bit 1 | blood_oxygen: bit set | true |
| 3, bit 2 | blood_pressure: bit set | true |
| 3, bit 3 | generic_feature: bit set | false |
| 3, bit 4 | one_key_check: bit set | false |
| 3, bit 5 | weather: bit set | true |
| 3, bit 6 | wechat: bit unset | true |
| 10, bit 0 | contacts: bit set | true |
| 10, bit 1 | lyrics: bit set | false |
| 10, bit 2 | album: bit set | false |
| 10, bit 3 | gps: bit set | false |
| 10, bit 4 | jieli_music: bit set | true |
| 10, bit 5 | app_measurement: bit set | true |
| 10, bit 6 | manual_blood_oxygen: bit set | true |
| 10, bit 7 | yawei: bit set | true |
| 11, bit 0 | manual_heart_rate: bit set | true |
| 11, bit 1 | ecard: bit set | false |
| 11, bit 2 | location: bit set | false |
| 11, bit 4 | music: bit set | false |
| 11, bit 5 | rtk_mcu: bit set | false |
| 11, bit 6 | ebook: bit set | false |
| 11, bit 7 | blood_glucose: bit set | false |
| 13, bit 0 | recording: bit set | false |
| [4, 5] | display_width: little-endian unsigned 16-bit | 240 |
| [6, 7] | display_height: little-endian unsigned 16-bit | 284 |
| 9 | maximum_watch_faces: Java signed byte; app fallback is 6 when nonpositive | 6 |
| 12 | maximum_contacts: 20 if zero; otherwise Java signed byte multiplied by 10 | 500 |

The app uses the YaWei flag for dial-switch and battery-saver options.
The rtk_mcu flag selects an app variant. It cannot identify an exact Realtek part.
Health feature flags describe app behavior. They do not establish sensor hardware or measurement accuracy.
Other settings replies also supply limits and options. Profile, locale, preferences, and server data can affect the visible app.

## Extended transfers and firmware

FileHandle, EbookHandle, RecordHandle, SportPlusHandle, and big-data classes use additional protocols.
The JSON lists their methods as entry points. Their packet layouts remain unresolved.
The inspected DfuHandle uses a separate de5bf728 service with de5bf729 notifications and de5bf72a writes.
Other large-data paths share that service. Its presence alone does not establish an OTA operation.
The authenticated guest firmware lookup returned no upgraded version and no download URL.
The app checks file sums and CRCs. That does not establish which images the watch bootloader accepts.
No firmware image, verified readback, recovery procedure, or modified image acceptance is available.

## Next evidence passes

1. Complete: capture the normal Pubu connection sequence and decode its capability reply.
2. Compare advertised capabilities with visible watch and app menus.
3. Annotate each remaining request and response schema before any new command test.
4. Test supported reads first. Preserve original state before each reversible control test.
5. Confirm visible results separately from protocol acknowledgments and stored settings.
6. Inspect extended resource formats before any face, image, contact, or file transfer.
7. Keep reset, delete, test, and firmware operations outside the ordinary control test pass.

Static inventory coverage is complete for the selected namespaces. Full watch behavior coverage remains open.

## Source identity and evidence boundary

APK SHA-256: `6f254bc8ffa8c6156072db2e30a40bf835431b955d8ca5873ea49fdbc2ee7814`.
The source is Pubu Wear com.pubu.dafit version 1.0.0.39, version code 46.
REA archive inventory evidence: `ev_85059e57273f29c091d79403c10c4b11319c1b86b25c4d9224d7002cf493d75e`.
Static inspection used Androguard 4.1.3 after the Android analysis provider lacked Java and JADX.
The JSON preserves class names, method signatures, fixed request opcodes, and captured control frames.
No APK, disassembly, authentication token, phone address, or personal health history is included.
