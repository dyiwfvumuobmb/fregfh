package com.android.imeisettings.service

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID

/**
 * BLE GATT server that sends security alerts to connected wearables.
 * Works without Google Play Services — uses Android Bluetooth LE API directly.
 *
 * Protocol:
 * - GATT Service UUID: 0000CAFE-0000-1000-8000-00805F9B34FB
 * - Alert Characteristic: 0000CAFE-0001-... (NOTIFY) — threat level byte + reason string
 * - Status Characteristic: 0000CAFE-0002-... (READ) — current threat level
 *
 * Any BLE-capable watch (Wear OS, Galaxy Watch, Amazfit, Garmin, etc.)
 * can connect and subscribe to notifications. For full integration, a companion
 * watch app would subscribe to the Alert characteristic.
 *
 * Even without a companion app, the phone sends a vibration pattern
 * via Android's standard notification channel to any connected Bluetooth device.
 */
@SuppressLint("MissingPermission")
object WearAlertService {

    private const val TAG = "CONSUL_WEAR_BLE"

    // Custom GATT Service & Characteristics
    val SERVICE_UUID: UUID = UUID.fromString("0000CAFE-0000-1000-8000-00805F9B34FB")
    val ALERT_CHAR_UUID: UUID = UUID.fromString("0000CAFE-0001-1000-8000-00805F9B34FB")
    val STATUS_CHAR_UUID: UUID = UUID.fromString("0000CAFE-0002-1000-8000-00805F9B34FB")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var isRunning = false
    private var currentThreatLevel: Int = 0
    private var currentReason: String = ""
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Start the BLE GATT server and begin advertising.
     * Call this from NetworkSecurityService when monitoring starts.
     */
    fun start(context: Context) {
        if (isRunning) return
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: run {
            Log.w(TAG, "BluetoothManager not available")
            return
        }
        val adapter = bluetoothManager.adapter ?: run {
            Log.w(TAG, "Bluetooth adapter not available")
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
            return
        }

        try {
            // Setup GATT Server
            gattServer = bluetoothManager.openGattServer(context, gattCallback)
            if (gattServer == null) {
                Log.e(TAG, "Failed to open GATT server")
                return
            }

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            // Alert characteristic (NOTIFY) — sends threat alerts
            val alertChar = BluetoothGattCharacteristic(
                ALERT_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            alertChar.addDescriptor(cccd)

            // Status characteristic (READ) — current threat level
            val statusChar = BluetoothGattCharacteristic(
                STATUS_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )

            service.addCharacteristic(alertChar)
            service.addCharacteristic(statusChar)
            gattServer?.addService(service)

            // Start BLE advertising
            advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser != null) {
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setConnectable(true)
                    .setTimeout(0) // Advertise indefinitely
                    .build()

                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build()

                val scanResponse = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build()

                advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
            }

            isRunning = true
            Log.i(TAG, "BLE Wear Alert Service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE service: ${e.message}")
        }
    }

    /**
     * Stop the BLE GATT server and advertising.
     */
    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
            gattServer = null
            advertiser = null
            subscribedDevices.clear()
            isRunning = false
            Log.i(TAG, "BLE Wear Alert Service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE service: ${e.message}")
        }
    }

    /**
     * Send a threat alert to all subscribed wearables.
     * Called from NetworkSecurityService when threat level changes.
     *
     * @param threatLevel 0-100 threat percentage
     * @param reason Human-readable reason string
     */
    fun sendAlert(threatLevel: Int, reason: String) {
        if (!isRunning || gattServer == null) return

        currentThreatLevel = threatLevel
        currentReason = reason

        val alertChar = gattServer?.getService(SERVICE_UUID)
            ?.getCharacteristic(ALERT_CHAR_UUID) ?: return

        // Payload: [threatLevel byte][reason UTF-8]
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + reasonBytes.size.coerceAtMost(19)) // BLE MTU limit safe
        payload[0] = threatLevel.toByte()
        reasonBytes.copyInto(payload, 1, 0, (reasonBytes.size).coerceAtMost(19))

        alertChar.value = payload

        for (device in subscribedDevices.toSet()) {
            try {
                gattServer?.notifyCharacteristicChanged(device, alertChar, false)
                Log.d(TAG, "Alert sent to ${device.address}: threat=$threatLevel")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify ${device.address}: ${e.message}")
                subscribedDevices.remove(device)
            }
        }
    }

    fun isActive(): Boolean = isRunning

    fun getSubscribedDeviceCount(): Int = subscribedDevices.size

    // ==================== GATT Callbacks ====================

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Device connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Device disconnected: ${device.address}")
                    subscribedDevices.remove(device)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                STATUS_CHAR_UUID -> {
                    val value = byteArrayOf(currentThreatLevel.toByte())
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                }
                ALERT_CHAR_UUID -> {
                    val reasonBytes = currentReason.toByteArray(Charsets.UTF_8)
                    val payload = ByteArray(1 + reasonBytes.size.coerceAtMost(19))
                    payload[0] = currentThreatLevel.toByte()
                    reasonBytes.copyInto(payload, 1, 0, reasonBytes.size.coerceAtMost(19))
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, payload)
                }
                else -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (value != null && value.size >= 2) {
                    if (value[0].toInt() == 1) {
                        subscribedDevices.add(device)
                        Log.i(TAG, "Device subscribed to alerts: ${device.address}")
                    } else {
                        subscribedDevices.remove(device)
                        Log.i(TAG, "Device unsubscribed from alerts: ${device.address}")
                    }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: errorCode=$errorCode")
            isRunning = false
        }
    }
}
