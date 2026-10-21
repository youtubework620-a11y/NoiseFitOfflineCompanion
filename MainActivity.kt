package com.example.noisefitoffline

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import java.util.UUID

class MainActivity : Activity() {
    private val deviceAddress = "DD:27:DB:35:F5:F5"
    private val serviceId = UUID.fromString("c3e6fea0-e966-1000-8000-be99c223df6a")
    private val writeId = UUID.fromString("c3e6fea1-e966-1000-8000-be99c223df6a")
    private val notifyId = UUID.fromString("c3e6fea2-e966-1000-8000-be99c223df6a")
    private val cccId = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private lateinit var status: TextView
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var sequence = 1
    private var pendingSync = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 24)
        }
        status = TextView(this).apply { textSize = 17f; text = "Ready. Turn on Bluetooth and keep the watch nearby." }
        val connect = Button(this).apply {
            text = "Connect to EVOLVE SPORT"
            setOnClickListener { connectWatch() }
        }
        val sync = Button(this).apply {
            text = "Send phone date and time"
            setOnClickListener {
                if (writeChar == null || gatt == null) {
                    status.text = "Connect to the watch first."
                } else {
                    pendingSync = true
                    sendTimePacket()
                }
            }
        }
        val note = TextView(this).apply {
            textSize = 13f
            text = "\nExperimental diagnostic build. The watch may require an additional handshake or packet behavior from the original app. A successful Bluetooth write does not guarantee the watch accepted the time."
        }
        layout.addView(status)
        layout.addView(connect)
        layout.addView(sync)
        layout.addView(note)
        setContentView(ScrollView(this).apply { addView(layout) })
        requestBlePermissions()
    }

    private fun requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            val missing = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100)
        } else if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
        }
    }

    private fun hasPermissions(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
             checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

    private fun connectWatch() {
        if (!hasPermissions()) {
            status.text = "Grant Bluetooth permissions, then tap Connect again."
            requestBlePermissions()
            return
        }
        try {
            val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter
            if (adapter == null || !adapter.isEnabled) {
                status.text = "Turn on Bluetooth, then tap Connect again."
                return
            }
            status.text = "Connecting to saved watch address…"
            gatt?.close()
            val device = adapter.getRemoteDevice(deviceAddress)
            gatt = if (Build.VERSION.SDK_INT >= 23)
                device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(this, false, callback)
        } catch (e: Exception) {
            status.text = "Could not connect: ${e.message}"
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            runOnUiThread {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    status.text = "Connection error ($statusCode). Try disconnecting the official app and reconnect."
                    return@runOnUiThread
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status.text = "Connected. Discovering watch services…"
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    writeChar = null
                    status.text = "Disconnected."
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            val service = g.getService(serviceId)
            val w = service?.getCharacteristic(writeId)
            val n = service?.getCharacteristic(notifyId)
            runOnUiThread {
                if (statusCode != BluetoothGatt.GATT_SUCCESS || w == null) {
                    status.text = "Connected, but expected watch service/write characteristic was not found."
                } else {
                    writeChar = w
                    status.text = "Connected and write characteristic found. Tap Send phone date and time."
                }
            }
            if (n != null && n.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                g.setCharacteristicNotification(n, true)
                val descriptor = n.getDescriptor(cccId)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(descriptor)
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            runOnUiThread {
                status.text = if (statusCode == BluetoothGatt.GATT_SUCCESS)
                    "Bluetooth accepted the write. Check whether the watch time changed."
                else "Write failed ($statusCode)."
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int, value: ByteArray) {
            runOnUiThread {
                status.text = if (statusCode == BluetoothGatt.GATT_SUCCESS)
                    "Bluetooth accepted the write. Check whether the watch time changed."
                else "Write failed ($statusCode)."
            }
        }
    }

    private fun sendTimePacket() {
        val g = gatt ?: return
        val characteristic = writeChar ?: return
        val cal = Calendar.getInstance()
        val payload = byteArrayOf(
            (cal.get(Calendar.YEAR) % 100).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte()
        )
        // Inner command: protocol version 2, reserved byte 0, command 0x20, payload length 6.
        val inner = byteArrayOf(2, 0, 0x20, 0, payload.size.toByte()) + payload
        val crc = crc8(inner)
        // Outer FunDo frame: marker 0xBA, command flags 0x20, length, CRC, sequence.
        val outer = byteArrayOf(
            0xBA.toByte(), 0x20, ((inner.size shr 8) and 0xff).toByte(),
            (inner.size and 0xff).toByte(), 0, crc.toByte(),
            ((sequence shr 8) and 0xff).toByte(), (sequence and 0xff).toByte()
        ) + inner
        sequence = (sequence + 1) and 0xffff
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val result = g.writeCharacteristic(characteristic, outer, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                status.text = "Time packet submitted (status $result). This is experimental; confirm on the watch."
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = outer
                @Suppress("DEPRECATION")
                val ok = g.writeCharacteristic(characteristic)
                status.text = if (ok) "Time packet submitted. Confirm whether the watch time changed."
                    else "Android rejected the write request."
            }
        } catch (e: Exception) {
            status.text = "Could not send packet: ${e.message}"
        }
    }

    private fun crc8(data: ByteArray): Int {
        var crc = 0xff
        for (item in data) {
            crc = crc xor (item.toInt() and 0xff)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xb8 else crc ushr 1
            }
        }
        return crc and 0xff
    }

    override fun onDestroy() {
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        super.onDestroy()
    }
}
