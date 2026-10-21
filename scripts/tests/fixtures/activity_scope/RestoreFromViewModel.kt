package com.example.ui.home

// The same restore after #457: on viewModelScope, which a rotation keeps.
class RestoreFromViewModel(private val app: Application) : AndroidViewModel(app) {
    fun queueRestores(rows: List<Row>) {
        viewModelScope.launch {
            val targets = rows.mapNotNull { CloudSync.resolveCloudIdFor(app, it.id) }
            RestoreStart.startAll(app, targets)
        }
    }
}

class RestoreFromViewModelActivity : AppCompatActivity() {
    private val viewModel: RestoreFromViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Collecting state, phone-only calls, and calls named only in comments
        // and strings are all fine on the screen's scope.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            CloudSync.eraseLocalOnly(this@RestoreFromViewModelActivity, "id") // not CloudSync.reconcile(
            val api = SemperApi.get(this@RestoreFromViewModelActivity)
            if (!api.enabled) toast("CloudSync.reconcile(this) and enqueue( are only text")
            RestoreStart.isRunning(this@RestoreFromViewModelActivity, "cloud")
        }
        binding.restore.setOnClickListener { viewModel.queueRestores(selected()) }
    }
}
