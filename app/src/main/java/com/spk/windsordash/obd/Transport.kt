package com.spk.windsordash.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** A byte pipe to an ELM327-compatible OBD adapter. */
interface ObdTransport {
    val incoming: Channel<ByteArray>
    suspend fun connect()
    suspend fun write(data: ByteArray)
    fun close()
}

/** How to reach the adapter. Serialised into prefs as "kind|address|name". */
data class AdapterRef(val kind: Kind, val address: String, val name: String) {
    enum class Kind { BLE, CLASSIC, WIFI, DEMO }

    fun encode() = "${kind.name}|$address|$name"

    companion object {
        fun decode(s: String?): AdapterRef? {
            if (s.isNullOrBlank()) return null
            val p = s.split('|')
            if (p.size < 3) return null
            return runCatching { AdapterRef(Kind.valueOf(p[0]), p[1], p[2]) }.getOrNull()
        }
        val WIFI_DEFAULT = AdapterRef(Kind.WIFI, "192.168.0.10:35000", "Wi-Fi OBD (192.168.0.10)")
        val DEMO = AdapterRef(Kind.DEMO, "demo", "Demo (simulated car)")
    }
}

/* ------------------------------------------------------------------ */
/* Stream-based transports (classic Bluetooth SPP, Wi-Fi TCP)          */
/* ------------------------------------------------------------------ */

abstract class StreamTransport : ObdTransport {
    override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    protected var input: InputStream? = null
    protected var output: OutputStream? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    protected fun startReader() {
        val ins = input ?: return
        scope.launch {
            val buf = ByteArray(512)
            try {
                while (isActive) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (n > 0) incoming.trySend(buf.copyOf(n))
                }
            } catch (_: Exception) {
            }
            incoming.close(IllegalStateException("Adapter link closed"))
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: error("Not connected")
        out.write(data)
        out.flush()
    }

    override fun close() {
        scope.cancel()
        runCatching { input?.close() }
        runCatching { output?.close() }
        closeSocket()
    }

    protected abstract fun closeSocket()
}

class TcpTransport(private val hostPort: String) : StreamTransport() {
    private var socket: Socket? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "35000").toIntOrNull() ?: 35000
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 5000)
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
        startReader()
    }

    override fun closeSocket() {
        runCatching { socket?.close() }
    }
}

@SuppressLint("MissingPermission")
class ClassicTransport(private val address: String) : StreamTransport() {
    private var socket: BluetoothSocket? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("No Bluetooth on this phone")
        val dev = adapter.getRemoteDevice(address)
        runCatching { adapter.cancelDiscovery() }
        val s = try {
            dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
        } catch (e: Exception) {
            // Some cheap ELM327 clones only answer the insecure socket.
            dev.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
        startReader()
    }

    override fun closeSocket() {
        runCatching { socket?.close() }
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/* ------------------------------------------------------------------ */
/* Bluetooth Low Energy transport                                      */
/* ------------------------------------------------------------------ */

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleTransport(private val context: Context, private val address: String) : ObdTransport {
    override val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private var connected = CompletableDeferred<Unit>()
    private var servicesFound = CompletableDeferred<Unit>()
    private var descriptorWritten = CompletableDeferred<Unit>()
    private var writeDone = CompletableDeferred<Unit>()
    private val writeLock = Mutex()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val err = IllegalStateException("Bluetooth adapter disconnected (status $status)")
                connected.completeExceptionally(err)
                servicesFound.completeExceptionally(err)
                incoming.close(err)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesFound.complete(Unit)
            else servicesFound.completeExceptionally(IllegalStateException("Service discovery failed ($status)"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorWritten.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.complete(Unit)
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { incoming.trySend(it.copyOf()) }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(value.copyOf())
        }
    }

    override suspend fun connect() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("No Bluetooth on this phone")
        val dev = adapter.getRemoteDevice(address)
        val g = withContext(Dispatchers.Main) {
            dev.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } ?: error("Could not open Bluetooth LE link")
        gatt = g
        withTimeout(12_000) { connected.await() }
        delay(300)
        g.discoverServices()
        withTimeout(10_000) { servicesFound.await() }
        pickCharacteristics(g)
        val n = notifyChar ?: error("Adapter has no notify characteristic — is this an OBD dongle?")
        g.setCharacteristicNotification(n, true)
        n.getDescriptor(CCCD)?.let { d ->
            descriptorWritten = CompletableDeferred()
            d.value = if (n.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            g.writeDescriptor(d)
            withTimeoutOrNull(3000) { descriptorWritten.await() }
        }
    }

    private fun pickCharacteristics(g: BluetoothGatt) {
        // Known ELM327 BLE layouts first: (service, notify, write)
        for ((svc, nfy, wr) in KNOWN) {
            val s = g.getService(svc) ?: continue
            val n = s.getCharacteristic(nfy) ?: continue
            val w = s.getCharacteristic(wr) ?: continue
            notifyChar = n; writeChar = w; return
        }
        // Fallback: any service exposing both a notify and a write characteristic
        for (s in g.services) {
            val chars = s.characteristics
            val n = chars.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            } ?: continue
            val w = chars.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            } ?: continue
            notifyChar = n; writeChar = w; return
        }
    }

    override suspend fun write(data: ByteArray) = writeLock.withLock {
        val g = gatt ?: error("Not connected")
        val w = writeChar ?: error("Not connected")
        val noResp = w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 &&
            w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
        w.writeType = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var i = 0
        while (i < data.size) {
            val chunk = data.copyOfRange(i, minOf(i + 20, data.size))
            writeDone = CompletableDeferred()
            w.value = chunk
            g.writeCharacteristic(w)
            withTimeoutOrNull(1000) { writeDone.await() }
            i += 20
        }
    }

    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        incoming.close()
    }

    companion object {
        private fun u(s: String): UUID = UUID.fromString(
            if (s.length == 4) "0000$s-0000-1000-8000-00805f9b34fb" else s
        )

        val CCCD: UUID = u("2902")
        val KNOWN = listOf(
            Triple(u("fff0"), u("fff1"), u("fff2")),
            Triple(u("ffe0"), u("ffe1"), u("ffe1")),
            Triple(u("18f0"), u("2af0"), u("2af1")),
            Triple(
                u("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
                u("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
                u("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")
            ),
        )
        /** Service UUIDs worth showing first in the scan list. */
        val SCAN_HINTS = KNOWN.map { it.first }.toSet()
    }
}

fun AdapterRef.openTransport(context: Context): ObdTransport = when (kind) {
    AdapterRef.Kind.BLE -> BleTransport(context, address)
    AdapterRef.Kind.CLASSIC -> ClassicTransport(address)
    AdapterRef.Kind.WIFI -> TcpTransport(address)
    AdapterRef.Kind.DEMO -> error("Demo has no transport")
}
