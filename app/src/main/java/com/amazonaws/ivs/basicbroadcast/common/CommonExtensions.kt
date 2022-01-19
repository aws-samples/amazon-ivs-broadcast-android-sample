package com.amazonaws.ivs.basicbroadcast.common

import android.app.Activity
import android.app.Dialog
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.View
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import androidx.core.content.ContextCompat
import com.amazonaws.ivs.basicbroadcast.R
import com.amazonaws.ivs.basicbroadcast.databinding.ViewDialogBinding
import kotlinx.coroutines.*

private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
private const val TAG = "AmazonIVS"

fun launchIO(block: suspend CoroutineScope.() -> Unit) = ioScope.launch(
    context = CoroutineExceptionHandler { _, e -> Log.d(TAG, "Coroutine failed ${e.localizedMessage}") },
    block = block
)

fun launchMain(block: suspend CoroutineScope.() -> Unit) = mainScope.launch(
    context = CoroutineExceptionHandler { _, e -> Log.d(TAG, "Coroutine failed ${e.localizedMessage}") },
    block = block
)

fun Activity.showDialog(title: String, message: String) {
    val binding = ViewDialogBinding.inflate(layoutInflater)
    val dialog = Dialog(this).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(false)
        setContentView(binding.root)
    }
    binding.title.text = getString(R.string.error_happened_template, title)
    binding.message.text = message
    binding.dismissBtn.setOnClickListener {
        dialog.dismiss()
    }
    dialog.show()
}

fun Activity.hideKeyboard() {
    val view = currentFocus ?: window.decorView
    val token = view.windowToken
    view.clearFocus()
    ContextCompat.getSystemService(this, InputMethodManager::class.java)
        ?.hideSoftInputFromWindow(token, 0)
}

fun Spinner.onSelectionChanged(callback: (Int) -> Unit) {
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {
            /* Ignored */
        }

        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            callback(position)
        }
    }
}

fun Button.toggleVisibility(keyField: EditText, keyHidden: Boolean) {
    if (keyHidden) {
        keyField.transformationMethod = HideReturnsTransformationMethod.getInstance()
        this.background = ContextCompat.getDrawable(context, R.drawable.ic_outline_eye)
    } else {
        keyField.transformationMethod = PasswordTransformationMethod.getInstance()
        this.background = ContextCompat.getDrawable(context, R.drawable.ic_baseline_eye)
    }
}

fun View.hide() {
    if (visibility == View.VISIBLE) {
        visibility = View.INVISIBLE
    }
}

fun View.show() {
    if (visibility == View.INVISIBLE) {
        visibility = View.VISIBLE
    }
}

fun View.changeVisibility(show: Boolean) {
    visibility = if (show) {
        View.VISIBLE
    } else {
        View.GONE
    }
}
