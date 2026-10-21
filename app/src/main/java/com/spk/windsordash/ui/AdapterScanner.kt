package com.spk.windsordash.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import com.spk.windsordash.obd.AdapterRef
import com.spk.windsordash.obd.BleTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class FoundAdapter(val ref: AdapterRef, val likelyObd: Boolean, val rssi: Int?)

/** Lists bonded classic devices and scans for BLE OBD dongles. */
@SuppressLint("MissingPermission")
class AdapterScanner {
    private val _found = MutableStateFlow<List<FoundAdapter>>(emptyList())
    val found: StateFlow<List<FoundAdapter>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val bt get() = BluetoothAdapter.getDefaultAdapter()

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = runCatching { dev.name }.getOrNull() ?: result.scanRecord?.deviceName ?: return
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            val likely = uuids.any { it in BleTransport.SCAN_HINTS } || looksLikeObd(name)
            add(FoundAdapter(AdapterRef(AdapterRef.Kind.BLE, dev.address, name), likely, result.rssi))
        }
    }

    private fun add(f: FoundAdapter) {
        val list = _found.value.filterNot { it.ref.address == f.ref.address && it.ref.kind == f.ref.kind } + f
        _found.value = list.sortedWith(compareByDescending<FoundAdapter> { it.likelyObd }.thenByDescending { it.rssi ?: -200 })
    }

    fun start() {
        val a = bt ?: return
        _found.value = emptyList()
        // Paired classic-Bluetooth dongles (most cheap ELM327s)
        runCatching {
            a.bondedDevices.orEmpty()
                .filter { it.type == BluetoothDevice.DEVICE_TYPE_CLASSIC || it.type == BluetoothDevice.DEVICE_TYPE_DUAL }
                .forEach { d ->
                    val n = d.name ?: d.address
                    add(FoundAdapter(AdapterRef(AdapterRef.Kind.CLASSIC, d.address, "$n (paired)"), looksLikeObd(n), null))
                }
        }
        runCatching {
            a.bluetoothLeScanner?.startScan(
                null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb
            )
            _scanning.value = true
        }
    }

    fun stop() {
        runCatching { bt?.bluetoothLeScanner?.stopScan(cb) }
        _scanning.value = false
    }

    companion object {
        fun looksLikeObd(name: String) =
            Regex("obd|elm|vlink|v-link|vgate|icar|veepeak|obdlink|konnwei|carista|scan|ios-vlink|android-vlink", RegexOption.IGNORE_CASE)
                .containsMatchIn(name)
    }
}
