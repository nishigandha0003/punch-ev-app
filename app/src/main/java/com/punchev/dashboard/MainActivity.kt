package com.punchev.dashboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.punchev.dashboard.bluetooth.Elm327Manager
import com.punchev.dashboard.bluetooth.EvTelemetry
import com.punchev.dashboard.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var elmManager: Elm327Manager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var isDemoMode = false
    private var demoJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            showDevicePicker()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required to connect", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bm?.adapter

        setupElmManager()
        setupListeners()
    }

    private fun setupElmManager() {
        elmManager = Elm327Manager(
            onTelemetryReceived = { telemetry ->
                runOnUiThread { updateUiWithTelemetry(telemetry) }
            },
            onStatusChanged = { state ->
                runOnUiThread { updateConnectionStatus(state) }
            }
        )
    }

    private fun setupListeners() {
        binding.btnConnect.setOnClickListener {
            if (isDemoMode) {
                stopDemoMode()
            }
            checkPermissionsAndConnect()
        }

        binding.btnDemoToggle.setOnClickListener {
            if (isDemoMode) {
                stopDemoMode()
            } else {
                startDemoMode()
            }
        }
    }

    private fun checkPermissionsAndConnect() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            showDevicePicker()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth in your settings", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter!!.bondedDevices
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices found. Pair your OBD adapter first!", Toast.LENGTH_LONG).show()
            return
        }

        val deviceList = pairedDevices.toList()
        val names = deviceList.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select your OBD-II Adapter")
            .setItems(names) { _, which ->
                val selected = deviceList[which]
                lifecycleScope.launch {
                    elmManager?.connect(selected)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateConnectionStatus(state: Elm327Manager.ConnectionState) {
        when (state) {
            Elm327Manager.ConnectionState.DISCONNECTED -> {
                binding.tvStatus.text = getString(R.string.status_disconnected)
                binding.statusDot.setBackgroundColor(Color.parseColor("#FF3B30"))
                binding.btnConnect.text = getString(R.string.btn_connect)
            }
            Elm327Manager.ConnectionState.CONNECTING -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.statusDot.setBackgroundColor(Color.parseColor("#FFB300"))
                binding.btnConnect.text = "CANCEL"
            }
            Elm327Manager.ConnectionState.INITIALIZING -> {
                binding.tvStatus.text = "INITIALIZING ZIPTRON BUS..."
                binding.statusDot.setBackgroundColor(Color.parseColor("#00F2FE"))
            }
            Elm327Manager.ConnectionState.CONNECTED -> {
                binding.tvStatus.text = getString(R.string.status_connected)
                binding.statusDot.setBackgroundColor(Color.parseColor("#00E676"))
                binding.btnConnect.text = getString(R.string.btn_disconnect)
            }
            Elm327Manager.ConnectionState.FAILED -> {
                binding.tvStatus.text = "CONNECTION FAILED"
                binding.statusDot.setBackgroundColor(Color.parseColor("#FF3B30"))
                binding.btnConnect.text = getString(R.string.btn_connect)
                Toast.makeText(this, "Failed to connect to OBD. Ensure ignition is ON.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUiWithTelemetry(telemetry: EvTelemetry) {
        // 1. Smooth Circular Gauge
        binding.circularGauge.setProgressSmooth(telemetry.socPercent)
        binding.tvSocPercent.text = String.format(Locale.US, "%.0f", telemetry.socPercent)

        // 2. Range estimation (Punch EV real-world ~2.6 km per 1% SoC)
        val estKm = (telemetry.socPercent * 2.6f).toInt()
        binding.tvEstimatedRange.text = "EST. RANGE: $estKm KM"

        // 3. Power Meter & State
        val powerAbs = abs(telemetry.powerKw)
        binding.tvPowerKw.text = String.format(Locale.US, "%.1f kW", powerAbs)

        if (telemetry.isCharging) {
            binding.tvPowerState.text = "CHARGING ACTIVE"
            binding.tvPowerState.setTextColor(Color.parseColor("#00E676"))
            binding.tvPowerKw.setTextColor(Color.parseColor("#00E676"))
        } else if (telemetry.packCurrent < -0.5f) {
            binding.tvPowerState.text = "ACCELERATING (DRAW)"
            binding.tvPowerState.setTextColor(Color.parseColor("#00F2FE"))
            binding.tvPowerKw.setTextColor(Color.parseColor("#00F2FE"))
        } else if (telemetry.packCurrent > 0.5f) {
            binding.tvPowerState.text = "REGENERATIVE BRAKING"
            binding.tvPowerState.setTextColor(Color.parseColor("#00E676"))
            binding.tvPowerKw.setTextColor(Color.parseColor("#00E676"))
        } else {
            binding.tvPowerState.text = "IDLE"
            binding.tvPowerState.setTextColor(Color.parseColor("#8E9BAE"))
            binding.tvPowerKw.setTextColor(Color.parseColor("#00F2FE"))
        }

        // 4. Pack Voltage & Current
        binding.tvPackVoltage.text = String.format(Locale.US, "%.1f V", telemetry.packVoltage)
        binding.tvPackCurrent.text = String.format(Locale.US, "%+.1f A", telemetry.packCurrent)

        // 5. Battery Temperature
        binding.tvBatteryTemp.text = "${telemetry.batteryTemp} °C"
        if (telemetry.batteryTemp > 45) {
            binding.tvTempStatus.text = "HIGH TEMP"
            binding.tvTempStatus.setTextColor(Color.parseColor("#FF3B30"))
        } else {
            binding.tvTempStatus.text = "OPTIMAL (COOLED)"
            binding.tvTempStatus.setTextColor(Color.parseColor("#00E676"))
        }

        // 6. 12V Auxiliary
        binding.tvAuxVoltage.text = String.format(Locale.US, "%.1f V", telemetry.auxVoltage)
    }

    private fun startDemoMode() {
        isDemoMode = true
        binding.btnDemoToggle.text = "EXIT DEMO"
        binding.tvStatus.text = "SIMULATION ACTIVE"
        binding.statusDot.setBackgroundColor(Color.parseColor("#00F2FE"))

        demoJob = lifecycleScope.launch {
            var simSoc = 82f
            var timeStep = 0
            while (isActive) {
                timeStep++
                // Simulate acceleration and regen wave
                val simCurrent = if (timeStep % 10 < 6) -25f - (timeStep % 5) * 8f else 18f
                val simVoltage = 332f + (simCurrent * 0.1f)
                val simPower = (simVoltage * simCurrent) / 1000f

                val telemetry = EvTelemetry(
                    socPercent = simSoc,
                    packVoltage = simVoltage,
                    packCurrent = simCurrent,
                    powerKw = simPower,
                    batteryTemp = 32,
                    auxVoltage = 12.7f,
                    isCharging = false,
                    isRegen = simCurrent > 0
                )

                updateUiWithTelemetry(telemetry)
                delay(1200)
            }
        }
    }

    private fun stopDemoMode() {
        isDemoMode = false
        demoJob?.cancel()
        demoJob = null
        binding.btnDemoToggle.text = getString(R.string.mode_demo)
        binding.tvStatus.text = getString(R.string.status_disconnected)
        binding.statusDot.setBackgroundColor(Color.parseColor("#FF3B30"))
    }

    override fun onDestroy() {
        super.onDestroy()
        elmManager?.disconnect()
    }
}

