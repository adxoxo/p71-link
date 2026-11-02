# P71J normal connection exchange

Evidence date: 2026-11-02.

The authorized Pubu Wear connection produced two identical capability replies from P71J_CEC9.
Both replies have 16 bytes, command 01, and checksum 62.

```text
0100010127f0001c010106f101320062
```

The app parser decodes the following target values.
These values describe advertised features. They do not prove complete feature operation or measurement accuracy.

| Field | Target report |
| --- | --- |
| temperature | false |
| watch_faces | true |
| menstruation | true |
| new_sleep_protocol | true |
| custom_wallpaper | true |
| blood_oxygen | true |
| blood_pressure | true |
| generic_feature | false |
| one_key_check | false |
| weather | true |
| wechat | true |
| contacts | true |
| lyrics | false |
| album | false |
| gps | false |
| jieli_music | true |
| app_measurement | true |
| manual_blood_oxygen | true |
| yawei | true |
| manual_heart_rate | true |
| ecard | false |
| location | false |
| music | false |
| rtk_mcu | false |
| ebook | false |
| blood_glucose | false |
| recording | false |
| display_width | 240 |
| display_height | 284 |
| maximum_watch_faces | 6 |
| maximum_contacts | 500 |

## What the values establish

The display reports 240 by 284 pixels. The app decodes six watch-face slots and a contact limit of 500.
The actual contact transfer capacity remains untested.
Custom wallpaper, contacts, weather, and manual heart-rate and oxygen functions are advertised.
The new-sleep flag chooses the app sleep protocol. It does not prove sleep measurement quality.
The app uses YaWei for dial-switch and battery-saver options. The flag does not explain the manufacturer or silicon.
The Jieli-music flag is true. The separate generic music flag is false.
This combination does not prove local music storage, file transfer, playback, or the identity of the main processor.
The Realtek-MCU flag is false. It is an app variant flag, not an exact chip identifier.
That value does not disprove the working RTL8763EWE identity.
A false field means this reply does not advertise that app capability. It does not establish physical hardware absence.
WeChat is true because the app treats its bit as inverted.

## Observed sequence

The filtered capture lasted 70.33 seconds. Most of that time preceded the connection.
It contains 91 checksum-valid short-frame log events and metadata for 24 non-16-byte log events.
It retains selected configuration payloads and opcode metadata for other traffic.
This is a bounded excerpt, not a complete Bluetooth packet capture.
Log order does not establish radio order. One battery notification log precedes its write log.

The first clock-sync reply arrived near elapsed second 61.1.
The app then read the battery, automatic health controls, wrist gesture, units, DND, and display settings.
It also exchanged bind, goal, notification, activity, health-history, weather, and extended packets.
Only the selected configuration and capability payloads were retained. History and notification payloads were discarded.
The app sent another clock-sync request near second 62.2. The second capability reply matched the first.
The time-format exchange includes a write. Normal connection includes state synchronization as well as reads.
No independent raw command or custom firmware operation was sent for this capture.

The captured DND, automatic heart-rate, and automatic oxygen replies match their earlier restored off states.
The wrist query returned `0501010500000000000000000000000c`.
The display query returned `1f01050114000000053c050100000081`.
Their complete field interpretation still needs a separate schema annotation.

## Capture and restoration

The phone remained accessible through its existing ADB connection.
Pubu initially showed Bind now. Its scan found P71J_CEC9, and the normal Connect action completed.
The temporary diagnostic switch exposed app-process BLE log messages.
A local filter retained only selected packet payloads and command metadata.
The original debug state was off. The switch returned to off, and Pubu restarted with that state.
The four task-created packet and debug log files were removed from the phone.
The pre-existing app log remained intact. Pubu showed the target name and Remove Device control after restart.
No account login, firmware update, reset, or flash operation occurred.

## DaFlasherFiles reference

The [author repository](https://github.com/atc1441/DaFlasherFiles) supplies Nordic SoftDevice and DFU bootloader files.
Its documented targets include Nordic nRF52840 watches and P8 or PineTime examples.
Those bootloader files target a different platform from the working Realtek identity.
The [author compatibility list](https://gist.github.com/atc1441/d0a3c1f5ee69ab901bccba4eb47a6e4e) does not establish P71J compatibility.
The useful method is to identify the exact board, obtain correct stock firmware, and establish recovery before a firmware experiment.
No files from that repository were flashed or executed.

## Next mapping gaps

- Compare the advertised fields with all local watch menus and visible Pubu options.
- Decode the remaining request and response fields before new functional tests.
- Investigate the Jieli-music path separately from the generic music path.
- Confirm resource formats and limits before custom-face, contact, or image transfers.

See [CAPABILITIES.md](CAPABILITIES.md) for the full command inventory and evidence labels.
