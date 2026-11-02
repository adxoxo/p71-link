# Sensor data and app substitution evidence

Evidence date: 2026-11-02.

The installed Pubu Wear APK contains a manual blood-oxygen callback that assigns a random value after a positive response.
This is a static code finding. No live execution of this callback on this watch has been captured.
The result does not establish the accuracy or physical design of the watch sensor.
An earlier description called this a direct display substitution. The deeper trace does not establish that direct display path.

## Exact inspected path

Package: com.pubu.dafit, version 1.0.0.39, code 46.
APK SHA-256: `6f254bc8ffa8c6156072db2e30a40bf835431b955d8ca5873ea49fdbc2ee7814`.

Class: `com.qcwireless.qcwatch.ui.home.bloodoxgen.BloodOxygenActivity$setupViews$10$1`.
Method: `onDataResponse(StartHeartRateRsp)`.

The inspected branch requires error code zero, measurement type three, and a positive response value.
It then creates Random, calls nextInt with bound four, adds 96, and assigns the result through setBloodOxygenValue.
The assigned measurement-state value is therefore one of 96, 97, 98, or 99.
It does not preserve the positive response value for that assignment.

The timed completion handler reads that stored field and calls stopBloodOxygen with it.
The resulting command is 6A with payload [3, assignedValue, 0].
The field also starts with default value 98, which can reach that command path.
This establishes a synthetic value sent toward the watch. It does not establish firmware persistence or a specific displayed history value.

The later manual-history query populates the local database from watch replies.
Whether those replies contain the synthetic stop value requires live or firmware evidence.
No direct synthetic-value cloud upload was established in the inspected paths.

The incoming type-three value is full-frame byte three. Its positivity gates the substitution branch.
No conversion or reliable percentage-unit definition was established for that byte.
The replacement must not label every positive raw type-three byte as measured oxygen percent.
This finding does not cover every history screen or every firmware branch in Pubu Wear.

## Replacement-app requirements

- Preserve received sensor values and their source timestamps.
- Show unknown, pending, rejected, disconnected, and timeout states explicitly.
- Do not replace missing or unexpected values with random or estimated samples.
- Keep unverified raw responses separate from records with established field semantics.
- Test received-packet-to-display and storage identity.
- Keep protocol success separate from measurement completion and sensor accuracy.

No clinical accuracy test has been performed.
The new app can remove the identified synthetic-value command path. A software change cannot establish sensor accuracy by itself.

## Product scope

Oxygen controls, measurements, history and export are excluded.
Private live sensor captures are not part of this repository.
This exclusion does not establish physical sensor absence or sensor accuracy.
