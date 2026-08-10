# Alpha usage report (fill during device matrix)

Date (UTC):  
API project:  
Backend revision (must include `opClass`):  
App version / tag:  
Tester uid:  
Device: Pixel 3  

## Scenario results

| # | Scenario | Start UTC | End UTC | opClass counts | Peak PSS (MiB) | ROM Δ (MiB) | Frames / files | Notes |
|---|---|---|---|---|---|---|---|---|
| 1 | Warm Home sync |  |  |  |  |  | — |  |
| 2 | Single (small) |  |  |  |  |  |  |  |
| 3 | Sweep (small) |  |  |  |  |  |  |  |
| 4 | Restore |  |  |  |  |  |  | Cloud Run egress |
| 5 | Delete backup |  |  |  |  | — | — |  |
| 6 | Heavy PLC band |  |  |  |  |  |  | `AAA5083_H111 - PLC band` |

Peak PSS / ROM Δ: from `./scripts/meter_alpha_device_memory.sh pull && summarize`
(or paste AlphaMeter CSV). ROM Δ = `rom_bytes` at end − start for that window.

## Blockers / gate

- [ ] Backend with `opClass` deployed
- [ ] Beta APK installed (with `AlphaDeviceMeter` / PR #40+)
- [ ] Tester confirmed **logged in**
- [ ] PLC-band images on device
- [ ] `adb` metering scripts run alongside scenarios

## Raw query notes

Paste `gcloud` / `meter_alpha_usage.sh` snippets and `meter_alpha_device_memory.sh summarize` here.
