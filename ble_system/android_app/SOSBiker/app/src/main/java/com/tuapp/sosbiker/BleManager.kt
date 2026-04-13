package com.tuapp.sosbiker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import java.util.UUID

class BleManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onBleStatus(text: String)
        fun onStatusValue(text: String)
        fun onAccValue(text: String)
        fun onGyroValue(text: String)
    }

    private val deviceName = "SOS_Biker_XIAO"

    private val serviceUuid = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
    private val statusUuid = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
    private val accUuid = UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214")
    private val gyroUuid = UUID.fromString("19B10003-E8F2-537E-4F6C-D104768A1214")
    private val commandUuid = UUID.fromString("19B10004-E8F2-537E-4F6C-D104768A1214")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    private var notifyQueue: MutableList<BluetoothGattCharacteristic> = mutableListOf()
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onBleStatus("BLE: Bluetooth off")
            return
        }

        listener.onBleStatus("BLE: scanning...")
        adapter.bluetoothLeScanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        commandCharacteristic = null
        notifyQueue.clear()
        listener.onBleStatus("BLE: disconnected")
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(command: String): Boolean {
        val gatt = bluetoothGatt ?: run {
            listener.onBleStatus("BLE: no gatt for command")
            return false
        }

        val characteristic = commandCharacteristic ?: run {
            listener.onBleStatus("BLE: command characteristic not found")
            return false
        }

        val payload = command.toByteArray(Charsets.UTF_8)

        return try {
            characteristic.value = payload
            val ok = gatt.writeCharacteristic(characteristic)

            if (!ok) {
                listener.onBleStatus("BLE: command write failed")
            }

            ok
        } catch (e: Exception) {
            listener.onBleStatus("BLE: command error ${e.message}")
            false
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device: BluetoothDevice = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: ""

            if (name == deviceName) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                listener.onBleStatus("BLE: device found, connecting...")
                bluetoothGatt = device.connectGatt(context, false, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onBleStatus("BLE: connected")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                commandCharacteristic = null
                notifyQueue.clear()
                listener.onBleStatus("BLE: disconnected")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(serviceUuid)
            if (service == null) {
                listener.onBleStatus("BLE: service not found")
                return
            }

            val statusChar = service.getCharacteristic(statusUuid)
            val accChar = service.getCharacteristic(accUuid)
            val gyroChar = service.getCharacteristic(gyroUuid)
            commandCharacteristic = service.getCharacteristic(commandUuid)

            notifyQueue.clear()

            if (statusChar != null) notifyQueue.add(statusChar)
            if (accChar != null) notifyQueue.add(accChar)
            if (gyroChar != null) notifyQueue.add(gyroChar)

            if (notifyQueue.isNotEmpty()) {
                listener.onBleStatus("BLE: enabling notifications...")
                enableNextNotification(gatt)
            } else {
                listener.onBleStatus("BLE: no characteristics found")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (notifyQueue.isNotEmpty()) {
                    notifyQueue.removeAt(0)
                }

                if (notifyQueue.isNotEmpty()) {
                    enableNextNotification(gatt)
                } else {
                    listener.onBleStatus("BLE: subscribed")
                }
            } else {
                listener.onBleStatus("BLE: descriptor write failed")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val text = value.toString(Charsets.UTF_8)

            when (characteristic.uuid) {
                statusUuid -> listener.onStatusValue(text)
                accUuid -> listener.onAccValue(text)
                gyroUuid -> listener.onGyroValue(text)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val text = characteristic.value?.toString(Charsets.UTF_8) ?: ""

            when (characteristic.uuid) {
                statusUuid -> listener.onStatusValue(text)
                accUuid -> listener.onAccValue(text)
                gyroUuid -> listener.onGyroValue(text)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNextNotification(gatt: BluetoothGatt) {
        val characteristic = notifyQueue.firstOrNull() ?: return

        val ok = gatt.setCharacteristicNotification(characteristic, true)
        if (!ok) {
            listener.onBleStatus("BLE: setNotification failed")
            return
        }

        val descriptor = characteristic.getDescriptor(cccdUuid)
        if (descriptor == null) {
            listener.onBleStatus("BLE: CCCD not found")
            return
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }
}