package com.moneyboss.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moneyboss.app.databinding.ActivityAddExpenseBinding
import kotlinx.coroutines.launch

class AddExpenseActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAddExpenseBinding
    private val store by lazy { TokenStore(this) }
    private val api by lazy { SupabaseClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddExpenseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (store.getToken() == null) {
            Toast.makeText(this, "יש להתחבר קודם באפליקציה", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.cancelButton.setOnClickListener { finish() }
        binding.confirmButton.setOnClickListener { submit() }
    }

    private fun submit() {
        val token = store.getToken() ?: return
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        val amountRaw = binding.amountInput.text?.toString()?.trim()?.replace(",", "").orEmpty()
        val amount = amountRaw.toDoubleOrNull()

        if (name.isEmpty()) {
            Toast.makeText(this, "הזן שם הוצאה", Toast.LENGTH_SHORT).show()
            return
        }
        if (amount == null || amount <= 0) {
            Toast.makeText(this, "הזן סכום תקין", Toast.LENGTH_SHORT).show()
            return
        }

        binding.confirmButton.isEnabled = false
        binding.progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            try {
                api.insertExpense(token = token, amount = amount, name = name)
                Toast.makeText(this@AddExpenseActivity, "ההוצאה נרשמה", Toast.LENGTH_SHORT).show()
                refreshWidgets()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@AddExpenseActivity, "שגיאה: ${e.message}", Toast.LENGTH_LONG).show()
                binding.confirmButton.isEnabled = true
                binding.progress.visibility = android.view.View.GONE
            }
        }
    }

    private fun refreshWidgets() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, BalanceWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            BalanceWidgetProvider.requestUpdate(this, ids)
        }
    }
}
