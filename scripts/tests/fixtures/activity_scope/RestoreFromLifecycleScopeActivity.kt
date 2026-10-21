package com.example.ui.home

// Home's restore before #457 (ADR-016 amendment): the lookup and the enqueue
// run on the Activity's scope, so a rotation during the lookup loses both.
class RestoreFromLifecycleScopeActivity :
    AppCompatActivity(),
    SomeHost {

    private fun startRestore(rows: List<Row>) {
        clearSelection()
        queueRestores(rows) { count -> toast("$count queued") }
    }

    private fun queueRestores(rows: List<Row>, onDone: (Int) -> Unit) {
        lifecycleScope.launch {
            val targets = rows.mapNotNull { resolve(it) }
            val counts = RestoreStart.startAll(this@RestoreFromLifecycleScopeActivity, targets)
            onDone(counts.started)
        }
    }

    private suspend fun resolve(row: Row): Target? =
        CloudSync.resolveCloudIdFor(this, row.id)?.let { Target(it, row.id) }
}
