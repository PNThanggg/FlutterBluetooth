package com.example.flutter_bluetooth

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.*

open class MainActivity : FlutterActivity() {
    private val METHOD_CHANNEL_NAME = "com.example.flutter_bluetooth/bluetooth"
    private val EVENT_DEVICE_RESULT_STREAM = "com.example.flutter_bluetooth/deviceResultStream"
    private val EVENT_STATUS_RESULT_STREAM = "com.example.flutter_bluetooth/anyStatusResultStream"

    private lateinit var channel: MethodChannel
    private var deviceResultStreamEventSink: EventChannel.EventSink? = null
    private var eventAnyStatusSink: EventChannel.EventSink? = null

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothDevice: BluetoothDevice? = null
    private var socket: BluetoothSocket? = null

    private var mainMacAddress: String? = null

    private var mainUuid: UUID? = null
    private val baseUUID: String = "00001101-0000-1000-8000-00805F9B34FB"

    // for all of status broadcast receiver
    private var filterAnyStatusFilter: IntentFilter? = null
    private var uuidList: JSONArray = JSONArray()

    // auto connect for write job [true] or [false]
    private var mainStreamAutoConnect: Boolean = false

    private val handler: Handler? = null
    private var filter: IntentFilter? = null

    @Serializable
    data class UUIDSerializable(
        val name: String?, val short_description: String?, val uuid: String?
    )

    @Serializable
    data class DeviceSerializable(
        val name: String?,
        val aliasName: String?,
        val address: String?,
        val type: String?,
        val isPaired: String?,
        val uuids: String?,
    )

    @Serializable
    data class PairingStatusSerializable(
        val STATUS_PAIRING: String?,
    )

    @Serializable
    data class ConnectingStatusSerializable(
        val STATUS_CONNECTING: String?,
        val MAC_ADDRESS: String?,
    )

    @Serializable
    data class DiscoveryStatusSerializable(
        val STATUS_DISCOVERY: String?,
    )

    companion object {
        // get string from function getConnectingStatus
        private const val STATE_DISCONNECTED: Int = 0
        private const val STATE_CONNECTED: Int = 2

        // location and bluetooth permissions system numbers
        private const val BLUETOOTH_ENABLE_PERMISSION_NUMBER: Int = 17
        private const val BLUETOOTH_DISABLE_PERMISSION_NUMBER: Int = 18
        private const val LOCATION_PERMISSION_NUMBER: Int = 19
        private const val LOCATION_ENABLE_PERMISSION_NUMBER: Int = 20
        private const val PAIRING_CHANGE_NUMBER: Int = 21

        // read && write system send target (OS default)
        private const val MESSAGE_WRITE: Int = 1
        private const val MESSAGE_TOAST: Int = 2
    }

    // devices' connecting status
    fun getConnectingStatus(connectId: Int): String {
        return when (connectId) {
            0 -> "STATE_DISCONNECTED"
            1 -> "STATE_CONNECTING"
            2 -> "STATE_CONNECTED"
            3 -> "STATE_DISCONNECTING"

            else -> "ERROR"
        }
    }

    // devices' pairing status
    private fun getIsPaired(isPaired: Int): String {
        return when (isPaired) {
            10 -> "PAIRED_NONE"
            11 -> "PAIRING"
            12 -> "PAIRED"

            else -> "UNKNOWN_PAIRED"
        }
    }

