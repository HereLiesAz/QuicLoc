package com.hereliesaz.quicloc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var whitelistManager: WhitelistManager
    private lateinit var adapter: WhitelistAdapter

    private val PERMISSIONS_REQUEST_CODE = 123
    private val BACKGROUND_LOCATION_REQUEST_CODE = 124

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        whitelistManager = WhitelistManager(this)

        setupViews()
        checkPermissions()
    }

    private fun setupViews() {
        val phoneNumberInput = findViewById<EditText>(R.id.phoneNumberInput)
        val addButton = findViewById<Button>(R.id.addButton)
        val recyclerView = findViewById<RecyclerView>(R.id.whitelistRecyclerView)

        adapter = WhitelistAdapter(whitelistManager.getNumbers().toList()) { number ->
            whitelistManager.removeNumber(number)
            updateList()
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        addButton.setOnClickListener {
            val number = phoneNumberInput.text.toString().trim()
            if (number.isNotEmpty()) {
                whitelistManager.addNumber(number)
                phoneNumberInput.text.clear()
                updateList()
                Toast.makeText(this, "Number added to whitelist", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateList() {
        adapter.updateNumbers(whitelistManager.getNumbers().toList())
    }

    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    BACKGROUND_LOCATION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                checkBackgroundLocationPermission()
            } else {
                Toast.makeText(this, "All permissions are required for QuicLoc to function.", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == BACKGROUND_LOCATION_REQUEST_CODE) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Background location permission is required for QuicLoc to function when the app is closed.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
