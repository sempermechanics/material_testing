package com.example.data

// Three ways ADR-018 forbids: each turns the caller's cancellation into a failure.
class AccountLoader(private val api: CloudApi) {
    suspend fun fetchMe(): Me = api.me()

    // 1. runCatching around a suspend call (TD-77's 21 sites).
    suspend fun load(): Result<Me> = runCatching { fetchMe() }

    // 2. A broad catch with no rethrow, its parameter annotated.
    suspend fun loadOrNull(): Me? =
        try {
            fetchMe()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "load failed")
            null
        }

    // 3. A CancellationException clause after the broad one is never reached.
    suspend fun waitThenLoad(): Me? {
        return try {
            delay(10)
            fetchMe()
        } catch (e: Throwable) {
            null
        } catch (e: CancellationException) {
            throw e
        }
    }
}
