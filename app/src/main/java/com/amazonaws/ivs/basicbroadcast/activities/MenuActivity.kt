package com.amazonaws.ivs.basicbroadcast.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
    }
}
