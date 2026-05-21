package com.android.imeisettings.util

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import java.io.File

/**
 * System-level IMEI writing methods.
 * Requires: system app signature, android.uid.phone sharedUserId,
 * MODIFY_PHONE_STATE permission, no Google Play Services.
 *
 * These methods bypass root requirements by using privileged system APIs
 * directly available to platform-signed apps.
 */
object SystemImeiUtil {
    private const val TAG = "CONSUL_SYS_IMEI"

    data class SystemMethodResult(val success: Boolean, val method: String)

    /**
     * Try all system-level methods in priority order.
     */
    fun tryAllSystemMethods(context: Context, imei1: String, imei2: String): SystemMethodResult {
        val methods = listOf(
            { tryTelephonyHiddenApi(context, imei1, imei2) to "TelephonyManager Hidden API" },
            { tryIRadioModemAidl(imei1, imei2) to "IRadioModem AIDL Direct" },
            { tryIRadioModemHidl(imei1, imei2) to "IRadioModem HIDL Direct" },
            { tryMtkRadioExAidl(imei1, imei2) to "MTK RadioEx AIDL Direct" },
            { tryPhoneSubInfoService(context, imei1, imei2) to "PhoneSubInfo Service" },
            { tryRilConnectorDirect(imei1, imei2) to "RIL Direct Socket" },
            { tryModemAtDirect(imei1, imei2) to "Modem AT Direct (/dev/smd*)" },
            { tryNvramDirectNoRoot(imei1, imei2) to "NVRAM Direct (no root)" },
            { tryEfsDirectNoRoot(imei1, imei2) to "EFS Direct (no root)" },
            { tryPersistDirectNoRoot(imei1, imei2) to "Persist Direct (no root)" },
            { tryCarrierConfigOverride(context, imei1, imei2) to "Carrier Config Override" },
            { trySubscriptionOverride(context, imei1, imei2) to "Subscription Override" },
            { tryTelephonyRegistryInject(context, imei1, imei2) to "TelephonyRegistry Inject" },
            { trySystemPropertyWrite(imei1, imei2) to "System Property Write" }
        )

        for (methodFn in methods) {
            try {
                val (success, name) = methodFn()
                if (success) {
                    Log.i(TAG, "System method succeeded: $name")
                    return SystemMethodResult(true, name)
                }
            } catch (e: Exception) {
                Log.w(TAG, "System method failed: ${e.message}")
            }
        }
        return SystemMethodResult(false, "none")
    }

    // ==================== 1. TelephonyManager Hidden API ====================

    /**
     * Uses hidden TelephonyManager methods available to system apps with MODIFY_PHONE_STATE.
     * On AOSP: setDeviceId(), setImei(), setDeviceIdForPhone()
     */
    private fun tryTelephonyHiddenApi(context: Context, imei1: String, imei2: String): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val tmClass = tm.javaClass

        // Method 1: setImei(int slotIndex, String imei) — Android 14+
        try {
            val setImei = tmClass.getDeclaredMethod("setImei", Int::class.javaPrimitiveType, String::class.java)
            setImei.isAccessible = true
            setImei.invoke(tm, 0, imei1)
            if (imei2.isNotEmpty()) setImei.invoke(tm, 1, imei2)
            Log.i(TAG, "setImei() succeeded")
            return true
        } catch (e: Exception) {
            Log.d(TAG, "setImei not available: ${e.message}")
        }

        // Method 2: setDeviceId(int slotIndex, String deviceId)
        try {
            val setDeviceId = tmClass.getDeclaredMethod("setDeviceId", Int::class.javaPrimitiveType, String::class.java)
            setDeviceId.isAccessible = true
            setDeviceId.invoke(tm, 0, imei1)
            if (imei2.isNotEmpty()) setDeviceId.invoke(tm, 1, imei2)
            Log.i(TAG, "setDeviceId() succeeded")
            return true
        } catch (e: Exception) {
            Log.d(TAG, "setDeviceId not available: ${e.message}")
        }

