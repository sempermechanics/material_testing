package com.example.ui.settings

// A Settings section: not an Activity, but `activity.lifecycleScope` is the
// screen's scope, so what it launches there counts.
internal class SettingsPartSection(private val activity: SettingsActivity, private val job: SerialJob) {
    fun refresh() {
        job.launch(activity.lifecycleScope) {
            api.listSessions(token())
        }
    }

    fun backUpAll(ids: List<String>) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            ids.forEach { WorkManager.getInstance(activity).enqueueUniqueWork(it, KEEP, oneTimeWork<UploadWorker>()) }
        }
    }

    fun eraseCloud(id: String) {
        activity.lifecycleScope.launch {
            api.authed(tokens) { token -> deleteSession(token, id) }
        }
    }
}
