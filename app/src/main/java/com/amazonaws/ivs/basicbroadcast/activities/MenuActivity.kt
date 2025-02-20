package com.amazonaws.ivs.basicbroadcast.activities

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.amazonaws.ivs.basicbroadcast.databinding.ActivityMenuBinding

class MenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.menuBroadcast.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        binding.menuMixerAndTransitions.setOnClickListener {
            startActivity(Intent(this, MixerActivity::class.java))
        }

        binding.menuCustomMediaSources.setOnClickListener {
            startActivity(Intent(this, CustomSourceActivity::class.java))
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.menuBroadcast) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            windowInsets
        }
    }
}
