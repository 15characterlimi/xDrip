/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.eveningoutpost.dexdrip.Services;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;

//KS import com.eveningoutpost.dexdrip.GcmActivity;
import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.UserError.Log;

import com.eveningoutpost.dexdrip.Models.ActiveBluetoothDevice;
import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Models.TransmitterData;
import com.eveningoutpost.dexdrip.Models.Sensor;
import com.eveningoutpost.dexdrip.UtilityModels.CollectionServiceStarter;
//KS import com.eveningoutpost.dexdrip.UtilityModels.ForegroundServiceStarter;
import com.eveningoutpost.dexdrip.UtilityModels.HM10Attributes;
//KS import com.eveningoutpost.dexdrip.utils.BgToSpeech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

@TargetApi(Build.VERSION_CODES.KITKAT)
public class DexCollectionService extends Service {
    private final static String TAG = "wear" + DexCollectionService.class.getSimpleName();
    private SharedPreferences prefs;
    //KS private BgToSpeech bgToSpeech;
    public DexCollectionService dexCollectionService;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt;
    //KS private ForegroundServiceStarter foregroundServiceStarter;
    private int mConnectionState = BluetoothProfile.STATE_DISCONNECTING;
    private BluetoothDevice device;
    private BluetoothGattCharacteristic mCharacteristic;
    long lastPacketTime;
    private byte[] lastdata = null;
    private Context mContext;
    private static final int STATE_DISCONNECTED = BluetoothProfile.STATE_DISCONNECTED;
    private static final int STATE_DISCONNECTING = BluetoothProfile.STATE_DISCONNECTING;
    private static final int STATE_CONNECTING = BluetoothProfile.STATE_CONNECTING;
    private static final int STATE_CONNECTED = BluetoothProfile.STATE_CONNECTED;

    public static double last_time_seen = 0;
    private static int watchdog_count = 0;

