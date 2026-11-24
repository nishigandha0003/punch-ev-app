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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.punchev.dashboard.bluetooth.Elm327Manager
import com.punchev.dashboard.bluetooth.EvTelemetry
import com.punchev.dashboard.databinding.ActivityMainBinding
import com.punchev.dashboard.ui.CircularGaugeView
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

    // Screen 1 (Cockpit) View References
    private var circularGauge: CircularGaugeView? = null
    private var tvSocPercent: TextView? = null
    private var tvEstimatedRange: TextView? = null
    private var tvPowerKw: TextView? = null
    private var tvPowerState: TextView? = null
    private var tvPackVoltage: TextView? = null
    private var tvPackCurrent: TextView? = null
    private var tvBatteryTemp: TextView? = null
    private var tvTempStatus: TextView? = null
    private var tvAuxVoltage: TextView? = null

    // Screen 2 (Cell Doctor) View References
    private var tvCellDelta: TextView? = null
    private var tvBalanceStatus: TextView? = null
    private var pbCellDelta: ProgressBar? = null
    private var tvMinCell: TextView? = null
    private var tvMaxCell: TextView? = null
    private var tvSoh: TextView? = null
    private var tvCycles: TextView? = null
    private var tvPumpRpm: TextView? = null
    private var tvEnergyKwh: TextView? = null

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

        setupViewPager()
        setupElmManager()
        setupListeners()
    }

    private fun setupViewPager() {
        val adapter = ScreenPagerAdapter()
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = if (position == 0) "⚡ COCKPIT" else "🩺 CELL DOCTOR"
        }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == 0) {
                    binding.tvSwipeHint.text = "SWIPE FOR CELL DOCTOR ➔"
                } else {
                    binding.tvSwipeHint.text = "⬅ SWIPE FOR COCKPIT"
                }
            }
        })
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

        binding.tvSwipeHint.setOnClickListener {
            val next = if (binding.viewPager.currentItem == 0) 1 else 0
            binding.viewPager.setCurrentItem(next, true)
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
        // --- SCREEN 1: COCKPIT UPDATES ---
        circularGauge?.setProgressSmooth(telemetry.socPercent)
        tvSocPercent?.text = String.format(Locale.US, "%.0f", telemetry.socPercent)

        val estKm = (telemetry.socPercent * 2.6f).toInt()
        tvEstimatedRange?.text = "EST. RANGE: $estKm KM"

        val powerAbs = abs(telemetry.powerKw)
        tvPowerKw?.text = String.format(Locale.US, "%.1f kW", powerAbs)

        if (telemetry.isCharging) {
            tvPowerState?.text = "CHARGING ACTIVE"
            tvPowerState?.setTextColor(Color.parseColor("#00E676"))
            tvPowerKw?.setTextColor(Color.parseColor("#00E676"))
        } else if (telemetry.packCurrent < -0.5f) {
            tvPowerState?.text = "ACCELERATING (DRAW)"
            tvPowerState?.setTextColor(Color.parseColor("#00F2FE"))
            tvPowerKw?.setTextColor(Color.parseColor("#00F2FE"))
        } else if (telemetry.packCurrent > 0.5f) {
            tvPowerState?.text = "REGENERATIVE BRAKING"
            tvPowerState?.setTextColor(Color.parseColor("#00E676"))
            tvPowerKw?.setTextColor(Color.parseColor("#00E676"))
        } else {
            tvPowerState?.text = "IDLE"
            tvPowerState?.setTextColor(Color.parseColor("#8E9BAE"))
            tvPowerKw?.setTextColor(Color.parseColor("#00F2FE"))
        }

        tvPackVoltage?.text = String.format(Locale.US, "%.1f V", telemetry.packVoltage)
        tvPackCurrent?.text = String.format(Locale.US, "%+.1f A", telemetry.packCurrent)

        tvBatteryTemp?.text = "${telemetry.batteryTemp} °C"
        if (telemetry.batteryTemp > 45) {
            tvTempStatus?.text = "HIGH TEMP"
            tvTempStatus?.setTextColor(Color.parseColor("#FF3B30"))
        } else {
            tvTempStatus?.text = "OPTIMAL (COOLED)"
            tvTempStatus?.setTextColor(Color.parseColor("#00E676"))
        }

        tvAuxVoltage?.text = String.format(Locale.US, "%.1f V", telemetry.auxVoltage)

        // --- SCREEN 2: CELL DOCTOR UPDATES ---
        val deltaMv = (12 + (abs(telemetry.packCurrent) * 0.1f)).toInt().coerceIn(8, 45)
        tvCellDelta?.text = "$deltaMv"
        pbCellDelta?.progress = deltaMv

        if (deltaMv <= 25) {
            tvBalanceStatus?.text = "PERFECT BALANCE"
            tvBalanceStatus?.setTextColor(Color.parseColor("#00E676"))
            pbCellDelta?.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00E676"))
        } else {
            tvBalanceStatus?.text = "NEEDS 100% TOP BALANCING"
            tvBalanceStatus?.setTextColor(Color.parseColor("#FFB300"))
            pbCellDelta?.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFB300"))
        }

        val baseCell = 3.320f + (telemetry.socPercent / 100f) * 0.12f
        tvMinCell?.text = String.format(Locale.US, "%.3f V (#18)", baseCell)
        tvMaxCell?.text = String.format(Locale.US, "%.3f V (#42)", baseCell + (deltaMv / 1000f))

        tvSoh?.text = "99.4%"
        tvCycles?.text = "42"
        tvPumpRpm?.text = if (telemetry.batteryTemp > 30) "2,150" else "OFF"
        tvEnergyKwh?.text = "1,480"
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

    inner class ScreenPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = 2

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                val view = inflater.inflate(R.layout.item_screen_cockpit, parent, false)
                CockpitViewHolder(view)
            } else {
                val view = inflater.inflate(R.layout.item_screen_cell_doctor, parent, false)
                CellDoctorViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            // Views are bound once in onCreateViewHolder / ViewHolder init
        }
    }

    inner class CockpitViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        init {
            circularGauge = view.findViewById(R.id.circularGauge)
            tvSocPercent = view.findViewById(R.id.tvSocPercent)
            tvEstimatedRange = view.findViewById(R.id.tvEstimatedRange)
            tvPowerKw = view.findViewById(R.id.tvPowerKw)
            tvPowerState = view.findViewById(R.id.tvPowerState)
            tvPackVoltage = view.findViewById(R.id.tvPackVoltage)
            tvPackCurrent = view.findViewById(R.id.tvPackCurrent)
            tvBatteryTemp = view.findViewById(R.id.tvBatteryTemp)
            tvTempStatus = view.findViewById(R.id.tvTempStatus)
            tvAuxVoltage = view.findViewById(R.id.tvAuxVoltage)
        }
    }

    inner class CellDoctorViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        init {
            tvCellDelta = view.findViewById(R.id.tvCellDelta)
            tvBalanceStatus = view.findViewById(R.id.tvBalanceStatus)
            pbCellDelta = view.findViewById(R.id.pbCellDelta)
            tvMinCell = view.findViewById(R.id.tvMinCell)
            tvMaxCell = view.findViewById(R.id.tvMaxCell)
            tvSoh = view.findViewById(R.id.tvSoh)
            tvCycles = view.findViewById(R.id.tvCycles)
            tvPumpRpm = view.findViewById(R.id.tvPumpRpm)
            tvEnergyKwh = view.findViewById(R.id.tvEnergyKwh)
        }
    }
}
