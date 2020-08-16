package com.example.playgb

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

var isAppRunning = true

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        isAppRunning = true
    }

    override fun onPause() {
        super.onPause()
        isAppRunning = false
    }

    override fun onStop() {
        super.onStop()
        isAppRunning = false
        finish()
    }
}