# Alpha usage report (fill during device matrix)

Date (UTC):  
API project:  
Backend revision (must include `opClass`):  
App version / tag:  
Tester uid:  
Device: Pixel 3  

## Scenario results

| # | Scenario | Start UTC | End UTC | opClass counts | Notable paths | Frames / files | Notes (Drive vs Cloud Run) |
|---|---|---|---|---|---|---|---|
| 1 | Warm Home sync |  |  |  |  | — |  |
| 2 | Single (small) |  |  |  |  |  |  |
| 3 | Sweep (small) |  |  |  |  |  |  |
| 4 | Restore |  |  |  |  |  | Cloud Run egress |
| 5 | Delete backup |  |  |  |  | — |  |
| 6 | Heavy PLC band |  |  |  |  |  | `AAA5083_H111 - PLC band` |

## Blockers / gate

- [ ] Backend with `opClass` deployed
- [ ] Beta APK installed
- [ ] Tester confirmed **logged in**
- [ ] PLC-band images on device

## Raw query notes

Paste `gcloud logging read` / `scripts/meter_alpha_usage.sh` snippets here.