        // Method 3: ITelephony.setDeviceIdForPhone(int phoneId, String deviceId)
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "phone") as? IBinder ?: return false

            val iTelephonyStub = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterface = iTelephonyStub.getMethod("asInterface", IBinder::class.java)
            val iTelephony = asInterface.invoke(null, binder) ?: return false

            val setMethod = iTelephony.javaClass.getDeclaredMethod(
                "setDeviceIdForPhone", Int::class.javaPrimitiveType, String::class.java
            )
            setMethod.isAccessible = true
            setMethod.invoke(iTelephony, 0, imei1)
            if (imei2.isNotEmpty()) setMethod.invoke(iTelephony, 1, imei2)
            Log.i(TAG, "ITelephony.setDeviceIdForPhone() succeeded")
            return true
        } catch (e: Exception) {
            Log.d(TAG, "ITelephony not available: ${e.message}")
        }

        // Method 4: ITelephonyRegistry.notifyDeviceIdentityChanged
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "telephony.registry") as? IBinder ?: return false

            val registryStub = Class.forName("com.android.internal.telephony.ITelephonyRegistry\$Stub")
            val asInterface = registryStub.getMethod("asInterface", IBinder::class.java)
            val registry = asInterface.invoke(null, binder) ?: return false

            for (m in registry.javaClass.declaredMethods) {
                if (m.name.contains("notifyDeviceIdentityChanged") || m.name.contains("notifyImeiChanged")) {
                    m.isAccessible = true
                    try {
                        when (m.parameterCount) {
                            2 -> m.invoke(registry, 0, imei1)
                            3 -> m.invoke(registry, 0, imei1, "")
                            else -> continue
                        }
                        Log.i(TAG, "TelephonyRegistry.${m.name}() succeeded")
                        return true
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "TelephonyRegistry not available: ${e.message}")
        }

        return false
    }

    // ==================== 2. IRadioModem AIDL (Android 13+) ====================

    /**
     * Direct binding to AIDL IRadioModem service.
     * System apps can bind without root.
     */
    private fun tryIRadioModemAidl(imei1: String, imei2: String): Boolean {
        val aidlServices = arrayOf(
            "android.hardware.radio.modem.IRadioModem/slot1",
            "android.hardware.radio.modem.IRadioModem/default",
            "android.hardware.radio.modem.IRadioModem/slot0"
        )

        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)

        for (serviceName in aidlServices) {
            try {
                val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                Log.d(TAG, "Found AIDL service: $serviceName")

                // Try to use OEM hook to send AT+EGMR
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(serviceName.substringBefore("/"))
                    // sendDeviceState or setRadioPower with OEM hook
                    val atCmd = "AT+EGMR=1,7,\"$imei1\"\r\n"
                    data.writeString(atCmd)

                    // TRANSACTION_sendOemRilRequestRaw
                    for (txCode in intArrayOf(200, 201, 202, 510, 511, 512)) {
                        try {
                            if (binder.transact(txCode, data, reply, 0)) {
                                val result = reply.readInt()
                                if (result == 0) {
                                    Log.i(TAG, "AIDL transaction $txCode succeeded for slot1")
                                    if (imei2.isNotEmpty()) {
                                        sendAidlImei(getService, smClass, imei2, 1)
                                    }
                                    return true
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) {
                Log.d(TAG, "AIDL $serviceName: ${e.message}")
            }
        }
        return false
    }

    private fun sendAidlImei(getService: java.lang.reflect.Method, smClass: Class<*>, imei: String, slotId: Int) {
        val slot2Services = arrayOf(
            "android.hardware.radio.modem.IRadioModem/slot2",
            "android.hardware.radio.modem.IRadioModem/slot${slotId}"
        )
        for (svc in slot2Services) {
            try {
                val binder = getService.invoke(null, svc) as? IBinder ?: continue
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(svc.substringBefore("/"))
                    data.writeString("AT+EGMR=1,10,\"$imei\"\r\n")
                    for (txCode in intArrayOf(200, 201, 510)) {
                        try {
                            binder.transact(txCode, data, reply, 0)
                        } catch (_: Exception) {}
                    }
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (_: Exception) {}
        }
    }

    // ==================== 3. IRadioModem HIDL (Android 8-12) ====================

    private fun tryIRadioModemHidl(imei1: String, imei2: String): Boolean {
        val hidlServices = arrayOf(
            "android.hardware.radio@1.6::IRadio/slot1",
            "android.hardware.radio@1.5::IRadio/slot1",
            "android.hardware.radio@1.4::IRadio/slot1",
            "android.hardware.radio@1.2::IRadio/slot1",
            "android.hardware.radio@1.0::IRadio/slot1"
        )

        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)

        for (serviceName in hidlServices) {
            try {
                val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                Log.d(TAG, "Found HIDL service: $serviceName")

                // sendOemRilRequestRaw via HIDL
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(serviceName.substringBefore("/"))
                    data.writeInt(1) // serial
                    val atBytes = "AT+EGMR=1,7,\"$imei1\"\r\n".toByteArray()
                    data.writeInt(atBytes.size)
                    data.writeByteArray(atBytes)

                    // HIDL transaction codes for OEM requests
                    for (txCode in intArrayOf(100, 101, 130, 131, 200)) {
                        try {
                            if (binder.transact(txCode, data, reply, 0)) {
                                Log.i(TAG, "HIDL transaction $txCode succeeded")
                                return true
                            }
                        } catch (_: Exception) {}
                    }
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) {
                Log.d(TAG, "HIDL $serviceName: ${e.message}")
            }
        }
        return false
    }

    // ==================== 4. MTK RadioEx AIDL ====================

    private fun tryMtkRadioExAidl(imei1: String, imei2: String): Boolean {
        val mtkServices = arrayOf(
            "vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem/slot1",
            "vendor.mediatek.hardware.mtkradioex@3.0::IMtkRadioEx/slot1",
            "vendor.mediatek.hardware.mtkradioex@2.0::IMtkRadioEx/slot1",
            "vendor.mediatek.hardware.radio@3.0::IRadio/slot1",
            "vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem/default"
        )

        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)

        for (serviceName in mtkServices) {
            try {
                val binder = getService.invoke(null, serviceName) as? IBinder ?: continue
                Log.d(TAG, "Found MTK service: $serviceName")

                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(serviceName.substringBefore("/"))
                    data.writeInt(1) // serial

                    // MTK specific: writeImei(serial, slotId, imei)
                    data.writeInt(0) // slotId
                    data.writeString(imei1)

                    // MTK RadioEx transaction codes for IMEI write
                    for (txCode in intArrayOf(50, 51, 52, 80, 81, 200, 201)) {
                        try {
                            if (binder.transact(txCode, data, reply, 0)) {
                                val status = reply.readInt()
                                if (status == 0) {
                                    Log.i(TAG, "MTK AIDL transaction $txCode succeeded")
                                    // Write IMEI2
                                    if (imei2.isNotEmpty()) {
                                        writeMtkSlot2(getService, imei2)
                                    }
                                    return true
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) {
                Log.d(TAG, "MTK AIDL $serviceName: ${e.message}")
            }
        }
        return false
    }

    private fun writeMtkSlot2(getService: java.lang.reflect.Method, imei2: String) {
        val slot2Names = arrayOf(
            "vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem/slot2",
            "vendor.mediatek.hardware.mtkradioex@3.0::IMtkRadioEx/slot2"
        )
        for (svc in slot2Names) {
            try {
                val binder = getService.invoke(null, svc) as? IBinder ?: continue
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(svc.substringBefore("/"))
                    data.writeInt(2)
                    data.writeInt(1)
                    data.writeString(imei2)
                    for (txCode in intArrayOf(50, 51, 80, 200)) {
                        try { binder.transact(txCode, data, reply, 0) } catch (_: Exception) {}
                    }
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (_: Exception) {}
        }
    }

    // ==================== 5. PhoneSubInfo Service ====================

    private fun tryPhoneSubInfoService(context: Context, imei1: String, imei2: String): Boolean {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "iphonesubinfo") as? IBinder ?: return false

            val stubClass = Class.forName("com.android.internal.telephony.IPhoneSubInfo\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val phoneSubInfo = asInterface.invoke(null, binder) ?: return false

            for (m in phoneSubInfo.javaClass.declaredMethods) {
                if (m.name.contains("setDeviceId") || m.name.contains("setImei") ||
                    m.name.contains("updateDeviceIdentity")) {
                    m.isAccessible = true
                    try {
                        when (m.parameterCount) {
                            1 -> m.invoke(phoneSubInfo, imei1)
                            2 -> {
                                m.invoke(phoneSubInfo, 0, imei1)
                                if (imei2.isNotEmpty()) m.invoke(phoneSubInfo, 1, imei2)
                            }
                            else -> continue
                        }
                        Log.i(TAG, "PhoneSubInfo.${m.name}() succeeded")
                        return true
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "PhoneSubInfo: ${e.message}")
        }
        return false
    }

    // ==================== 6. RIL Direct Socket ====================

    /**
     * Connect to RIL socket directly (system apps with android.uid.phone can access).
     * Sockets: /dev/socket/rild, /dev/socket/rild-oem, /dev/socket/rild2
     */
    private fun tryRilConnectorDirect(imei1: String, imei2: String): Boolean {
        val rilSockets = arrayOf(
            "/dev/socket/rild",
            "/dev/socket/rild-oem",
            "/dev/socket/qmux_radio/qmux_client_socket0",
            "/dev/socket/rild-debug"
        )

        for (socketPath in rilSockets) {
            try {
                val socketFile = File(socketPath)
                if (!socketFile.exists()) continue

                val localSocket = android.net.LocalSocket()
                val address = android.net.LocalSocketAddress(socketPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM)
                localSocket.connect(address)

                val out = localSocket.outputStream
                val inp = localSocket.inputStream

                // RIL_REQUEST_OEM_HOOK_RAW = 59
                val cmd = buildRilRequest(59, "AT+EGMR=1,7,\"$imei1\"\r\n")
                out.write(cmd)
                out.flush()

                val response = ByteArray(1024)
                val len = inp.read(response)
                localSocket.close()

                if (len > 0) {
                    val respStr = String(response, 0, len)
                    if (respStr.contains("OK") || !respStr.contains("ERROR")) {
                        Log.i(TAG, "RIL socket $socketPath succeeded")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "RIL socket $socketPath: ${e.message}")
            }
        }
        return false
    }

    private fun buildRilRequest(requestId: Int, data: String): ByteArray {
        val dataBytes = data.toByteArray()
        val header = ByteArray(8)
        // Length (4 bytes, big-endian)
        val totalLen = 4 + dataBytes.size
        header[0] = ((totalLen shr 24) and 0xFF).toByte()
        header[1] = ((totalLen shr 16) and 0xFF).toByte()
        header[2] = ((totalLen shr 8) and 0xFF).toByte()
        header[3] = (totalLen and 0xFF).toByte()
        // Request ID (4 bytes, little-endian)
        header[4] = (requestId and 0xFF).toByte()
        header[5] = ((requestId shr 8) and 0xFF).toByte()
        header[6] = ((requestId shr 16) and 0xFF).toByte()
        header[7] = ((requestId shr 24) and 0xFF).toByte()
        return header + dataBytes
    }

    // ==================== 7. Modem AT Direct ====================

    /**
     * Direct AT command via modem device nodes.
     * System app with phone UID can access these without root.
     */
    private fun tryModemAtDirect(imei1: String, imei2: String): Boolean {
        val modemDevices = arrayOf(
            "/dev/smd0", "/dev/smd7", "/dev/smd11",
            "/dev/ttyGS0", "/dev/ttyMSM0",
            "/dev/radio/atci0", "/dev/radio/atci1",
            "/dev/at_channel0", "/dev/at_channel1",
            "/dev/umts_router", "/dev/umts_atc0",
            "/dev/mux/dlci63", "/dev/gsmtty15",
            "/dev/ttyACM0", "/dev/ttyUSB0",
            "/dev/ccci_ioctl0", "/dev/ccci_ioctl1"
        )

        for (dev in modemDevices) {
            try {
                val devFile = File(dev)
                if (!devFile.exists() || !devFile.canRead()) continue

                val fos = java.io.FileOutputStream(devFile)
                val fis = java.io.FileInputStream(devFile)

                val cmd = "AT+EGMR=1,7,\"$imei1\"\r\n"
                fos.write(cmd.toByteArray())
                fos.flush()

                Thread.sleep(200)

                val buf = ByteArray(512)
                val available = fis.available()
                if (available > 0) {
                    val len = fis.read(buf)
                    val resp = String(buf, 0, len)
                    fos.close()
                    fis.close()
                    if (resp.contains("OK")) {
                        Log.i(TAG, "AT direct $dev succeeded")
                        if (imei2.isNotEmpty()) {
                            writeAtToDevice(dev, "AT+EGMR=1,10,\"$imei2\"\r\n")
                        }
                        return true
                    }
                }
                fos.close()
                fis.close()
            } catch (e: Exception) {
                Log.d(TAG, "AT $dev: ${e.message}")
            }
        }
        return false
    }

    private fun writeAtToDevice(dev: String, cmd: String) {
        try {
            val fos = java.io.FileOutputStream(File(dev))
            fos.write(cmd.toByteArray())
            fos.flush()
            fos.close()
        } catch (_: Exception) {}
    }

    // ==================== 8-10. Direct Partition Writes (no root for system app) ====================

    /**
     * As system app with phone UID, we can write to vendor partitions directly.
     */
    private fun tryNvramDirectNoRoot(imei1: String, imei2: String): Boolean {
        val nvramPaths = arrayOf(
            "/data/vendor/nvdata/APCFG/APRDCL/IMEI",
            "/data/nvram/APCFG/APRDCL/IMEI",
            "/vendor/nvdata/APCFG/APRDCL/IMEI",
            "/mnt/vendor/nvdata/APCFG/APRDCL/IMEI",
            "/data/vendor/nvdata/md/NVRAM/NVD_IMEI/MP0B_001",
            "/data/nvram/md/NVRAM/NVD_IMEI/MP0B_001"
        )

        for (path in nvramPaths) {
            try {
                val file = File(path)
                if (!file.exists()) continue
                if (!file.canWrite()) continue

                val encoded = encodeImeiForNvram(imei1, imei2)
                file.writeBytes(encoded)
                Log.i(TAG, "NVRAM direct write to $path succeeded")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "NVRAM $path: ${e.message}")
            }
        }
        return false
    }

    private fun tryEfsDirectNoRoot(imei1: String, imei2: String): Boolean {
        val efsPaths = arrayOf(
            "/efs/FactoryApp/serial_no",
            "/efs/FactoryApp/imei_svc.dat",
            "/efs/imei/mps_code.dat",
            "/efs/nv/item_files/modem/mmode/imei"
        )

        for (path in efsPaths) {
            try {
                val file = File(path)
                if (!file.exists()) continue
                if (!file.canWrite()) continue

                file.writeBytes(imei1.toByteArray())
                Log.i(TAG, "EFS direct write to $path succeeded")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "EFS $path: ${e.message}")
            }
        }
        return false
    }

    private fun tryPersistDirectNoRoot(imei1: String, imei2: String): Boolean {
        val persistPaths = arrayOf(
            "/persist/radio/imei",
            "/persist/radio/imei1",
            "/mnt/vendor/persist/radio/imei",
            "/data/vendor/radio/imei",
            "/persist/radio/modem_config/imei"
        )

        for (path in persistPaths) {
            try {
                val file = File(path)
                val dir = file.parentFile
                if (dir != null && !dir.exists()) continue
                if (file.exists() && !file.canWrite()) continue

                file.writeText(imei1)
                Log.i(TAG, "Persist direct write to $path succeeded")

                if (imei2.isNotEmpty()) {
                    val imei2Path = path.replace("imei1", "imei2").replace("imei", "imei2")
                    if (imei2Path != path) {
                        try { File(imei2Path).writeText(imei2) } catch (_: Exception) {}
                    }
                }
                return true
            } catch (e: Exception) {
                Log.d(TAG, "Persist $path: ${e.message}")
            }
        }
        return false
    }

    // ==================== 11. Carrier Config Override ====================

    /**
     * Override carrier config to inject custom IMEI.
     * System apps can modify CarrierConfigManager directly.
     */
    private fun tryCarrierConfigOverride(context: Context, imei1: String, imei2: String): Boolean {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "carrier_config") as? IBinder ?: return false

            val stubClass = Class.forName("com.android.internal.telephony.ICarrierConfigLoader\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val configLoader = asInterface.invoke(null, binder) ?: return false

            for (m in configLoader.javaClass.declaredMethods) {
                if (m.name.contains("overrideConfig") || m.name.contains("updateConfig")) {
                    m.isAccessible = true
                    try {
                        val bundle = android.os.PersistableBundle()
                        bundle.putString("device_imei_override", imei1)
                        m.invoke(configLoader, 0, bundle)
                        Log.i(TAG, "CarrierConfig.${m.name}() succeeded")
                        return true
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "CarrierConfig: ${e.message}")
        }
        return false
    }

    // ==================== 12. Subscription Override ====================

    private fun trySubscriptionOverride(context: Context, imei1: String, imei2: String): Boolean {
        try {
            val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subMgrClass = subMgr.javaClass

            // setImei(int subId, String imei)
            for (m in subMgrClass.declaredMethods) {
                if (m.name == "setImei" || m.name == "setDeviceImei") {
                    m.isAccessible = true
                    try {
                        val subIds = SubscriptionManager.from(context).activeSubscriptionInfoList
                        if (subIds != null && subIds.isNotEmpty()) {
                            m.invoke(subMgr, subIds[0].subscriptionId, imei1)
                            if (imei2.isNotEmpty() && subIds.size > 1) {
                                m.invoke(subMgr, subIds[1].subscriptionId, imei2)
                            }
                            Log.i(TAG, "SubscriptionManager.${m.name}() succeeded")
                            return true
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "SubscriptionOverride: ${e.message}")
        }
        return false
    }

    // ==================== 13. TelephonyRegistry Inject ====================

    private fun tryTelephonyRegistryInject(context: Context, imei1: String, imei2: String): Boolean {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "telephony.registry") as? IBinder ?: return false

            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.android.internal.telephony.ITelephonyRegistry")
                data.writeInt(0) // phoneId
                data.writeString(imei1) // imei
                data.writeString("") // imeisv

                // Scan all transaction codes for device identity notification
                for (txCode in 30..50) {
                    try {
                        binder.transact(txCode, data, reply, 0)
                        val err = reply.readException()
                    } catch (_: Exception) {}
                }
            } finally {
                data.recycle()
                reply.recycle()
            }

            Log.i(TAG, "TelephonyRegistry inject attempted")
            return false // Can't verify success
        } catch (e: Exception) {
            Log.d(TAG, "TelephonyRegistry inject: ${e.message}")
        }
        return false
    }

    // ==================== 14. System Property Write ====================

    /**
     * System apps can set properties that persist across reboots.
     */
    private fun trySystemPropertyWrite(imei1: String, imei2: String): Boolean {
        val properties = arrayOf(
            "persist.radio.imei" to imei1,
            "persist.radio.imei1" to imei1,
            "persist.radio.device.imei0" to imei1,
            "gsm.imei0" to imei1,
            "ril.imei0" to imei1,
            "persist.sys.imei0" to imei1,
            "vendor.ril.imei0" to imei1,
            "ro.ril.oem.imei" to imei1
        )

        val properties2 = if (imei2.isNotEmpty()) arrayOf(
            "persist.radio.imei2" to imei2,
            "persist.radio.device.imei1" to imei2,
            "gsm.imei1" to imei2,
            "ril.imei1" to imei2,
            "persist.sys.imei1" to imei2,
            "vendor.ril.imei1" to imei2
        ) else emptyArray()

        var anySet = false

        try {
            val sysPropClass = Class.forName("android.os.SystemProperties")
            val setMethod = sysPropClass.getMethod("set", String::class.java, String::class.java)

            for ((key, value) in properties + properties2) {
                try {
                    setMethod.invoke(null, key, value)
                    anySet = true
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.d(TAG, "SystemProperties: ${e.message}")
        }

        if (anySet) Log.i(TAG, "System properties written")
        return anySet
    }

    // ==================== Helpers ====================

    private fun encodeImeiForNvram(imei1: String, imei2: String): ByteArray {
        val result = ByteArray(16)
        // NVRAM IMEI format: BCD encoded, 8 bytes per IMEI
        // First nibble = 0x0A (length marker), then BCD pairs
        val digits1 = imei1.map { it.digitToInt() }
        result[0] = ((digits1.size and 0x0F) or 0xA0).toByte()
        for (i in 0 until minOf(digits1.size, 15) step 2) {
            val high = if (i + 1 < digits1.size) digits1[i + 1] else 0x0F
            val low = digits1[i]
            result[1 + i / 2] = ((high shl 4) or low).toByte()
        }
        return result
    }
}
