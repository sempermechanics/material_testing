package com.example.data

class HandledLoader(private val api: CloudApi, private val retry: suspend () -> Unit) {
    suspend fun fetchMe(): Me = api.me()

    suspend fun load(): Result<Me> = suspendRunCatching { fetchMe() }

    suspend fun rethrowsFirst(): Me? =
        try {
            fetchMe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    suspend fun asksTheCaller(): Me? =
        try {
            fetchMe()
        } catch (e: Exception) {
            e.rethrowIfCallerCancelled()
            null
        }

    suspend fun testsTheType(): Me? =
        try {
            retry()
            fetchMe()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    suspend fun rethrowsEverything() {
        try {
            fetchMe()
        } catch (e: Exception) {
            Timber.w(e, "failed")
            throw e
        }
    }

    suspend fun chainRethrows(): Me = runCatching { fetchMe() }.getOrThrow()

    // Cleanup that must finish (ADR-018).
    suspend fun cleanUp() = withContext(Dispatchers.IO + NonCancellable) {
        runCatching { fetchMe() }
    }

    // Not a suspend call: plain runCatching is fine.
    suspend fun parse(text: String): Int? = runCatching { text.toInt() }.getOrNull()

    // A nested launch is its own coroutine; Play services' Tasks.await blocks.
    fun startAll(scope: CoroutineScope) {
        try {
            scope.launch { fetchMe() }
            Tasks.await(task, 5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Timber.w(e, "runCatching { fetchMe() } is only text")
        }
    }
}
