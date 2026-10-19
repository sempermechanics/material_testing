package com.example.ui

// `write` is a suspend fun here and a plain fun in PlainWriter.kt; `run` is a
// stdlib scope function and SingleFlight's suspend member.
class FrameSink {
    suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { file.writeBytes(bytes) }
}

class Flight {
    suspend fun run(block: suspend () -> Unit) = block()
}

class Screen(private val sink: FrameSink, private val flight: Flight) {
    // Not a suspend context: the plain `write` is meant.
    fun saveBlocking(bytes: ByteArray) {
        runCatching { DicFieldIo.write(bytes) }
    }

    // A suspend context, but `run { }` is the scope function.
    suspend fun measure(): Int? = runCatching { size.run { this + 1 } }.getOrNull()

    // A suspend context and the suspend `write`.
    suspend fun save(bytes: ByteArray) {
        runCatching { sink.write(bytes) }
    }
}
