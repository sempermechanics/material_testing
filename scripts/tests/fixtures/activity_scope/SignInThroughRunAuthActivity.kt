package com.example.ui.auth

// Sign-in before #457: runAuth launches on the screen's scope and runs the
// lambda each caller hands it.
class SignInThroughRunAuthActivity : AppCompatActivity() {
    private val authRepo by lazy { AuthRepository(this) }

    private fun onMainAction(email: String, password: String) {
        runAuth { authRepo.signInWithPassword(email, password) }
    }

    internal fun runAuth(call: suspend () -> Result<String>) {
        setLoading(true)
        lifecycleScope.launch {
            routeResult(call())
        }
    }
}
