package com.amazonaws.ivs.basicbroadcast.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.core.view.contains
import androidx.core.view.isNotEmpty
import com.amazonaws.ivs.basicbroadcast.App
import com.amazonaws.ivs.basicbroadcast.viewModel.StageViewModel
import com.amazonaws.ivs.broadcast.BroadcastException
import com.amazonaws.ivs.broadcast.BroadcastSession
import com.amazonaws.ivs.basicbroadcast.common.*
import com.amazonaws.ivs.basicbroadcast.R
import com.amazonaws.ivs.basicbroadcast.databinding.ActivityStageBinding

@RequiresApi(Build.VERSION_CODES.Q)
class StageActivity : PermissionActivity() {

    private lateinit var binding: ActivityStageBinding

    private val viewModel: StageViewModel by lazyViewModel({ application as App }, { StageViewModel(application) })

    companion object {
        const val TOKEN_EXTRA_NAME = "TOKEN"
    }

    private var token: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        getTokenData(intent)
        observeData()

        with(viewModel) {
            initWithPermissions {
                attachImageStream()
                attachMicrophoneStream()
            }
        }

        initUi()
        initBackCallback()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        getTokenData(intent)
    }

    override fun onStop() {
        super.onStop()
        with(viewModel) {
            if (joined) enterBackground()
        }
    }

    override fun onResume() {
        super.onResume()
        with(viewModel) {
            if (joined) enterForeground(binding.btnCamera.isChecked)
        }
    }

    override fun onDestroy() {
        clearPreview()
        leaveStage()
        viewModel.release()
        super.onDestroy()
    }

    private fun backPressed() {
        Intent(Intent.ACTION_MAIN).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addCategory(Intent.CATEGORY_HOME)
            startActivity(this)
        }
    }

    private fun initBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                backPressed()
            }
        } else {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    backPressed()
                }
            })
        }
    }

    private fun getTokenData(intent: Intent?) {
        token = intent?.getStringExtra(TOKEN_EXTRA_NAME) ?: ""
    }

    /**
     * Check CAMERA and RECORD_AUDIO permission status and create session
     */
    private fun initStage() {
        if (!arePermissionsGranted()) {
            askForPermissions { success ->
                if (success) createAndJoinStage()
            }
        } else {
            createAndJoinStage()
        }
    }

    private fun createAndJoinStage() {
        binding.stageOptionView.edtToken.text.toString().trim().let { token ->
            if (token.isEmpty()) return

            with(viewModel) {
                if (needCreateStage(token)) createStage(token) { joinStage() } else joinStage()
            }
            saveToken(token)
        }
    }

    /**
     * LiveData observers
     */
    private fun observeData() {
        viewModel.viewToAdd.observe { view ->
            if (viewModel.participantViewMap.containsValue(view)) {
                with(binding.participantListLayout) {
                    if (contains(view)) removeView(view)
                    addView(view)
                }
            }
        }

        viewModel.viewToRemove.observe { view ->
            with(binding.participantListLayout) {
                if (contains(view)) {
                    removeView(view)
                }
            }
        }

        viewModel.localViewToAdd.observe { view ->
            if (viewModel.participantViewMap.containsValue(view)) {
                with(binding.participantListLayout) {
                    if (isNotEmpty()) removeViewAt(0)
                    addView(view, 0)
                }
            }
        }

        viewModel.joinHappened.observe(this) { joinedSession ->
            binding.joined = joinedSession
        }

        viewModel.removeAllParticipants.observe { remove ->
            if (remove) {
                with(binding.participantListLayout) {
                    if (childCount != 0) removeViews(1, childCount - 1)
                }
            }
        }

        viewModel.broadcastHappened.observe(this) { broadcasting ->
            binding.stageBroadcastOptions.broadcasting = broadcasting
            if (!broadcasting) viewModel.stopBroadcast()
            binding.btnBroadcast.setBroadcastingTextColor(broadcasting)
        }

        viewModel.removeAllViews.observe { remove ->
            if (remove) {
                with(binding.participantListLayout) {
                    removeAllViews()
                }
            }
        }
    }

    /**
     * Initialize views
     */
    private fun initUi() {
        getSavedData()

        with(binding) {
            if (token.isNotEmpty()) stageOptionView.edtToken.setText(token)

            with(stageOptionView) {
                version = BroadcastSession.getVersion()

                btnJoin.setOnClickListener {
                    with(viewModel) {
                        if (joined) leaveStage() else initStage()
                    }
                }
            }

            btnAudio.setOnCheckedChangeListener { _, isChecked ->
                with(viewModel) {
                    if (isChecked) unmuteMicrophoneStream() else muteMicrophoneStream()
                }
            }

            btnCamera.setOnCheckedChangeListener { _, isChecked ->
                with(viewModel) {
                    if (isChecked) unmuteImageStream() else muteImageStream()
                }
            }

            btnLeave.setOnClickListener {
                leaveStage()
            }

            btnBroadcast.setOnClickListener {
                binding.stageBroadcastOptions.root.toggleVisibility()
            }

            stageBroadcastOptions.btnStart.setOnClickListener {
                if (viewModel.broadcasting) viewModel.stopBroadcast() else startBroadcast()
            }
        }
    }

    private fun clearPreview() {
        binding.participantListLayout.removeAllViews()
    }

    /**
     * Get saved token and broadcast data from SharedPreferences
     */
    private fun getSavedData() {
        with(getToken()) {
            if (isNotEmpty()) binding.stageOptionView.edtToken.setText(this)
        }
        with(getEndpoint()) {
            if (isNotEmpty()) binding.stageBroadcastOptions.edtEndpoint.setText(this)
        }
        with(getStreamKey()) {
            if (isNotEmpty()) binding.stageBroadcastOptions.edtStream.setText(this)
        }
    }

    /**
     * Leave session and reset UI
     */
    private fun leaveStage() {
        viewModel.leaveStage()
        with(binding) {
            joined = false
            stageBroadcastOptions.root.hide()
        }
    }

    /**
     * Save stream key and endpoint in SharedPreferences
     * Start broadcast
     */
    private fun startBroadcast() {
        with(binding.stageBroadcastOptions) {
            hideKeyboard()
            val key = edtStream.text.toString()
            val endpoint = edtEndpoint.text.toString()
            try {
                if (key.isNotEmpty() && endpoint.isNotEmpty()) {
                    saveBroadcastData(key, endpoint)
                    viewModel.startBroadcast(key, endpoint)
                } else {
                    Toast.makeText(this@StageActivity, getString(R.string.error_some_fields_are_empty), Toast.LENGTH_SHORT).show()
                }
            } catch (e: BroadcastException) {
                Toast.makeText(this@StageActivity, getString(R.string.error_start_session), Toast.LENGTH_SHORT).show()
            }
        }
    }

}
