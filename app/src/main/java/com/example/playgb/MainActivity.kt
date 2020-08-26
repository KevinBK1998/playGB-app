package com.example.playgb

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

var isAppPaused = false
var isAppRunning = false

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        isAppPaused = false
        isAppRunning = true
    }

    override fun onPause() {
        super.onPause()
        isAppPaused = true
    }

    override fun onResume() {
        super.onResume()
        isAppPaused = false
    }

    override fun onStop() {
        super.onStop()
        isAppRunning = false
        finish()
    }
}