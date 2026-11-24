package com.punchev.dashboard.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class Elm327Manager(
    private val onTelemetryReceived: (EvTelemetry) -> Unit,
    private val onStatusChanged: (ConnectionState) -> Unit
) {
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        INITIALIZING,
        CONNECTED,
        FAILED
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isRunning = false

    companion object {
        private const val TAG = "Elm327Manager"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        try {
            onStatusChanged(ConnectionState.CONNECTING)
            socket?.close()

            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket?.connect()

            inputStream = socket?.inputStream
            outputStream = socket?.outputStream

            onStatusChanged(ConnectionState.INITIALIZING)

            // Send Init Commands
            for (cmd in TataEvPids.INIT_COMMANDS) {
                sendCommand(cmd)
                delay(120)
            }

            onStatusChanged(ConnectionState.CONNECTED)
            isRunning = true
            startPollingLoop()

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            disconnect()
            onStatusChanged(ConnectionState.FAILED)
        }
    }

    private suspend fun startPollingLoop() = withContext(Dispatchers.IO) {
        var currentSoc = 75f
        var currentPackVoltage = 328f
        var currentAmps = 0f
        var currentTemp = 31
        var currentAux = 12.8f

        while (isRunning && socket?.isConnected == true) {
            try {
                // 1. Auxiliary 12V
                val auxResp = sendCommand(TataEvPids.CMD_AUX_VOLTAGE)
                val auxVal = TataEvPids.parseAuxVoltage(auxResp)
                if (auxVal > 0) currentAux = auxVal
                delay(60)

                // 2. Battery SoC %
                val socResp = sendCommand(TataEvPids.PID_SOC)
                val socVal = TataEvPids.parseSoC(socResp)
                if (socVal >= 0) currentSoc = socVal
                delay(60)

                // 3. Pack Voltage
                val voltResp = sendCommand(TataEvPids.PID_PACK_VOLTAGE)
                val voltVal = TataEvPids.parsePackVoltage(voltResp)
                if (voltVal > 0) currentPackVoltage = voltVal
                delay(60)

                // 4. Current (Amps)
                val currResp = sendCommand(TataEvPids.PID_PACK_CURRENT)
                val currVal = TataEvPids.parseCurrent(currResp)
                currentAmps = currVal
                delay(60)

                // 5. Battery Temp
                val tempResp = sendCommand(TataEvPids.PID_BATTERY_TEMP)
                val tempVal = TataEvPids.parseBatteryTemp(tempResp)
                currentTemp = tempVal
                delay(60)

                // Calculate kW (Power = V * I / 1000)
                val powerKw = (currentPackVoltage * currentAmps) / 1000f
                val isCharging = currentAmps > 0.5f

                val telemetry = EvTelemetry(
                    socPercent = currentSoc,
                    packVoltage = currentPackVoltage,
                    packCurrent = currentAmps,
                    powerKw = powerKw,
                    batteryTemp = currentTemp,
                    auxVoltage = currentAux,
                    isCharging = isCharging,
                    isRegen = currentAmps > 0.5f && !isCharging
                )

                withContext(Dispatchers.Main) {
                    onTelemetryReceived(telemetry)
                }

                delay(500) // Poll update rate

            } catch (e: Exception) {
                Log.e(TAG, "Error in polling loop: ${e.message}")
                if (!isRunning) break
                delay(1000)
            }
        }
    }

    private fun sendCommand(cmd: String): String {
        return try {
            outputStream?.write((cmd + "\r").toByteArray())
            outputStream?.flush()

            val buffer = ByteArray(1024)
            val builder = StringBuilder()
            var bytesRead: Int
            val startTime = System.currentTimeMillis()

            // Wait for response ending with '>'
            while (System.currentTimeMillis() - startTime < 1500) {
                if (inputStream?.available() ?: 0 > 0) {
                    bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        val chunk = String(buffer, 0, bytesRead)
                        builder.append(chunk)
                        if (builder.contains(">")) break
                    }
                } else {
                    Thread.sleep(20)
                }
            }
            builder.toString()
        } catch (e: Exception) {
            ""
        }
    }

    fun disconnect() {
        isRunning = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        onStatusChanged(ConnectionState.DISCONNECTED)
    }
}