    // found device's type
    private fun getType(type: Int): String{
        return when(type) {
            0 -> "DEVICE_TYPE_UNKNOWN"
            1 -> "DEVICE_TYPE_CLASSIC"
            2 -> "DEVICE_TYPE_LE"
            3 -> "DEVICE_TYPE_DUAL"
            -214748368 -> "ERROR"

            else -> "UNKNOWN_TYPE"
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL_NAME)
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "IS_BLUETOOTH_AVAILABLE" -> isBluetoothAvailable(result)
                "SET_BLUETOOTH_ENABLE" -> bluetoothSetEnable()
                "SET_BLUETOOTH_DISABLE" -> bluetoothSetDisable()

                "PAIR_TO_DEVICE" -> startPairing(call.argument("macAddress"))
                "CONNECT_TO_DEVICE" -> createConnectionToDevice(call.argument("macAddress"), call.argument("UUIDString"))
                "GET_PAIRED_DEVICES" -> getJustPairedDevices(result)

                "START_DISCOVERY" -> startDiscovery(result)
                "STOP_DISCOVERY" -> stopDiscovery(result)

                "CLOSE_TO_DEVICE" -> closeConnectionFromDevice(call.argument("macAddress"))
                "WRITE_TO_DEVICE" -> writeToDevice(call.argument("data"), call.argument("autoConnect"))

                "IS_ON_LOCATION" -> isOnLocation(result)
                "GO_LOCATION_FOR_ENABLE" -> goLocationForEnable(result)
                "APPLY_PERMISSION_LOCATION" -> applyPermissionLocation(result)

                else -> result.notImplemented()
            }
        }

        // [deviceResultStream] is [EventChannel], found devices return to flutter
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_DEVICE_RESULT_STREAM).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, deviceResultStreamEventSinkInLine: EventChannel.EventSink?) {
                deviceResultStreamEventSink = deviceResultStreamEventSinkInLine
                Log.d("deviceResultStream", "called onListen")
            }

            override fun onCancel(arguments: Any?) {
                deviceResultStreamEventSink = null
                Log.d("deviceResultStream", "called onCancel")
            }
        })

        // [anyStatusResultStream] is [EventChannel], connecting and pairing return to flutter
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_STATUS_RESULT_STREAM).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, eventAnyStatusSinkInLine: EventChannel.EventSink?) {
                eventAnyStatusSink = eventAnyStatusSinkInLine
                Log.d("anyStatusResultStream", "called onListen")
            }

            override fun onCancel(arguments: Any?) {
                eventAnyStatusSink = null
                Log.d("anyStatusResultStream", "called onCancel")
            }
        })

        getUUIDListFromFile()

        //some intents for register
        filterAnyStatusFilter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        context.registerReceiver(receiverAnyStatusResult, filterAnyStatusFilter)

        filterAnyStatusFilter = IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        context.registerReceiver(receiverAnyStatusResult, filterAnyStatusFilter)

        filterAnyStatusFilter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiver(receiverAnyStatusResult, filterAnyStatusFilter)

        filterAnyStatusFilter = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        context.registerReceiver(receiverAnyStatusResult, filterAnyStatusFilter)

        filterAnyStatusFilter = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        context.registerReceiver(receiverAnyStatusResult, filterAnyStatusFilter)

        filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        context.registerReceiver(receiverDeviceResults, filter)
    }

    /*

    this [receiverAnyStatusResult]
    for this is a broadcast receiver
    its job is listen connecting status and pairing status
    then return them to flutter

  */
    private val receiverAnyStatusResult = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            when (action!!) {
                // STATUS_PAIRING
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val pairedStatus: Int = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, PAIRING_CHANGE_NUMBER)
                    eventAnyStatusSink!!.success(Json.encodeToString(PairingStatusSerializable(getIsPaired(pairedStatus))))
                }

                // STATUS_CONNECTING
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                    // [STATE_CONNECTED] named parameter takes reference 2
                    eventAnyStatusSink!!.success(Json.encodeToString(ConnectingStatusSerializable(getConnectingStatus(STATE_CONNECTED), device?.toString())))
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                    //[STATE_DISCONNECTED] named parameter takes reference 0
                    eventAnyStatusSink!!.success(Json.encodeToString(ConnectingStatusSerializable(getConnectingStatus(STATE_DISCONNECTED), device?.toString())))
                    // if give [true] parameter from flutter to api on the write job reconnect when write job is done
                    if (mainStreamAutoConnect) createConnectionToDevice(mainMacAddress, mainUuid.toString())
                }

                // DISCOVERY_STATUS
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    eventAnyStatusSink!!.success(Json.encodeToString(DiscoveryStatusSerializable("STARTED")))
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    eventAnyStatusSink!!.success(Json.encodeToString(DiscoveryStatusSerializable("FINISHED")))
                }
            }
        }
    }


    // this [receiverDeviceResults] for this is a broadcast receiver
    // its job is find a device and return to flutter gives parameters to function [getDeviceInfo] its found device
    private val receiverDeviceResults = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action

            when (action!!) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    getDeviceInfo(device!!)
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    @SuppressLint("MissingPermission", "NewApi")
    fun getDeviceInfo(device: BluetoothDevice?) {
        val uuids: MutableList<UUIDSerializable> = mutableListOf()

        try {
            // only get paired device's uuids
            if (device!!.uuids != null) {
                device.uuids.forEach { uuid: ParcelUuid? ->
                    val fromUuidList: JSONObject? = findFromUUIDList(word = uuid?.uuid?.toString())

                    uuids.add(
                        UUIDSerializable(
                            if (fromUuidList != null) fromUuidList.getString("name") else "UNKNOWN",
                            if (fromUuidList != null) fromUuidList.getString("short_description") else "UNKNOWN",
                            if (fromUuidList != null) fromUuidList.getString("uuid") else uuid?.uuid?.toString()
                        )
                    )
                }
            }

            deviceResultStreamEventSink!!.success(
                Json.encodeToString(
                    DeviceSerializable(
                        device.name?.toString(),
                        device.alias,
                        device.address?.toString(),
                        getType(device.type),
                        getIsPaired(device.bondState),
                        Json.encodeToString(uuids)
                    )
                )
            )
        } catch (e: Throwable) {
            Log.d("SomeException", e.toString())
        }
    }


    // this [getUUIDListFromFile] is get a json file in assets directory when trigger this plugin
    private fun getUUIDListFromFile() {
        try {
            val uuidListString = context.assets.open("uuids_list.json").bufferedReader().use { it.readText() }
            uuidList = JSONArray(uuidListString)
        } catch (ioException: IOException) {
            ioException.printStackTrace()
        }
    }

    // this [findFromUUIDList] is find uuid from return file from function [getUUIDListFromFile]
    // takes [word] named parameter, the parameter is [UUID] and type is [String]
    private fun findFromUUIDList(word: String?): JSONObject? {
        var returnObject: JSONObject? = null

        var countNumber = 0
        while (countNumber < uuidList.length()) {
            if (uuidList.getJSONObject(countNumber).getString("uuid").lowercase() == word?.lowercase()) {
                returnObject = uuidList.getJSONObject(countNumber)
                break
            }
            countNumber++
        }

        return returnObject
    }

    // this [isBluetoothAvailable] check device's bluetooth available status
    // if the device doesn't support bluetooth return [null] parameter
    // if bluetooth already active return [true] parameter or not return [false] parameter
    // takes [result] named parameter, type is [Result]
    private fun isBluetoothAvailable(result: MethodChannel.Result) {
        // if bluetooth is null means the device doesn't support bluetooth
        if (bluetoothAdapter == null) {
            Log.d("isBluetoothAvailable", "Bluetooth is NULL")
            result.success(null)
        }

        Log.d("isBluetoothAvailable", "Bluetooth is " + bluetoothAdapter!!.isEnabled)

        // if bluetooth is true means the device's bluetooth is active
        // if not means the device's bluetooth isn't active
        result.success(bluetoothAdapter.isEnabled)
    }

    // this bluetoothSetEnable for device's bluetooth turn on
    @TargetApi(Build.VERSION_CODES.S)
    private fun bluetoothSetEnable() {
        val checkBluetoothState: Int = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH_CONNECT
        )

        if (!bluetoothAdapter!!.isEnabled) {
            // android 12 and higher
            if (Build.VERSION.SDK_INT >= 31) {
                if (checkBluetoothState != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        activity, arrayOf(
                            android.Manifest.permission.BLUETOOTH_SCAN,
                            android.Manifest.permission.BLUETOOTH_CONNECT,
                        ), BLUETOOTH_ENABLE_PERMISSION_NUMBER
                    )

                    return
                }
            }

            bluetoothAdapter.enable()
            Log.d("bluetoothSetEnable", "Enable")
        }
    }

    // this bluetoothSetDisable for device's bluetooth turn off
    @TargetApi(Build.VERSION_CODES.S)
    private fun bluetoothSetDisable() {
        val checkBluetoothState: Int = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH_CONNECT
        )

        if (bluetoothAdapter!!.isEnabled) {
            // android 12 and higher
            if (Build.VERSION.SDK_INT >= 31) {

                if (checkBluetoothState != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        activity, arrayOf(
                            android.Manifest.permission.BLUETOOTH_SCAN,
                            android.Manifest.permission.BLUETOOTH_CONNECT,
                        ), BLUETOOTH_DISABLE_PERMISSION_NUMBER
                    )

                    return
                }
            }

            bluetoothAdapter.disable()
            Log.d("bluetoothSetDisable", "Disable")
        }
    }

    // this [startPairing] as override Synchronized starts pair to any device
    // if already paired [void] or wants accept a number between each devices (as OS default)
    // takes [macAddress] named parameter, type is [String]
    @TargetApi(Build.VERSION_CODES.KITKAT)
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startPairing(macAddress: String?) {
        mainMacAddress = macAddress

        val bluetoothDeviceForPair: BluetoothDevice = bluetoothAdapter!!.getRemoteDevice(mainMacAddress!!)

        val isPaired = getIsPaired(bluetoothDeviceForPair.bondState)

        if (isPaired != "PAIRED") {
            bluetoothDeviceForPair.createBond()
            bluetoothDevice = bluetoothDeviceForPair
        }
    }

    // this [startDiscovery] belongs to [receiverDeviceResults] starts discovery job
    //  if start returns [true] or not returns [false] parameter
    //  takes [result] named parameter, type is [Result]
    @SuppressLint("MissingPermission")
    private fun startDiscovery(result: MethodChannel.Result) {
        val valueBS = bluetoothAdapter?.startDiscovery()
        Log.d("startDiscovery", "discovery started")
        result.success(valueBS)
    }

    // this [stopDiscovery] belongs to [receiverDeviceResults] stops discovery job
    // if stop returns [true] or not returns [false] parameter takes [result] named parameter, type is [Result]
    @SuppressLint("MissingPermission")
    private fun stopDiscovery(result: MethodChannel.Result) {
        val valueBC = bluetoothAdapter?.cancelDiscovery()
        Log.d("stopDiscovery", "discovery stopped")
        result.success(valueBC)
    }

    // this [isOnLocation] for device's location check
    // if the location is on, return parameter [true], type is [Boolean]
    // if the location is off, return parameter [false], type is [Boolean]
    // takes [result] named parameter, type is [Result]
    private fun isOnLocation(result: MethodChannel.Result) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val isOnLocation = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.d("isEnableOrDisable", "Enable")
            true
        } else {
            Log.d("isEnableOrDisable", "Disable")
            false
        }

        result.success(isOnLocation)
    }

    // this [goLocationForEnable] for device's location permission check
    // if the location is off, turn on as manuel for goes to location setting and return parameter [true], type is [Boolean]
    // if the location is on, return parameter [true], type is [Boolean] takes [result] named parameter, type is [Result]
    private fun goLocationForEnable(result: MethodChannel.Result) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val goLocationForEnable = if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            activity.startActivityForResult(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), LOCATION_ENABLE_PERMISSION_NUMBER
            )
            true
        } else {
            Log.d("goLocationForEnable", "Already still enabled")
            true
        }

        result.success(goLocationForEnable)
    }

    // this [applyPermissionLocation] for ask device's location permission for use
    // takes [result] named parameter, type is [Result]
    @TargetApi(Build.VERSION_CODES.Q)
    private fun applyPermissionLocation(result: MethodChannel.Result) {
        var locationPermission = false
        val checkLocation: Int = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (checkLocation != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ), LOCATION_PERMISSION_NUMBER
            )
        } else {
            locationPermission = true
            Log.d("applyPermissionLocation", "Location permission granted")
        }

        result.success(locationPermission)
    }

    // this [getJustPairedDevices] for only return device's paired devices as string for decode as json
    // takes [result] named parameter, type is [Result]
    @TargetApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission")
    private fun getJustPairedDevices(result: MethodChannel.Result) {
        val resultForPairedDevices: MutableList<String> = mutableListOf()
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        val uuids: MutableList<UUIDSerializable> = mutableListOf()

        if (!pairedDevices.isNullOrEmpty()) {
            pairedDevices.forEach { device ->
                device.uuids.forEach { uuid: ParcelUuid? ->
                    val fromUuidList: JSONObject? = findFromUUIDList(word = uuid?.uuid?.toString())

                    uuids.add(
                        UUIDSerializable(
                            if (fromUuidList != null) fromUuidList.getString("name") else "UNKNOWN", if (fromUuidList != null) fromUuidList.getString("short_description") else "UNKNOWN", if (fromUuidList != null) fromUuidList.getString("uuid") else uuid?.uuid?.toString()
                        )
                    )
                }

                resultForPairedDevices.add(
                    Json.encodeToString(
                        DeviceSerializable(
                            device.name?.toString(),
                            device.alias,
                            device.address?.toString(),
                            getType(device.type),
                            getIsPaired(device.bondState),
                            Json.encodeToString(uuids)
                        )
                    )
                )
            }
        }

        result.success(resultForPairedDevices)
    }

    // this [ConnectThread] for this is a [Thread]
    // this has two functions one of them is [run], use for connect a device,
    // another of them is [cancel], use for disconnect already connect device
    // takes two parameters, one of them is [device], type is [BluetoothDevice]
    // another of them is [UUIDString], type is [String]
    protected inner class ConnectThread(device: BluetoothDevice? = null, UUIDString: UUID? = null) : Thread() {
        private var mmDevice: BluetoothDevice? = device
        private var uuidForConnect: UUID = UUIDString ?: UUID.fromString(baseUUID)

        @SuppressLint("MissingPermission")
        override fun start() {
            mainUuid = uuidForConnect

            socket = mmDevice!!.createRfcommSocketToServiceRecord(mainUuid)
            bluetoothAdapter?.cancelDiscovery()

            try {
                socket?.connect()
                Log.d("STATUS_CONNECT", true.toString())
            } catch (e: Throwable) {
                Log.d("STATUS_CONNECT", e.toString())
            }
        }

        fun cancel() {
            try {
                socket?.close()
                Log.d("STATUS_DISCONNECT", true.toString())
            } catch (e: Throwable) {
                Log.d("STATUS_DISCONNECT", e.toString())
            }
        }
    }

    // this [createConnectionToDevice] as override Synchronized belongs to function [ConnectThread]
    // for goes to [ConnectThread] for connect to device and gives two parameters to [ConnectThread]
    // one of the parameters is [bluetoothDevice], type is [BluetoothDevice]
    // another of the parameters is [mainUuid], type is [UUID]
    // takes [macAddress] named parameter, type is [String]
    // takes [UUIDString] named parameter, type is [String]
    @Synchronized
    fun createConnectionToDevice(macAddress: String?, UUIDString: String? = null) {
        mainUuid = UUID.fromString(UUIDString ?: baseUUID)
        mainMacAddress = macAddress

        if (mainMacAddress != null) bluetoothDevice = bluetoothAdapter?.getRemoteDevice(mainMacAddress!!)

        Log.d("uuid", mainUuid.toString())

        ConnectThread(bluetoothDevice, mainUuid).start()
    }

    // this [closeConnectionFromDevice] belongs to function [ConnectThread]
    // for goes to [ConnectThread] for disconnect connected device and gives one parameter to [ConnectThread]
    // the parameter is [bluetoothDevice], type is [BluetoothDevice]
    // takes [macAddress] named parameter, type is [String]
    private fun closeConnectionFromDevice(macAddress: String? = null) {
        mainMacAddress = macAddress

        if (mainMacAddress != null)
            bluetoothDevice = bluetoothAdapter?.getRemoteDevice(mainMacAddress!!)

        ConnectThread(bluetoothDevice).cancel()
    }


    // this [writeToDevice] as override Synchronized belongs to function [StreamThread]
    // for goes to [StreamThread] for write to remote device and gives two parameters to [StreamThread]
    // one of parameters is [data], [data] is any text for now, type is [String]
    // another of parameters is [autoConnect], [autoConnect] is reconnect to device because
    // status will be disconnect when write is done (OS default), type is [Boolean]
    // takes [data] named parameter, type is [String], actually is text
    // takes [autoConnect] named parameter, type is [Boolean]
    @Synchronized
    fun writeToDevice(data: String? = null, autoConnect: Boolean? = null) {
        StreamThread(data, autoConnect).write()
    }

    // this [StreamThread] for this is a [Thread]
    // this has three functions but [read] and [reset] in list
    // one parameter is [write] named, write to remote device means send data to remote device
    // takes two parameters, one of the parameters is [data], type is [String]
    // another of the parameters is [autoConnect], type is [Boolean]
    protected inner class StreamThread(data: String? = null, autoConnect: Boolean? = null) : Thread() {
        private val outStream: OutputStream? = socket?.outputStream
        private val buffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream

        private var streamData: String? = data ?: ""
        private var streamAutoConnect: Boolean = autoConnect ?: false

        fun write() {
            try {
                outStream!!.write(streamData!!.toByteArray())

                // if write method is done system auto disconnect the connect
                // if variable [mainStreamAutoConnect] set as [true] connection auto reconnect
                // if variable [mainStreamAutoConnect] set as [false] write is done, will be disconnect
                mainStreamAutoConnect = streamAutoConnect
            } catch (e: IOException) {
                Log.e("StreamThread", "Error occurred when sending data", e)

                // Send a failure message back to the activity.
                val writeErrorMsg = handler?.obtainMessage(MESSAGE_TOAST)
                val bundle = Bundle().apply {
                    putString("toast", "Couldn't send data to the other device")
                }
                writeErrorMsg?.data = bundle
                handler?.sendMessage(writeErrorMsg!!)
                return
            }

            // Share the sent message with the UI activity.
            val writtenMsg = handler?.obtainMessage(
                MESSAGE_WRITE, -1, -1, buffer
            )

            writtenMsg?.sendToTarget()
        }
    }
}
