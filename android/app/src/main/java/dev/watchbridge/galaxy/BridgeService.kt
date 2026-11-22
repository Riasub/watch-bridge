package dev.watchbridge.galaxy

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import org.json.JSONObject

/**
 * 갤럭시가 BLE "주변기기(Peripheral)" 역할을 한다.
 * 워치 앱이 이 서비스에 접속해서 NOTIFY 를 구독하면, 카톡 알림을 프레임으로 쪼개 보낸다.
 *
 * 모든 상태 변경은 메인 스레드에서만 한다(BLE 콜백은 binder 스레드라서 main.post 로 넘긴다).
 */
@SuppressLint("MissingPermission") // 시작 전에 hasBlePermissions() 로 확인한다
class BridgeService : Service() {

    private class Subscriber(val device: BluetoothDevice) {
        var mtu = 23
        var enabled = false
        var sending = false
        val queue = ArrayDeque<ByteArray>()
    }

    private val main = Handler(Looper.getMainLooper())
    private var adapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private val subscribers = LinkedHashMap<String, Subscriber>()
    private var nextMsgId = 0
    private var heartbeatCount = 0
    private var advertising = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(btStateReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startInForeground()
        } catch (e: Exception) {
            EventLog.add("포그라운드 서비스를 시작하지 못했습니다: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasBlePermissions(this)) {
            EventLog.add("블루투스 권한이 없어 시작하지 못했습니다")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (gattServer == null) startGatt()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        connectedCount = 0
        instance = null
        main.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(btStateReceiver)
        } catch (_: IllegalArgumentException) {
        }
        stopGatt()
        EventLog.add("브리지 중지")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 시작/중지

    private fun startGatt() {
        val mgr = getSystemService(BluetoothManager::class.java)
        val ad = mgr?.adapter
        if (mgr == null || ad == null || !ad.isEnabled) {
            EventLog.add("블루투스가 꺼져 있습니다. 켜면 자동으로 이어서 시작합니다")
            return
        }
        adapter = ad
        val server = mgr.openGattServer(this, gattCallback)
        if (server == null) {
            EventLog.add("GATT 서버를 열지 못했습니다")
            return
        }
        gattServer = server

        // 연결(암호화된 본딩)이 된 기기만 읽고 구독할 수 있게 ENCRYPTED 권한을 건다.
        // 처음 구독할 때 갤럭시에 "페어링하시겠습니까?" 창이 뜬다.
        val notify = BluetoothGattCharacteristic(
            Protocol.NOTIFY,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )
        notify.addDescriptor(
            BluetoothGattDescriptor(
                Protocol.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                    BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
            )
        )
        val control = BluetoothGattCharacteristic(
            Protocol.CONTROL,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        )
        val service = BluetoothGattService(Protocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(notify)
        service.addCharacteristic(control)
        notifyChar = notify
        server.addService(service)
        scheduleHeartbeat()
    }

    private fun stopGatt() {
        stopAdvertising()
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        notifyChar = null
        subscribers.clear()
        connectedCount = 0
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    EventLog.add("블루투스 켜짐")
                    if (gattServer == null && hasBlePermissions(this@BridgeService)) startGatt()
                }
                BluetoothAdapter.STATE_TURNING_OFF -> {
                    EventLog.add("블루투스 꺼짐")
                    stopGatt()
                }
            }
        }
    }

    // ---------------------------------------------------------------- 광고(접속 대기)

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            EventLog.add("접속 대기 중(광고 시작)")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            EventLog.add("광고 시작 실패: 코드 $errorCode")
        }
    }

    private fun startAdvertising() {
        if (advertising || subscribers.isNotEmpty()) return
        val adv = adapter?.bluetoothLeAdvertiser
        if (adv == null) {
            EventLog.add("이 기기는 BLE 광고를 지원하지 않습니다")
            return
        }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(Protocol.SERVICE))
            .build()
        adv.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (advertising) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (_: Exception) {
            }
        }
        advertising = false
    }

    // ---------------------------------------------------------------- GATT 콜백

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    startAdvertising()
                } else {
                    EventLog.add("서비스 등록 실패: $status")
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                val addr = device.address
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    subscribers[addr] = Subscriber(device)
                    EventLog.add("기기 접속: …${addr.takeLast(5)}")
                    stopAdvertising()
                } else {
                    subscribers.remove(addr)
                    EventLog.add("기기 접속 끊김: …${addr.takeLast(5)} (status $status)")
                    stopAdvertising()
                    startAdvertising()
                }
                connectedCount = subscribers.values.count { it.enabled }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            main.post { subscribers[device.address]?.mtu = mtu }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
        ) {
            main.post {
                val on = subscribers[device.address]?.enabled == true
                val value = if (on) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            val isCccd = descriptor.uuid == Protocol.CCCD
            main.post {
                if (responseNeeded) {
                    val status = if (isCccd) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                    gattServer?.sendResponse(device, requestId, status, offset, value)
                }
                if (!isCccd) return@post
                val sub = subscribers[device.address] ?: return@post
                sub.enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                connectedCount = subscribers.values.count { it.enabled }
                EventLog.add(if (sub.enabled) "워치가 알림을 구독함" else "워치가 구독을 해제함")
                if (sub.enabled) {
                    send(sub, JSONObject().put("v", 1).put("t", "hello").put("app", "watch-bridge-galaxy"))
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            main.post {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
                EventLog.add("워치 -> 갤럭시: ${String(value, Charsets.UTF_8).take(60)}")
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                val sub = subscribers[device.address] ?: return@post
                sub.sending = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    EventLog.add("알림 전송 실패 status=$status")
                    sub.queue.clear()
                } else {
                    drain(sub)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 전송

    private fun broadcast(json: JSONObject) {
        for (sub in subscribers.values) {
            if (sub.enabled) send(sub, json)
        }
    }

    private fun send(sub: Subscriber, json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val id = nextMsgId++ and 0xFF
        val maxFrame = (sub.mtu - 3).coerceAtLeast(20)
        val frames = try {
            Protocol.frames(id, payload, maxFrame)
        } catch (e: IllegalArgumentException) {
            EventLog.add("메시지가 너무 커서 보내지 않았습니다")
            return
        }
        if (sub.queue.size + frames.size > MAX_QUEUE) {
            EventLog.add("전송 대기열이 가득 차서 오래된 항목을 버렸습니다")
            sub.queue.clear()
        }
        frames.forEach { sub.queue.addLast(it) }
        drain(sub)
    }

    private fun drain(sub: Subscriber) {
        if (sub.sending) return
        val frame = sub.queue.firstOrNull() ?: return
        if (notifyFrame(sub.device, frame)) {
            sub.queue.removeFirst()
            sub.sending = true
        } else {
            EventLog.add("알림 전송 시작 실패, 대기열을 비웠습니다")
            sub.queue.clear()
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyFrame(device: BluetoothDevice, bytes: ByteArray): Boolean {
        val server = gattServer ?: return false
        val ch = notifyChar ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            server.notifyCharacteristicChanged(device, ch, false, bytes) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.value = bytes
            server.notifyCharacteristicChanged(device, ch, false)
        }
    }

    /** 생존 시험용 하트비트. 워치 화면의 "마지막 수신 시각"으로 연결이 살아 있는지 본다. */
    private fun scheduleHeartbeat() {
        main.postDelayed({
            if (gattServer != null) {
                if (subscribers.values.any { it.enabled }) {
                    broadcast(
                        JSONObject().put("v", 1).put("t", "hb")
                            .put("n", ++heartbeatCount).put("ts", System.currentTimeMillis())
                    )
                }
                scheduleHeartbeat()
            }
        }, HEARTBEAT_MS)
    }

    // ---------------------------------------------------------------- 포그라운드 알림

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "워치 브리지", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("워치 브리지 실행 중")
            .setContentText("애플워치 연결 대기")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1
        private const val HEARTBEAT_MS = 15_000L
        private const val MAX_QUEUE = 200
        private const val MAX_PENDING = 20

        @Volatile
        var running = false
            private set

        /** 알림을 구독 중인 기기 수. */
        @Volatile
        var connectedCount = 0
            private set

        @Volatile
        private var instance: BridgeService? = null
        private val pending = ArrayList<JSONObject>()

        /** 어느 스레드에서든 호출 가능. 서비스가 아직 없으면 잠깐 보관했다가 보낸다. */
        fun post(json: JSONObject) {
            val s = instance
            if (s == null) {
                synchronized(pending) {
                    if (pending.size >= MAX_PENDING) pending.removeAt(0)
                    pending.add(json)
                }
                return
            }
            s.main.post {
                flushPending(s)
                s.broadcast(json)
            }
        }

        private fun flushPending(s: BridgeService) {
            val items = synchronized(pending) { pending.toList().also { pending.clear() } }
            items.forEach { s.broadcast(it) }
        }

        fun hasBlePermissions(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31) return true
            return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        }
    }
}
