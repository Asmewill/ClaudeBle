package com.claude.ble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ClaudeBLE";
    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_ENABLE_BT = 101;

    // Control function map
    private static final Map<Integer, String> CONTROL_FUNCTION = new HashMap<>();
    static {
        CONTROL_FUNCTION.put(0x0,    "NUL");
        CONTROL_FUNCTION.put(0x1,    "打开,清水阀");
        CONTROL_FUNCTION.put(0x2,    "关闭,清水阀");
        CONTROL_FUNCTION.put(0x4,    "打开,污水泵与阀");
        CONTROL_FUNCTION.put(0x8,    "关闭,污水泵与阀");
        CONTROL_FUNCTION.put(0x10,   "打开,清水泵");
        CONTROL_FUNCTION.put(0x20,   "关闭,清水泵");
        CONTROL_FUNCTION.put(0x40,   "基站低水位传感器触发");
        CONTROL_FUNCTION.put(0x80,   "基站高水位传感器触发");
        CONTROL_FUNCTION.put(0x100,  "清水泵的流量计触发");
        CONTROL_FUNCTION.put(0x200,  "基站充电功能打开");
        CONTROL_FUNCTION.put(0x400,  "基站充电功能关闭");
        CONTROL_FUNCTION.put(0x800,  "基站水箱在位");
        CONTROL_FUNCTION.put(0x1000, "急停");
        CONTROL_FUNCTION.put(0x2000, "使用在位传感器");
    }

    // BLE
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic dataCharacteristic;
    private BluetoothGattCharacteristic heartbeatCharacteristic;

    // State
    private boolean isScanning = false;
    private boolean isConnected = false;
    private boolean keepHeartbeat = false;
    private double totalFlow = 0.0;
    private long lastTimestampMs = -1;
    private String selectedDeviceAddress = null;

    // UUID
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Scan results
    private final List<ScanResult> scanResultList = new ArrayList<>();
    private final List<String> deviceDisplayList = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;

    // UI Views
    private ListView lvDevices;
    private EditText etFilter, etTimeout;
    private EditText etDataUuid, etHeartbeatUuid;
    private ScrollView svData, svHeartbeat;
    private TextView tvData, tvHeartbeat;
    private TextView tvStatus;
    private Button btnScan;

    // Handlers
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Heartbeat scheduler
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?> heartbeatFuture;

    // Scan stop handler
    private Runnable stopScanRunnable;

    // Permission launcher
    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent> enableBtLauncher;

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initBluetooth();
        setupPermissionLaunchers();
        setupClickListeners();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopHeartbeat();
        stopScan();
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    // ============================================================
    // Init
    // ============================================================

    private void initViews() {
        lvDevices = findViewById(R.id.lv_devices);
        etFilter = findViewById(R.id.et_filter);
        etTimeout = findViewById(R.id.et_timeout);
        etDataUuid = findViewById(R.id.et_data_uuid);
        etHeartbeatUuid = findViewById(R.id.et_heartbeat_uuid);
        svData = findViewById(R.id.sv_data);
        svHeartbeat = findViewById(R.id.sv_heartbeat);
        tvData = findViewById(R.id.tv_data);
        tvHeartbeat = findViewById(R.id.tv_heartbeat);
        tvStatus = findViewById(R.id.tv_status);
        btnScan = findViewById(R.id.btn_scan);

        // Setup device list adapter
        deviceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, deviceDisplayList);
        lvDevices.setAdapter(deviceAdapter);

        // Device selection listener
        lvDevices.setOnItemClickListener((parent, view, position, id) -> {
            if (position < scanResultList.size()) {
                ScanResult result = scanResultList.get(position);
                selectedDeviceAddress = result.getDevice().getAddress();
                String name = result.getDevice().getName();
                if (name == null || name.isEmpty()) name = "Unknown";
                updateStatus("已选择: " + name + " [" + selectedDeviceAddress + "]");
                // Highlight selection
                for (int i = 0; i < lvDevices.getChildCount(); i++) {
                    View child = lvDevices.getChildAt(i);
                    if (child != null) {
                        child.setBackgroundColor(i == position ?
                                0xFFBBDEFB : 0x00000000);
                    }
                }
            }
        });
    }

    private void initBluetooth() {
        BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            showToast("本设备不支持蓝牙");
            finish();
            return;
        }
        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            showToast("本设备不支持BLE蓝牙");
            finish();
        }
    }

    private void setupPermissionLaunchers() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!granted) { allGranted = false; break; }
                    }
                    if (allGranted) {
                        performScan();
                    } else {
                        showToast("需要蓝牙和位置权限才能扫描设备");
                    }
                });

        enableBtLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        checkPermissionsAndScan();
                    } else {
                        showToast("请开启蓝牙");
                    }
                });
    }

    private void setupClickListeners() {
        btnScan.setOnClickListener(v -> onScanButtonClick());
        findViewById(R.id.btn_connect).setOnClickListener(v -> connectDevice());
        findViewById(R.id.btn_disconnect).setOnClickListener(v -> disconnectDevice());
        // Control buttons - map to command values
        findViewById(R.id.btn_open_pump).setOnClickListener(v -> sendControlMessage(16));    // 打开清水泵
        findViewById(R.id.btn_open_valve).setOnClickListener(v -> sendControlMessage(1));    // 打开清水阀
        findViewById(R.id.btn_close_pump).setOnClickListener(v -> sendControlMessage(32));   // 关闭清水泵
        findViewById(R.id.btn_close_valve).setOnClickListener(v -> sendControlMessage(2));   // 关闭清水阀
        findViewById(R.id.btn_open_sewage).setOnClickListener(v -> sendControlMessage(4));   // 打开污水泵
        findViewById(R.id.btn_close_sewage).setOnClickListener(v -> sendControlMessage(8));  // 关闭污水泵
        findViewById(R.id.btn_charge_close).setOnClickListener(v -> sendControlMessage(1024)); // 充电功能关
        findViewById(R.id.btn_charge_open).setOnClickListener(v -> sendControlMessage(512));   // 充电功能开
    }

    // ============================================================
    // Scan Logic
    // ============================================================

    private void onScanButtonClick() {
        int timeout;
        try {
            timeout = Integer.parseInt(etTimeout.getText().toString().trim());
            if (timeout <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showDialog("错误", "请输入有效的扫描超时时间！");
            return;
        }

        // Check BT enabled
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBtLauncher.launch(enableBtIntent);
            return;
        }
        checkPermissionsAndScan();
    }

    private void checkPermissionsAndScan() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            // Android 6~11
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (!hasPermission(Manifest.permission.BLUETOOTH))
                permissionsNeeded.add(Manifest.permission.BLUETOOTH);
        }

        if (!permissionsNeeded.isEmpty()) {
            permissionLauncher.launch(permissionsNeeded.toArray(new String[0]));
        } else {
            performScan();
        }
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED;
    }

    private void performScan() {
        if (isScanning) {
            stopScan();
            return;
        }

        scanResultList.clear();
        deviceDisplayList.clear();
        deviceAdapter.notifyDataSetChanged();
        selectedDeviceAddress = null;
        updateStatus("扫描中，请稍等...");
        btnScan.setText("停止扫描");

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            showToast("无法获取BLE扫描器，请确保蓝牙已开启");
            updateStatus("扫描失败");
            btnScan.setText("扫描设备");
            return;
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        bleScanner.startScan(null, settings, scanCallback);
        isScanning = true;

        // Auto-stop after timeout
        int timeoutSec;
        try {
            timeoutSec = Integer.parseInt(etTimeout.getText().toString().trim());
        } catch (NumberFormatException e) {
            timeoutSec = 5;
        }
        stopScanRunnable = this::stopScanAndUpdateUI;
        mainHandler.postDelayed(stopScanRunnable, timeoutSec * 1000L);
    }

    private void stopScan() {
        if (isScanning && bleScanner != null) {
            bleScanner.stopScan(scanCallback);
            isScanning = false;
        }
        if (stopScanRunnable != null) {
            mainHandler.removeCallbacks(stopScanRunnable);
            stopScanRunnable = null;
        }
    }

    private void stopScanAndUpdateUI() {
        stopScan();
        mainHandler.post(() -> {
            btnScan.setText("扫描设备");
            if (deviceDisplayList.isEmpty()) {
                updateStatus("未找到符合条件的设备");
            } else {
                updateStatus("扫描完成，找到 " + deviceDisplayList.size() + " 个设备");
            }
        });
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            mainHandler.post(() -> {
                String deviceName = result.getDevice().getName();
                if (deviceName == null) deviceName = "";

                // Apply name filter
                String filter = etFilter.getText().toString().trim().toLowerCase();
                if (!filter.isEmpty() && !deviceName.toLowerCase().contains(filter)) {
                    return;
                }

                // Check if already in list (deduplicate by address)
                String address = result.getDevice().getAddress();
                for (ScanResult existing : scanResultList) {
                    if (existing.getDevice().getAddress().equals(address)) {
                        return;
                    }
                }

                scanResultList.add(result);
                String displayName = (deviceName.isEmpty() ? "Unknown" : deviceName)
                        + " [" + address + "]"
                        + ", RSSI: " + result.getRssi();
                deviceDisplayList.add(displayName);
                deviceAdapter.notifyDataSetChanged();
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            mainHandler.post(() -> {
                updateStatus("扫描失败，错误码: " + errorCode);
                btnScan.setText("扫描设备");
                isScanning = false;
            });
        }
    };

    // ============================================================
    // Connection Logic
    // ============================================================

    private void connectDevice() {
        if (selectedDeviceAddress == null || selectedDeviceAddress.isEmpty()) {
            showDialog("错误", "请先选择设备！");
            return;
        }

        if (!hasConnectPermission()) {
            showToast("缺少蓝牙连接权限");
            return;
        }

        updateStatus("正在连接...");
        stopScan(); // Stop scan before connecting

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(selectedDeviceAddress);
        totalFlow = 0.0;
        lastTimestampMs = -1;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
    }

    private void disconnectDevice() {
        if (bluetoothGatt == null || !isConnected) {
            showDialog("错误", "没有设备连接！");
            return;
        }
        stopHeartbeat();
        isConnected = false;
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        }
        updateStatus("状态：未连接");
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return hasPermission(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return true;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                mainHandler.post(() -> updateStatus("已连接，正在发现服务..."));
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                keepHeartbeat = false;
                stopHeartbeat();
                mainHandler.post(() -> updateStatus("状态：未连接"));
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
                dataCharacteristic = null;
                heartbeatCharacteristic = null;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post(() -> updateStatus("服务发现失败，状态: " + status));
                return;
            }

            // Get UUIDs from UI
            String dataUuidStr = etDataUuid.getText().toString().trim();
            String heartbeatUuidStr = etHeartbeatUuid.getText().toString().trim();

            UUID dataUUID, heartbeatUUID;
            try {
                dataUUID = UUID.fromString(dataUuidStr);
                heartbeatUUID = UUID.fromString(heartbeatUuidStr);
            } catch (IllegalArgumentException e) {
                mainHandler.post(() -> showDialog("错误", "UUID 格式不正确: " + e.getMessage()));
                return;
            }

            // Find characteristics in all services
            for (BluetoothGattService service : gatt.getServices()) {
                BluetoothGattCharacteristic dc = service.getCharacteristic(dataUUID);
                if (dc != null) dataCharacteristic = dc;

                BluetoothGattCharacteristic hc = service.getCharacteristic(heartbeatUUID);
                if (hc != null) heartbeatCharacteristic = hc;
            }

            if (heartbeatCharacteristic == null) {
                mainHandler.post(() -> updateStatus("未找到心跳特性 UUID，请检查设置"));
                return;
            }

            // Enable notifications on heartbeat characteristic
            enableNotification(gatt, heartbeatCharacteristic);

            mainHandler.post(() -> {
                updateStatus("状态：已连接到 [" + selectedDeviceAddress + "]");
                // Start heartbeat loop
                keepHeartbeat = true;
                startHeartbeat();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic) {
            handleNotification(characteristic);
        }

        // For Android 13+
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, byte[] value) {
            handleNotification(characteristic, value);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Characteristic write failed, status: " + status);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "Descriptor write status: " + status);
        }
    };

    private void enableNotification(BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic) {
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        }
    }

    // ============================================================
    // Notification Handler
    // ============================================================

    private void handleNotification(BluetoothGattCharacteristic characteristic) {
        handleNotification(characteristic, characteristic.getValue());
    }

    private void handleNotification(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (value == null || value.length == 0) return;

        String heartbeatUuidStr = etHeartbeatUuid.getText().toString().trim();
        String dataUuidStr = etDataUuid.getText().toString().trim();
        String charUuid = characteristic.getUuid().toString();

        if (charUuid.equalsIgnoreCase(dataUuidStr)) {
            // Data characteristic notification
            String received = new String(value, StandardCharsets.UTF_8);
            mainHandler.post(() -> {
                appendToDataWindow("收到数据: " + received + "\n");
            });
        } else if (charUuid.equalsIgnoreCase(heartbeatUuidStr)) {
            // Heartbeat characteristic notification - parse "flag,flowrate" format
            String dataStr = new String(value, StandardCharsets.UTF_8);
            try {
                String[] parts = dataStr.split(",");
                if (parts.length < 2) return;

                int intPart = Integer.parseInt(parts[0].trim());
                double floatPart = Double.parseDouble(parts[1].trim());

                // Decode bitmap flags
                List<String> activeFlags = new ArrayList<>();
                for (Map.Entry<Integer, String> entry : CONTROL_FUNCTION.entrySet()) {
                    if (entry.getKey() != 0 && (intPart & entry.getKey()) != 0) {
                        activeFlags.add(entry.getValue());
                    }
                }
                String flagsStr = activeFlags.isEmpty() ? "NUL" :
                        String.join("\n\t", activeFlags);

                // Calculate flow
                long currentTimeMs = System.currentTimeMillis();
                final double[] flowResult = {0.0, 0.0};

                if (lastTimestampMs >= 0) {
                    double timeDeltaSec = (currentTimeMs - lastTimestampMs) / 1000.0;
                    double adjustedFlow = floatPart * 0.4;
                    double flowThisPeriod = (adjustedFlow / 60.0) * timeDeltaSec;
                    totalFlow += flowThisPeriod;
                    flowResult[0] = adjustedFlow;
                    flowResult[1] = flowThisPeriod;
                }
                lastTimestampMs = currentTimeMs;

                if (lastTimestampMs > 0) {
                    final double displayFlow = flowResult[0];
                    final double periodFlow = flowResult[1];
                    final double total = totalFlow;
                    final String flags = flagsStr;

                    mainHandler.post(() -> {
                        String msg = "收到返回值:\n" +
                                "\t" + flags + "\n" +
                                String.format("\t流速: %.6f L/Min\n", displayFlow) +
                                String.format("\t本周期流量: %.6f L\n", periodFlow) +
                                String.format("\t总流量: %.6f L\n", total);
                        appendToHeartbeatWindow(msg);
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() ->
                        appendToHeartbeatWindow("解析错误: " + e.getMessage() + "\n"));
            }
        }
    }

    // ============================================================
    // Control Message
    // ============================================================

    private void sendControlMessage(int controlFunction) {
        if (!isConnected || bluetoothGatt == null) {
            showDialog("错误", "设备未连接，无法发送控制消息！");
            return;
        }
        if (dataCharacteristic == null) {
            // Try to find data characteristic again
            String dataUuidStr = etDataUuid.getText().toString().trim();
            try {
                UUID dataUUID = UUID.fromString(dataUuidStr);
                for (BluetoothGattService service : bluetoothGatt.getServices()) {
                    BluetoothGattCharacteristic dc = service.getCharacteristic(dataUUID);
                    if (dc != null) { dataCharacteristic = dc; break; }
                }
            } catch (Exception e) {
                // ignore
            }
            if (dataCharacteristic == null) {
                showDialog("错误", "数据特性未找到，请检查 UUID 设置！");
                return;
            }
        }

        byte[] data = String.valueOf(controlFunction).getBytes(StandardCharsets.UTF_8);
        writeCharacteristic(dataCharacteristic, data);

        mainHandler.post(() -> appendToDataWindow("已发送控制消息: " + controlFunction + "\n"));
    }

    // ============================================================
    // Heartbeat Loop
    // ============================================================

    private void startHeartbeat() {
        stopHeartbeat(); // Ensure no duplicate
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!keepHeartbeat || !isConnected || bluetoothGatt == null) {
                stopHeartbeat();
                return;
            }
            if (heartbeatCharacteristic == null) return;
            byte[] data = "1".getBytes(StandardCharsets.UTF_8);
            writeCharacteristic(heartbeatCharacteristic, data);
            mainHandler.post(() -> appendToHeartbeatWindow("已发送心跳: 1\n"));
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        keepHeartbeat = false;
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(true);
            heartbeatFuture = null;
        }
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    // ============================================================
    // GATT Write Helper
    // ============================================================

    private synchronized void writeCharacteristic(BluetoothGattCharacteristic characteristic,
            byte[] data) {
        if (bluetoothGatt == null || !isConnected) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ API
                bluetoothGatt.writeCharacteristic(characteristic, data,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                characteristic.setValue(data);
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                bluetoothGatt.writeCharacteristic(characteristic);
            }
        } catch (Exception e) {
            Log.e(TAG, "Write characteristic failed: " + e.getMessage());
        }
    }

    // ============================================================
    // UI Helpers
    // ============================================================

    private void updateStatus(String msg) {
        mainHandler.post(() -> tvStatus.setText(msg));
    }

    private void appendToDataWindow(String text) {
        tvData.append(text);
        svData.post(() -> svData.fullScroll(View.FOCUS_DOWN));
    }

    private void appendToHeartbeatWindow(String text) {
        tvHeartbeat.append(text);
        svHeartbeat.post(() -> svHeartbeat.fullScroll(View.FOCUS_DOWN));
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void showDialog(String title, String msg) {
        mainHandler.post(() -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show());
    }
}
