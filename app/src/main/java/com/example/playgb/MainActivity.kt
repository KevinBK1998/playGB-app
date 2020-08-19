package com.example.playgb

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

var isAppPaused = false

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        isAppPaused = false
    }

    override fun onPause() {
        super.onPause()
        isAppPaused = true
    }

    override fun onResume() {
        super.onResume()
        isAppPaused = false
    }
}