"""Build this directory (app/src/test/resources/upgrade/1036/) from the v1.2-beta.4 (33dce87d) formats.

Not captured from a device: each file is what that build's code writes,
read from its source at 33dce87d (readiness TE1-003):

- sessions/index.json: SessionStore.write = Json { ignoreUnknownKeys; no
  prettyPrint }.encodeToString(List<SessionRecord>), kotlinx defaults
  (encodeDefaults = false, so default-valued fields are left out), keys in
  SessionRecord's declaration order at 33dce87d. Paths are written with the
  @FILES@ placeholder the test replaces with its filesDir.
- shared_prefs/*.xml: Android's SharedPreferences XML for the keys and
  types PrefFiles declared at 33dce87d (Session, RemoteConfig, Settings,
  Onboarding), with the values AccountCache / AppRemoteConfig / AppSettings
  wrote then (AccessStatus.APPROVED = "APPROVED", improvement consent as
  "true", mode "licensed", settings schema 1).
"""
import json
import os

OUT = os.path.dirname(os.path.abspath(__file__))
F = '@FILES@'

rows = [
    # A batch, backed up, renamed by the user.
    {
        "id": "batch-1036", "name": "Tensile A", "createdAt": 1760700000000, "updatedAt": 1760700300000,
        "frameCount": 3, "subset": 41, "step": 5, "strainWindow": 15, "use6x6": True,
        "imgW": 4000, "imgH": 3000, "roiX": 10, "roiY": 20, "roiW": 300, "roiH": 400,
        "refPath": F + "/sessions/batch-1036/reference.png", "refName": "ref.tif",
        "sessionDir": F + "/sessions/batch-1036",
        "defNames": ["def_001.tif", "def_002.tif", "def_003.tif"],
        "headline": "97.5% converged on frame 1",
        "engineStats": [1200.0, 1180.0, 20.0, 2.3, 97.5],
        "strainMethod": "PLS", "pointsConverged": 1180, "avgIterations": 2.3, "executionTimeMs": 5400,
        "cloudSessionId": "cloud-1036", "syncState": "SYNCED",
        "renamedByUser": True,
    },
    # A sweep with one skipped combination.
    {
        "id": "sweep-1036", "name": "Sweep B", "createdAt": 1760700400000, "updatedAt": 1760700400000,
        "frameCount": 2, "subset": 41, "step": 5, "strainWindow": 15,
        "imgW": 100, "imgH": 100, "roiX": 0, "roiY": 0, "roiW": 100, "roiH": 100,
        "refPath": "", "refName": "ref.png", "sessionDir": F + "/sessions/sweep-1036",
        "defNames": ["d.png", "d.png"],
        "sweepSubsets": [21, 31], "sweepSteps": [5, 7], "sweepStrainWindows": [41, 85],
        "sweepLabels": ["S21\u00b7s5", "S31\u00b7s7"], "lineCutHorizontal": False,
        "sweepSkippedNodes": [{"subset": 41, "step": 9, "strainWindow": 121, "code": -12}],
    },
    # Frames sampled from a video.
    {
        "id": "video-1036", "name": "Clip C", "createdAt": 1760700500000, "updatedAt": 1760700500000,
        "frameCount": 2, "subset": 31, "step": 5, "strainWindow": 15,
        "imgW": 1920, "imgH": 1080, "roiX": 0, "roiY": 0, "roiW": 1920, "roiH": 1080,
        "refPath": "", "refName": "clip_ref.png", "sessionDir": F + "/sessions/video-1036",
        "defNames": ["clip_001.png", "clip_002.png"],
        "videoName": "clip.mp4", "frameTimesMs": [0, 500, 1000],
    },
    # Stopped early on low convergence, its backup still pending, metadata stale.
    {
        "id": "stopped-1036", "name": "Stopped D", "createdAt": 1760700600000, "updatedAt": 1760700600000,
        "frameCount": 2, "subset": 41, "step": 5, "strainWindow": 15,
        "imgW": 100, "imgH": 100, "roiX": 0, "roiY": 0, "roiW": 100, "roiH": 100,
        "refPath": "", "refName": "ref.png", "sessionDir": F + "/sessions/stopped-1036",
        "defNames": ["a.png", "b.png"],
        "cloudSessionId": "cloud-1036-d", "syncState": "PENDING", "metadataStale": True,
        "stopCode": -96, "plannedFrameCount": 6,
    },
]

PREFS = {
    'semper_session': [
        ('string', 'uid', 'u-1036'),
        ('string', 'email', 'qa@example.com'),
        ('string', 'last_status', 'APPROVED'),
        ('string', 'role', 'user'),
        ('boolean', 'device_registered', 'true'),
        ('int', 'quota_used', '4'),
        ('int', 'quota_local_count', '4'),
        ('string', 'terms_required_version', '2026-09-01'),
        ('string', 'terms_accepted_version', '2026-09-01'),
        ('boolean', 'terms_accepted_synced', 'true'),
        ('string', 'improvement_consent', 'true'),
    ],
    'semper_remote_config': [
        ('int', 'max_sessions', '0'),
        ('int', 'max_frames', '200'),
        ('string', 'mode', 'licensed'),
        ('boolean', 'cloud_backup_enabled', 'true'),
        ('boolean', 'share_enabled', 'true'),
        ('string', 'license_kind', 'institution'),
        ('string', 'license_duration', 'perpetual'),
        ('string', 'license_seating', 'assigned'),
        ('long', 'fetched_at', '1760700000000'),
    ],
    'dic_settings': [
        ('int', 'schema', '1'),
        ('boolean', 'save_to_cloud', 'true'),
        ('boolean', 'upload_wifi_only', 'true'),
        ('int', 'max_frames', '150'),
        ('boolean', 'diagnostics_asked', 'true'),
        ('boolean', 'diagnostics_enabled', 'false'),
    ],
    'semper_onboarding': [
        ('boolean', 'beta_notice_acked_u-1036', 'true'),
    ],
}


def xml(entries):
    out = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", '<map>']
    for kind, name, value in entries:
        if kind == 'string':
            out.append(f'    <string name="{name}">{value}</string>')
        else:
            out.append(f'    <{kind} name="{name}" value="{value}" />')
    out.append('</map>')
    return '\n'.join(out) + '\n'


os.makedirs(OUT + '/sessions', exist_ok=True)
os.makedirs(OUT + '/shared_prefs', exist_ok=True)
with open(OUT + '/sessions/index.json', 'w', encoding='utf-8', newline='') as f:
    f.write(json.dumps(rows, ensure_ascii=False, separators=(',', ':')))
for name, entries in PREFS.items():
    with open(f'{OUT}/shared_prefs/{name}.xml', 'w', encoding='utf-8', newline='') as f:
        f.write(xml(entries))
print('ok')
