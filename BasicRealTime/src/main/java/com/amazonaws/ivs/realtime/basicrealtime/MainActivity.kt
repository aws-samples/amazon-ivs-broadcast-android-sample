package com.amazonaws.ivs.realtime.basicrealtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.amazonaws.ivs.broadcast.Bluetooth
import com.amazonaws.ivs.broadcast.Stage.ConnectionState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var checkboxPublish: CheckBox
    private lateinit var recyclerView: RecyclerView
    private lateinit var buttonJoin: Button
    private lateinit var textViewState: TextView
    private lateinit var editTextToken: EditText

    // App State
    private val viewModel: MainViewModel by viewModels()

    // View Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Bluetooth.startBluetoothSco(applicationContext)
        checkboxPublish = findViewById(R.id.main_publish_checkbox)
        recyclerView = findViewById(R.id.main_recycler_view)
        buttonJoin = findViewById(R.id.main_join)
        textViewState = findViewById(R.id.main_state)
        editTextToken = findViewById(R.id.main_token)

        recyclerView.layoutManager = StageLayoutManager(this)
        recyclerView.adapter = viewModel.participantAdapter

        buttonJoin.setOnClickListener {
            viewModel.joinStage(editTextToken.text.toString())
        }
        viewModel.setPublishEnabled(viewModel.canPublish)
        checkboxPublish.isChecked = viewModel.canPublish
        checkboxPublish.isEnabled = viewModel.canPublish
        checkboxPublish.alpha = if (viewModel.canPublish) 1.0f else 0.5f
        checkboxPublish.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setPublishEnabled(isChecked)
        }
        textViewState.text = getString(R.string.state, ConnectionState.DISCONNECTED.name)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.connectionState.collect { state ->
                    buttonJoin.setText(if (state == ConnectionState.DISCONNECTED) R.string.join else R.string.leave)
                    textViewState.text = getString(R.string.state, state.name)
                }
            }
        }

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (viewModel.canPublish) {
            requestPermission()
        }
    }

    override fun onDestroy() {
        super.onDestroy();
        Bluetooth.stopBluetoothSco(applicationContext);
    }

    //region Permissions Related Code
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.CAMERA] == true && permissions[Manifest.permission.RECORD_AUDIO] == true) {
                viewModel.permissionGranted()
            }
        }

    private val permissions = listOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    private fun requestPermission() {
        when {
            this.hasPermissions(permissions) -> viewModel.permissionGranted()
            else -> requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun Context.hasPermissions(permissions: List<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    //endregion
}
