# Health data integrity

Created: 2026-11-02.
Target: M71J_V1.0, firmware M71J_1.00.27_231223.
App audit source: installed Pubu Wear 1.0.0.39.

The project requires no fabricated values in P71 Link. He explicitly excludes all oxygen features.

| Path | Confirmed Pubu app behavior | P71 Link rule |
| --- | --- | --- |
| Manual oxygen | A positive reply gates a generated96–99 value. The timer can send this value to the watch. | Exclude controls, records and export. Keep test captures as research only. |
| Manual heart rate | The timer can display and send default85 or a stale prior value without a fresh accepted result. | Require a current-session watch reply. Never substitute85 or a prior result. |
| Automatic heart rate | The inspected app path stores received sample bytes. It also pads and removes samples. | Preserve received samples and provenance. Mark absent samples explicitly. |
| Manual blood pressure | A timer can send default120/80 or stale values. | Exclude pressure measurements. Do not send default result values. |
| Automatic blood pressure | The app uses random terms to derive stored systolic and diastolic pairs from a single raw value. | Never reproduce these formulas or present raw values as measured pressure. |
| Sleep | The inspected app path stores watch stage-duration records and derives summaries. | Preserve raw records. Identify stages as watch classifications. |
| Sleep score | The app applies fixed duration and ratio targets. No random term was found in this helper. | Do not invent or reproduce an unexplained quality score. |

The inspected automatic heart-rate and sleep paths did not show the random fabrication found in blood pressure.
This bounded result does not establish sensor accuracy or firmware behavior.
The watch firmware and its sensor algorithms remain unexamined.

Sleep stage2 means light,3 means deep, and5 means awake in the active QC app path.
The Pubu summary includes awake time in its totalSleep value.
P71 Link must show awake time separately. Its asleep total must include only established sleep stages.
Unknown stage codes and incomplete intervals must remain explicit.

Missing fields must stay unavailable. A zero received from the watch must remain a received zero.
Derived totals must identify their calculation. Date fields must identify their source and timezone assumptions.
A Bluetooth acknowledgment is not a physiological result. Local storage success requires a successful write.

Activity units follow the recovered QC app convention, with raw counters retained beside interpreted values.
This convention is protocol evidence. It does not validate physical distance or energy estimates.

Raw tests and detailed audit reports remain outside the source package. They contain no recovered firmware image.
No firmware change, physiological accuracy claim, or clinical validation follows from this app audit.