    // Experimental support for "Transmiter PL" from Marek Macner @FPV-UAV
    private final boolean use_transmiter_pl_bluetooth = Home.getPreferencesBooleanDefaultFalse("use_transmiter_pl_bluetooth");
    private final UUID xDripDataService = use_transmiter_pl_bluetooth ? UUID.fromString(HM10Attributes.TRANSMITER_PL_SERVICE) : UUID.fromString(HM10Attributes.HM_10_SERVICE);
    private final UUID xDripDataCharacteristic = use_transmiter_pl_bluetooth ? UUID.fromString(HM10Attributes.TRANSMITER_PL_RX_TX) : UUID.fromString(HM10Attributes.HM_RX_TX);

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onCreate() {
        //KS foregroundServiceStarter = new ForegroundServiceStarter(getApplicationContext(), this);
        //KS foregroundServiceStarter.start();
        Log.d(TAG, "onCreate: ENTER");
        mContext = getApplicationContext();
        dexCollectionService = this;
        prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        listenForChangeInSettings();
        //KS bgToSpeech = BgToSpeech.setupTTS(mContext); //keep reference to not being garbage collected
        if(CollectionServiceStarter.isDexBridgeOrWifiandDexBridge()){
            Log.i(TAG,"onCreate: resetting bridge_battery preference to 0");
            prefs.edit().putInt("bridge_battery",0).apply();
            //if (Home.get_master()) GcmActivity.sendBridgeBattery(prefs.getInt("bridge_battery",-1));
        }
        Log.i(TAG, "onCreate: STARTING SERVICE");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final PowerManager.WakeLock wl = JoH.getWakeLock("dexcollect-service", 120000);
        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2){
            stopSelf();
            JoH.releaseWakeLock(wl);
            return START_NOT_STICKY;
        }
        Context context = getApplicationContext();
        if (CollectionServiceStarter.isBTWixel(context)
                || CollectionServiceStarter.isDexBridgeOrWifiandDexBridge()
                || CollectionServiceStarter.isWifiandBTWixel(context)
                || CollectionServiceStarter.isFollower(context)) {
            setFailoverTimer();
        } else {
            stopSelf();
            JoH.releaseWakeLock(wl);
            return START_NOT_STICKY;
        }
        lastdata = null;
        attemptConnection();
        watchdog();
        JoH.releaseWakeLock(wl);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy entered");
        close();
        //KS foregroundServiceStarter.stop();
        setRetryTimer();
        //KS BgToSpeech.tearDownTTS();
        Log.i(TAG, "SERVICE STOPPED");
    }

    public SharedPreferences.OnSharedPreferenceChangeListener prefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (key.compareTo("run_service_in_foreground") == 0) {
                Log.d("FOREGROUND", "run_service_in_foreground changed!");
                if (prefs.getBoolean("run_service_in_foreground", false)) {
                    //KS foregroundServiceStarter = new ForegroundServiceStarter(getApplicationContext(), dexCollectionService);
                    //KS foregroundServiceStarter.start();
                    Log.d(TAG, "Moving to foreground");
                } else {
                    //KS dexCollectionService.stopForeground(true);
                    Log.d(TAG, "Removing from foreground");
                }
            }
            if(key.equals("dex_collection_method") || key.equals("dex_txid")){
                //if the input method or ID changed, accept any new package once even if they seem duplicates
                Log.d(TAG, "collection method or txID changed - setting lastdata to null");
                lastdata = null;
            }
        }
    };

    public void listenForChangeInSettings() {
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
    }

    public void setRetryTimer() {
        if (CollectionServiceStarter.isBTWixel(getApplicationContext())
                || CollectionServiceStarter.isDexBridgeOrWifiandDexBridge()
                || CollectionServiceStarter.isWifiandBTWixel(getApplicationContext())) {
            long retry_in;
            if(CollectionServiceStarter.isDexBridgeOrWifiandDexBridge()) {
                retry_in = (1000 * 25);
            }else {
                //retry_in = (1000*65);
                retry_in = (1000 * 25); // same for both for testing
            }
            Log.d(TAG, "setRetryTimer: Restarting in: " + (retry_in / 1000) + " seconds");
            Calendar calendar = Calendar.getInstance();
            AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
            long wakeTime = calendar.getTimeInMillis() + retry_in;
            PendingIntent serviceIntent = PendingIntent.getService(this, 0, new Intent(this, this.getClass()), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeTime, serviceIntent);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarm.setExact(AlarmManager.RTC_WAKEUP, wakeTime, serviceIntent);
            } else
                alarm.set(AlarmManager.RTC_WAKEUP, wakeTime, serviceIntent);
        }
    }

    public void setFailoverTimer() {
        if (CollectionServiceStarter.isBTWixel(getApplicationContext())
                || CollectionServiceStarter.isDexBridgeOrWifiandDexBridge()
                || CollectionServiceStarter.isWifiandBTWixel(getApplicationContext())
                || CollectionServiceStarter.isFollower(getApplicationContext())) {

            long retry_in = (1000 * 60 * 6);
            Log.d(TAG, "setFailoverTimer: Fallover Restarting in: " + (retry_in / (60 * 1000)) + " minutes");
            Calendar calendar = Calendar.getInstance();
            AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
            long wakeTime = calendar.getTimeInMillis() + retry_in;
            PendingIntent serviceIntent = PendingIntent.getService(this, 0, new Intent(this, this.getClass()), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeTime, serviceIntent);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarm.setExact(AlarmManager.RTC_WAKEUP, wakeTime, serviceIntent);
            } else
                alarm.set(AlarmManager.RTC_WAKEUP, wakeTime, serviceIntent);
        } else {
            stopSelf();
        }
    }

    public void attemptConnection() {
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            setRetryTimer();
            return;
        }

        mBluetoothAdapter = bluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            setRetryTimer();
            return;
        }

        if (!mBluetoothAdapter.isEnabled()) {
            if (Home.getPreferencesBoolean("automatically_turn_bluetooth_on",true)) {
                Log.i(TAG, "Turning bluetooth on as appears disabled");
                JoH.setBluetoothEnabled(getApplicationContext(), true);
            } else {
                Log.d(TAG,"Not automatically turning on bluetooth due to preferences");
            }
        }

        if (device != null) {
            mConnectionState = STATE_DISCONNECTED;
            for (BluetoothDevice bluetoothDevice : bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)) {
                if (bluetoothDevice.getAddress().compareTo(device.getAddress()) == 0) {
                    mConnectionState = STATE_CONNECTED;
                }
            }
        }

        Log.i(TAG, "attemptConnection: Connection state: " + getStateStr(mConnectionState));
        if (mConnectionState == STATE_DISCONNECTED || mConnectionState == STATE_DISCONNECTING) {
            ActiveBluetoothDevice btDevice = ActiveBluetoothDevice.first();
            if (btDevice != null) {
                final String deviceAddress = btDevice.address;
                try {
                    if (mBluetoothAdapter.isEnabled() && mBluetoothAdapter.getRemoteDevice(deviceAddress) != null) {
                        connect(deviceAddress);
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "IllegalArgumentException: " + e);
                }
            }
        } else if (mConnectionState == STATE_CONNECTED) { //WOOO, we are good to go, nothing to do here!
            Log.i(TAG, "attemptConnection: Looks like we are already connected, going to read!");
            return;
        }

        setRetryTimer();
    }

    private String getStateStr(int mConnectionState) {
        switch (mConnectionState){
            case STATE_CONNECTED:
                return "CONNECTED";
            case STATE_CONNECTING:
                return "CONNECTING";
            case STATE_DISCONNECTED:
                return "DISCONNECTED";
            case STATE_DISCONNECTING:
                return "DISCONNECTING";
            default:
                return "UNKNOWN STATE!";
        }
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            final PowerManager.WakeLock wl = JoH.getWakeLock("bluetooth-gatt", 60000);
            try {
                if (Home.getPreferencesBoolean("bluetooth_excessive_wakelocks", true)) {
                    PowerManager powerManager = (PowerManager) mContext.getSystemService(POWER_SERVICE);
                    PowerManager.WakeLock wakeLock2 = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                            "DexCollectionService");
                    wakeLock2.acquire(45000);
                }
                switch (newState) {
                    case BluetoothProfile.STATE_CONNECTED:
                        mConnectionState = STATE_CONNECTED;
                        ActiveBluetoothDevice.connected();
                        Log.i(TAG, "onConnectionStateChange: Connected to GATT server.");
                        mBluetoothGatt.discoverServices();
                        break;
                    case BluetoothProfile.STATE_DISCONNECTED:
                        Log.i(TAG, "onConnectionStateChange: State disconnected.");
                        mConnectionState = STATE_DISCONNECTED;
                        ActiveBluetoothDevice.disconnected();
                        if (prefs.getBoolean("close_gatt_on_ble_disconnect", true)) {
                            if (mBluetoothGatt != null) {
                                Log.i(TAG, "onConnectionStateChange: mBluetoothGatt is not null, closing.");
                                mBluetoothGatt.close();
                                mBluetoothGatt = null;
                                mCharacteristic = null;
                            }
                            lastdata = null;
                        }
                        Log.i(TAG, "onConnectionStateChange: Disconnected from GATT server.");
                        setRetryTimer();
                        break;
                }
            } finally {
                JoH.releaseWakeLock(wl);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onServicesDiscovered received: " + status);
                return;
            }
            final PowerManager.WakeLock wl = JoH.getWakeLock("bluetooth-onservices", 60000);
            Log.d(TAG, "onServicesDiscovered received status: " + status);

            final BluetoothGattService gattService = mBluetoothGatt.getService(xDripDataService);
            if (gattService == null) {
                Log.w(TAG, "onServicesDiscovered: service " + xDripDataService + " not found");
                listAvailableServices(mBluetoothGatt);
                JoH.releaseWakeLock(wl);
                return;
            }

            final BluetoothGattCharacteristic gattCharacteristic = gattService.getCharacteristic(xDripDataCharacteristic);
            if (gattCharacteristic == null) {
                Log.w(TAG, "onServicesDiscovered: characteristic " + xDripDataCharacteristic + " not found");
                JoH.releaseWakeLock(wl);
                return;
            }

            mCharacteristic = gattCharacteristic;
            final int charaProp = gattCharacteristic.getProperties();
            if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                mBluetoothGatt.setCharacteristicNotification(gattCharacteristic, true);

                // Experimental support for "Transmiter PL" from Marek Macner @FPV-UAV
                if (use_transmiter_pl_bluetooth) {
                    BluetoothGattDescriptor descriptor = gattCharacteristic.getDescriptor(UUID.fromString(HM10Attributes.CLIENT_CHARACTERISTIC_CONFIG));
                    Log.i(TAG, "Transmiter Descriptor found: " + descriptor.getUuid());
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    mBluetoothGatt.writeDescriptor(descriptor);
                }
            } else {
                Log.w(TAG, "onServicesDiscovered: characteristic " + xDripDataCharacteristic + " not found");
            }
            JoH.releaseWakeLock(wl);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {

            PowerManager powerManager = (PowerManager) mContext.getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wakeLock1 = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "DexCollectionService");
            wakeLock1.acquire();
            try {
                Log.i(TAG, "onCharacteristicChanged entered");
                final byte[] data = characteristic.getValue();
                if (data != null && data.length > 0) {
                    setSerialDataToTransmitterRawData(data, data.length);
                }
                lastdata = data;
            } finally {
                if (Home.getPreferencesBoolean("bluetooth_frequent_reset",false))
                {
                    Log.e(TAG,"Resetting bluetooth due to constant reset option being set!");
                    JoH.restartBluetooth(getApplicationContext(),5000);
                }
                wakeLock1.release();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onDescriptorWrite: Wrote GATT Descriptor successfully.");
            } else {
                Log.d(TAG, "onDescriptorWrite: Error writing GATT Descriptor: " + status);
            }
        }
    };

    /**
     * Displays all services and characteristics for debugging purposes.
     * @param bluetoothGatt BLE gatt profile.
     */
    private void listAvailableServices(BluetoothGatt bluetoothGatt) {
        Log.d(TAG, "Listing available services:");
        for (BluetoothGattService service : bluetoothGatt.getServices()) {
            Log.d(TAG, "Service: " + service.getUuid().toString());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                Log.d(TAG, "|-- Characteristic: " + characteristic.getUuid().toString());
            }
        }
    }

    private boolean sendBtMessage(final ByteBuffer message) {
        //check mBluetoothGatt is available
        Log.i(TAG, "sendBtMessage: entered");
        if (mBluetoothGatt == null) {
            Log.w(TAG, "sendBtMessage: lost connection");
            return false;
        }

        byte[] value = message.array();
        Log.i(TAG, "sendBtMessage: sending message");
        mCharacteristic.setValue(value);

        return mBluetoothGatt.writeCharacteristic(mCharacteristic);
    }

    private Integer convertSrc(final String Src) {
        Integer res = 0;
        String tmpSrc = Src.toUpperCase();
        res |= getSrcValue(tmpSrc.charAt(0)) << 20;
        res |= getSrcValue(tmpSrc.charAt(1)) << 15;
        res |= getSrcValue(tmpSrc.charAt(2)) << 10;
        res |= getSrcValue(tmpSrc.charAt(3)) << 5;
        res |= getSrcValue(tmpSrc.charAt(4));
        return res;
    }

    private int getSrcValue(char ch) {
        int i;
        char[] cTable = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M', 'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'W', 'X', 'Y'};
        for (i = 0; i < cTable.length; i++) {
            if (cTable[i] == ch) break;
        }
        return i;
    }

    public synchronized boolean connect(final String address) {
        Log.i(TAG, "connect: going to connect to device at address: " + address);
        if (mBluetoothAdapter == null || address == null) {
            Log.i(TAG, "connect: BluetoothAdapter not initialized or unspecified address.");
            setRetryTimer();
            return false;
        }
        if (mBluetoothGatt != null) {
            Log.i(TAG, "connect: mBluetoothGatt isnt null, Closing.");
            try {
                mBluetoothGatt.close();
            } catch (NullPointerException e) {
                Log.wtf(TAG, "Concurrency related null pointer in connect");
            }
            mBluetoothGatt = null;
        }
        device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            setRetryTimer();
            return false;
        }
        Log.i(TAG, "connect: Trying to create a new connection.");
        setRetryTimer();
        mBluetoothGatt = device.connectGatt(getApplicationContext(), true, mGattCallback);
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    public void close() {
        Log.i(TAG, "close: Closing Connection - setting state DISCONNECTED");
        if (mBluetoothGatt == null) {
            return;
        }
        try {
            mBluetoothGatt.close();
        } catch (NullPointerException e) {
            Log.wtf(TAG, "Concurrency related null pointer in close");
        }

        setRetryTimer();
        mBluetoothGatt = null;
        mCharacteristic = null;
        mConnectionState = STATE_DISCONNECTED;
    }

    public void setSerialDataToTransmitterRawData(byte[] buffer, int len) {
        long timestamp = new Date().getTime();
        last_time_seen = JoH.ts();
        watchdog_count=0;
        if (CollectionServiceStarter.isDexBridgeOrWifiandDexBridge()) {
            Log.i(TAG, "setSerialDataToTransmitterRawData: Dealing with Dexbridge packet!");
            int DexSrc;
            int TransmitterID;
            String TxId;
            Calendar c = Calendar.getInstance();
            long secondsNow = c.getTimeInMillis();
            ByteBuffer tmpBuffer = ByteBuffer.allocate(len);
            tmpBuffer.order(ByteOrder.LITTLE_ENDIAN);
            tmpBuffer.put(buffer, 0, len);
            ByteBuffer txidMessage = ByteBuffer.allocate(6);
            txidMessage.order(ByteOrder.LITTLE_ENDIAN);
            if (buffer[0] == 0x07 && buffer[1] == -15) {
                //We have a Beacon packet.  Get the TXID value and compare with dex_txid
                Log.i(TAG, "setSerialDataToTransmitterRawData: Received Beacon packet.");
                //DexSrc starts at Byte 2 of a Beacon packet.
                DexSrc = tmpBuffer.getInt(2);
                TxId = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString("dex_txid", "00000");
                TransmitterID = convertSrc(TxId);
                if (TxId.compareTo("00000") != 0 && Integer.compare(DexSrc, TransmitterID) != 0) {
                    Log.w(TAG, "setSerialDataToTransmitterRawData: TXID wrong.  Expected " + TransmitterID + " but got " + DexSrc);
                    txidMessage.put(0, (byte) 0x06);
                    txidMessage.put(1, (byte) 0x01);
                    txidMessage.putInt(2, TransmitterID);
                    sendBtMessage(txidMessage);
                }
                return;
            }
            if ((buffer[0] == 0x11 || buffer[0] == 0x15) && buffer[1] == 0x00) {
                //we have a data packet.  Check to see if the TXID is what we are expecting.
                Log.i(TAG, "setSerialDataToTransmitterRawData: Received Data packet");
                if (len >= 0x11) {
                    //DexSrc starts at Byte 12 of a data packet.
                    DexSrc = tmpBuffer.getInt(12);
                    TxId = PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString("dex_txid", "00000");
                    TransmitterID = convertSrc(TxId);
                    if (Integer.compare(DexSrc, TransmitterID) != 0) {
                        Log.w(TAG, "TXID wrong.  Expected " + TransmitterID + " but got " + DexSrc);
                        txidMessage.put(0, (byte) 0x06);
                        txidMessage.put(1, (byte) 0x01);
                        txidMessage.putInt(2, TransmitterID);
                        sendBtMessage(txidMessage);
                    }
                    PreferenceManager.getDefaultSharedPreferences(mContext).edit().putInt("bridge_battery", ByteBuffer.wrap(buffer).get(11)).apply();
                    //All is OK, so process it.
                    //first, tell the wixel it is OK to sleep.
                    Log.d(TAG, "setSerialDataToTransmitterRawData: Sending Data packet Ack, to put wixel to sleep");
                    ByteBuffer ackMessage = ByteBuffer.allocate(2);
                    ackMessage.put(0, (byte) 0x02);
                    ackMessage.put(1, (byte) 0xF0);
                    sendBtMessage(ackMessage);
                    //duplicates are already filtered in TransmitterData.create - so no need to filter here
                    lastPacketTime = secondsNow;
                    Log.v(TAG, "setSerialDataToTransmitterRawData: Creating TransmitterData at " + timestamp);
                    processNewTransmitterData(TransmitterData.create(buffer, len, timestamp), timestamp);
                    //KS if (Home.get_master()) GcmActivity.sendBridgeBattery(Home.getPreferencesInt("bridge_battery",-1));
                }
            }
        } else {
            processNewTransmitterData(TransmitterData.create(buffer, len, timestamp), timestamp);
        }
    }

    private void processNewTransmitterData(TransmitterData transmitterData, long timestamp) {
        if (transmitterData == null) {
            return;
        }

        Sensor sensor = Sensor.currentSensor();
        if (sensor == null) {
            Log.i(TAG, "setSerialDataToTransmitterRawData: No Active Sensor, Data only stored in Transmitter Data");
            return;
        }

        sensor.latest_battery_level = (sensor.latest_battery_level!=0)?Math.min(sensor.latest_battery_level, transmitterData.sensor_battery_level):transmitterData.sensor_battery_level;
        sensor.save();

        Log.d(TAG, "BgReading.create: new BG reading at " + timestamp + " with a timestamp of " + transmitterData.timestamp);
        BgReading.create(transmitterData.raw_data, transmitterData.filtered_data, this, transmitterData.timestamp);
    }

    private void watchdog() {
        if (last_time_seen == 0) return;
        if (prefs.getBoolean("bluetooth_watchdog",false)) {
            if ((JoH.ts() - last_time_seen) > 1200000) {
                if (!JoH.isOngoingCall()) {
                    Log.e(TAG, "Watchdog triggered, attempting to reset bluetooth");
                    JoH.restartBluetooth(getApplicationContext());
                    last_time_seen = JoH.ts();
                    watchdog_count++;
                    if (watchdog_count>5) last_time_seen=0;
                } else {
                    Log.e(TAG,"Delaying watchdog reset as phone call is ongoing.");
                }
            }
        }
    }
}