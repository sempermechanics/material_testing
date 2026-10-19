"""Multi-step flows the routers call, one module per aggregate (TD-293).

A router reads the request, checks who may ask, and answers; when the answer
takes several steps across Drive, Firestore, Firebase Auth and the audit log,
the steps live here, in order, beside `app.repo` and through its names
(`repo.<name>`, ADR-021). Tests patch them where they always did: `drive`,
`audit` and `repo_view.patch`.
"""
