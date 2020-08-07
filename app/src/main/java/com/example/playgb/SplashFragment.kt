package com.example.playgb

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import com.example.playgb.databinding.FragmentSplashBinding

private const val REQUEST_MIC = 1
private val PERMISSIONS_MIC = arrayOf(
    //Manifest.permission.READ_EXTERNAL_STORAGE,
    //Manifest.permission.WRITE_EXTERNAL_STORAGE,
    Manifest.permission.RECORD_AUDIO
)
private lateinit var binding: FragmentSplashBinding

class SplashFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = DataBindingUtil.inflate(
            inflater,
            R.layout.fragment_splash,
            container,
            false
        )
        @Suppress("deprecation")
        val flags =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        @Suppress("deprecation")
        activity?.window?.decorView?.systemUiVisibility = flags
        (activity as? AppCompatActivity)?.supportActionBar?.hide()
        binding.splashImage.setOnClickListener {
            it.findNavController().navigate(R.id.action_splashFragment_to_screenFragment)
        }
        verifyPermissions()
        return binding.root
    }

    private fun verifyPermissions() {
        // Check if we have permission
        val permission = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        )

        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            requestPermissions(
                PERMISSIONS_MIC,
                REQUEST_MIC
            )
        }
    }
}