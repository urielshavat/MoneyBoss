package com.moneyboss.app

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moneyboss.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val store by lazy { TokenStore(this) }
    private val api by lazy { SupabaseClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store.getToken()?.let {
            openMain()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tokenInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else false
        }
        binding.loginButton.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val token = binding.tokenInput.text?.toString()?.trim()?.uppercase().orEmpty()
        if (token.length != Config.TOKEN_LEN) {
            Toast.makeText(this, "הזן טוקן בן 6 תווים", Toast.LENGTH_SHORT).show()
            return
        }
        binding.loginButton.isEnabled = false
        binding.progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            try {
                val ok = api.validateToken(token)
                if (!ok) {
                    Toast.makeText(this@LoginActivity, "טוקן לא נמצא", Toast.LENGTH_SHORT).show()
                } else {
                    store.saveToken(token)
                    openMain()
                }
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "שגיאת רשת: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.loginButton.isEnabled = true
                binding.progress.visibility = android.view.View.GONE
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
