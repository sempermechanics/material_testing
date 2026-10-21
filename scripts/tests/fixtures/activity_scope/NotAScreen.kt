package com.example.ui.common

// Not a screen: a bare `lifecycleScope` here is some other owner's, and a
// namesake of a launcher elsewhere (`observe`) is not that launcher.
fun LifecycleOwner.refreshLater(block: suspend () -> Unit) = lifecycleScope.launch { block() }

class NotAScreen(private val owner: LifecycleOwner) {
    fun start() {
        lifecycleScope.launch { CloudSync.reconcile(context) }
        liveData.observe(owner) { infos -> SessionDeletes.enqueue(context, infos) }
    }
}

object SignOutLike {
    fun observe(activity: AppCompatActivity, onDone: () -> Unit) {
        activity.lifecycleScope.launch { state.collect { onDone() } }
    }
}
