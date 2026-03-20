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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ClaudeBLE";

    // ── 控制功能表 ──────────────────────────────────────────────
    private static final Map<Integer, String> CONTROL_FUNCTION = new HashMap<>();
    static {
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

    // CCCD UUID
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ── BLE 对象 ─────────────────────────────────────────────────
    private BluetoothAdapter            bluetoothAdapter;
    private BluetoothLeScanner          bleScanner;
    private BluetoothGatt               bluetoothGatt;
    private BluetoothGattCharacteristic dataCharacteristic;
    private BluetoothGattCharacteristic heartbeatCharacteristic;

    // ── GATT 串行操作队列（核心修复）────────────────────────────
    // Android BLE GATT 是单线程总线：同一时刻只能有一个 write/descriptor 操作在途。
    // 把所有操作放入队列，每次只执行队头，回调完成后再驱动下一个。
    private final Queue<Runnable>  gattQueue = new LinkedList<>();
    private final AtomicBoolean    gattBusy  = new AtomicBoolean(false);

    // ── 状态 ─────────────────────────────────────────────────────
    private volatile boolean isScanning    = false;
    private volatile boolean isConnected   = false;
    private volatile boolean keepHeartbeat = false;
    private double   totalFlow      = 0.0;
    private long     lastTimestampMs = -1;
    private String   selectedDeviceAddress = null;

    // ── 扫描结果 ──────────────────────────────────────────────────
    private final List<ScanResult> scanResultList    = new ArrayList<>();
    private final List<String>     deviceDisplayList = new ArrayList<>();
    private ArrayAdapter<String>   deviceAdapter;

    // ── UI ────────────────────────────────────────────────────────
    private ListView   lvDevices;
    private EditText   etFilter, etTimeout, etDataUuid, etHeartbeatUuid;
    private ScrollView svData, svHeartbeat;
    private TextView   tvData, tvHeartbeat, tvStatus;
    private Button     btnScan;

    // ── 调度 ──────────────────────────────────────────────────────
    private final Handler            mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService heartbeatExecutor;
    private ScheduledFuture<?>       heartbeatFuture;
    private Runnable                 stopScanRunnable;

    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent>   enableBtLauncher;

    // ═════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════

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
        closeGatt();
    }

    // ═════════════════════════════════════════════════════════════
    // 初始化
    // ═════════════════════════════════════════════════════════════

    private void initViews() {
        lvDevices       = findViewById(R.id.lv_devices);
        etFilter        = findViewById(R.id.et_filter);
        etTimeout       = findViewById(R.id.et_timeout);
        etDataUuid      = findViewById(R.id.et_data_uuid);
        etHeartbeatUuid = findViewById(R.id.et_heartbeat_uuid);
        svData          = findViewById(R.id.sv_data);
        svHeartbeat     = findViewById(R.id.sv_heartbeat);
        tvData          = findViewById(R.id.tv_data);
        tvHeartbeat     = findViewById(R.id.tv_heartbeat);
        tvStatus        = findViewById(R.id.tv_status);
        btnScan         = findViewById(R.id.btn_scan);

        deviceAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, deviceDisplayList);
        lvDevices.setAdapter(deviceAdapter);

        lvDevices.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= scanResultList.size()) return;
            ScanResult r = scanResultList.get(position);
            selectedDeviceAddress = r.getDevice().getAddress();
            String name = r.getDevice().getName();
            if (name == null || name.isEmpty()) name = "Unknown";
            updateStatus("已选择: " + name + " [" + selectedDeviceAddress + "]");
            for (int i = 0; i < lvDevices.getChildCount(); i++) {
                View child = lvDevices.getChildAt(i);
                if (child != null)
                    child.setBackgroundColor(i == position ? 0xFFBBDEFB : 0x00000000);
            }
        });
    }

    private void initBluetooth() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) { showToast("本设备不支持蓝牙"); finish(); return; }
        bluetoothAdapter = bm.getAdapter();
        if (bluetoothAdapter == null) { showToast("本设备不支持BLE蓝牙"); finish(); }
    }

    private void setupPermissionLaunchers() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean all = true;
                    for (Boolean g : result.values()) if (!g) { all = false; break; }
                    if (all) checkBluetoothEnabledAndScan();
                    else     showToast("需要蓝牙和位置权限才能扫描设备");
                });

        enableBtLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) performScan();
                    else showToast("请开启蓝牙后再试");
                });
    }

    private void setupClickListeners() {
        btnScan.setOnClickListener(v -> onScanButtonClick());
        findViewById(R.id.btn_connect).setOnClickListener(v -> connectDevice());
        findViewById(R.id.btn_disconnect).setOnClickListener(v -> disconnectDevice());
        findViewById(R.id.btn_open_pump).setOnClickListener(v -> sendControlMessage(16));
        findViewById(R.id.btn_open_valve).setOnClickListener(v -> sendControlMessage(1));
        findViewById(R.id.btn_close_pump).setOnClickListener(v -> sendControlMessage(32));
        findViewById(R.id.btn_close_valve).setOnClickListener(v -> sendControlMessage(2));
        findViewById(R.id.btn_open_sewage).setOnClickListener(v -> sendControlMessage(4));
        findViewById(R.id.btn_close_sewage).setOnClickListener(v -> sendControlMessage(8));
        findViewById(R.id.btn_charge_close).setOnClickListener(v -> sendControlMessage(1024));
        findViewById(R.id.btn_charge_open).setOnClickListener(v -> sendControlMessage(512));
    }

    // ═════════════════════════════════════════════════════════════
    // 扫描
    // ═════════════════════════════════════════════════════════════

    private void onScanButtonClick() {
        try {
            int t = Integer.parseInt(etTimeout.getText().toString().trim());
            if (t <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showDialog("错误", "请输入有效的扫描超时时间！"); return;
        }
        checkPermissionsAndScan();
    }

    private void checkPermissionsAndScan() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPerm(Manifest.permission.BLUETOOTH_SCAN))    need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!need.isEmpty()) permissionLauncher.launch(need.toArray(new String[0]));
        else checkBluetoothEnabledAndScan();
    }

    private void checkBluetoothEnabledAndScan() {
        if (bluetoothAdapter == null) { showToast("本设备不支持BLE蓝牙"); return; }
        if (!bluetoothAdapter.isEnabled())
            enableBtLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        else
            performScan();
    }

    private void performScan() {
        if (isScanning) { stopScan(); return; }
        scanResultList.clear(); deviceDisplayList.clear();
        deviceAdapter.notifyDataSetChanged();
        selectedDeviceAddress = null;
        updateStatus("扫描中，请稍等...");
        btnScan.setText("停止扫描");

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            showToast("无法获取BLE扫描器"); btnScan.setText("扫描设备"); return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        bleScanner.startScan(null, settings, scanCallback);
        isScanning = true;

        int sec = 5;
        try { sec = Integer.parseInt(etTimeout.getText().toString().trim()); }
        catch (NumberFormatException ignored) {}
        stopScanRunnable = this::stopScanAndUpdateUI;
        mainHandler.postDelayed(stopScanRunnable, sec * 1000L);
    }

    private void stopScan() {
        if (isScanning && bleScanner != null) { bleScanner.stopScan(scanCallback); isScanning = false; }
        if (stopScanRunnable != null) { mainHandler.removeCallbacks(stopScanRunnable); stopScanRunnable = null; }
    }

    private void stopScanAndUpdateUI() {
        stopScan();
        mainHandler.post(() -> {
            btnScan.setText("扫描设备");
            updateStatus(deviceDisplayList.isEmpty()
                    ? "未找到符合条件的设备"
                    : "扫描完成，找到 " + deviceDisplayList.size() + " 个设备");
        });
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            mainHandler.post(() -> {
                String name = result.getDevice().getName();
                if (name == null) name = "";
                String filter = etFilter.getText().toString().trim().toLowerCase();
                if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) return;
                String addr = result.getDevice().getAddress();
                for (ScanResult ex : scanResultList)
                    if (ex.getDevice().getAddress().equals(addr)) return;
                scanResultList.add(result);
                deviceDisplayList.add((name.isEmpty() ? "Unknown" : name)
                        + " [" + addr + "], RSSI: " + result.getRssi());
                deviceAdapter.notifyDataSetChanged();
            });
        }
        @Override public void onScanFailed(int errorCode) {
            mainHandler.post(() -> {
                updateStatus("扫描失败，错误码: " + errorCode);
                btnScan.setText("扫描设备"); isScanning = false;
            });
        }
    };

    // ═════════════════════════════════════════════════════════════
    // 连接 / 断开
    // ═════════════════════════════════════════════════════════════

    private void connectDevice() {
        if (selectedDeviceAddress == null || selectedDeviceAddress.isEmpty()) {
            showDialog("错误", "请先选择设备！"); return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
            showToast("缺少蓝牙连接权限"); return;
        }
        closeGatt();               // 先彻底关闭旧连接
        gattQueue.clear();
        gattBusy.set(false);
        totalFlow = 0.0; lastTimestampMs = -1;

        updateStatus("正在连接...");
        stopScan();

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(selectedDeviceAddress);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            bluetoothGatt = device.connectGatt(this, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        else
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
    }

    private void disconnectDevice() {
        if (bluetoothGatt == null || !isConnected) {
            showDialog("错误", "没有设备连接！"); return;
        }
        stopHeartbeat();
        gattQueue.clear();
        gattBusy.set(false);
        isConnected = false;
        bluetoothGatt.disconnect();
        updateStatus("状态：未连接");
    }

    /** 安全关闭 GATT，必须先 disconnect 再 close */
    private void closeGatt() {
        stopHeartbeat();
        if (bluetoothGatt != null) {
            try { bluetoothGatt.disconnect(); } catch (Exception ignored) {}
            try { bluetoothGatt.close();      } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        dataCharacteristic      = null;
        heartbeatCharacteristic = null;
        isConnected = false;
        gattQueue.clear();
        gattBusy.set(false);
    }

    // ═════════════════════════════════════════════════════════════
    // GATT 回调
    // ═════════════════════════════════════════════════════════════

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                // ★ 修复1：连接成功立即请求高连接优先级，缩短连接间隔，防止超时断连
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                mainHandler.post(() -> updateStatus("已连接，正在发现服务..."));
                // ★ 修复2：延迟 600ms 再 discoverServices，给底层 BLE 栈充分建链时间
                mainHandler.postDelayed(() -> {
                    if (bluetoothGatt != null && isConnected) {
                        gatt.discoverServices();
                    }
                }, 600);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected, gatt status=" + status);
                boolean wasConnected = isConnected;
                isConnected   = false;
                keepHeartbeat = false;
                stopHeartbeat();
                gattQueue.clear();
                gattBusy.set(false);
                dataCharacteristic      = null;
                heartbeatCharacteristic = null;
                if (bluetoothGatt != null) {
                    try { bluetoothGatt.close(); } catch (Exception ignored) {}
                    bluetoothGatt = null;
                }
                mainHandler.post(() -> updateStatus(wasConnected
                        ? "状态：连接已断开 (状态码=" + status + ")"
                        : "状态：未连接"));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post(() -> updateStatus("服务发现失败: " + status)); return;
            }
            String dataUuidStr = etDataUuid.getText().toString().trim();
            String hbUuidStr   = etHeartbeatUuid.getText().toString().trim();
            UUID dataUUID, hbUUID;
            try {
                dataUUID = UUID.fromString(dataUuidStr);
                hbUUID   = UUID.fromString(hbUuidStr);
            } catch (IllegalArgumentException e) {
                mainHandler.post(() -> showDialog("错误", "UUID 格式不正确: " + e.getMessage()));
                return;
            }
            for (BluetoothGattService svc : gatt.getServices()) {
                BluetoothGattCharacteristic dc = svc.getCharacteristic(dataUUID);
                if (dc != null) dataCharacteristic = dc;
                BluetoothGattCharacteristic hc = svc.getCharacteristic(hbUUID);
                if (hc != null) heartbeatCharacteristic = hc;
            }
            if (heartbeatCharacteristic == null) {
                mainHandler.post(() -> updateStatus("未找到心跳特性 UUID，请检查设置")); return;
            }
            mainHandler.post(() -> updateStatus("状态：已连接到 [" + selectedDeviceAddress + "]"));

            // ★ 修复3：开启通知也走 GATT 队列，保证串行
            enqueueEnableNotification(gatt, heartbeatCharacteristic);
        }

        // ★ 修复4：Descriptor 写完后才解锁队列，并在此回调里启动心跳
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "onDescriptorWrite status=" + status);
            gattBusy.set(false);
            drainQueue();    // 解锁队列，执行后续操作

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 心跳 Descriptor 写成功后再启动心跳，完全避免并发
                String hbUuid = etHeartbeatUuid.getText().toString().trim();
                if (descriptor.getCharacteristic().getUuid().toString()
                        .equalsIgnoreCase(hbUuid)) {
                    mainHandler.post(() -> {
                        keepHeartbeat = true;
                        startHeartbeat();
                    });
                }
            }
        }

        // ★ 修复5：每次 Characteristic 写完后解锁队列
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            if (status != BluetoothGatt.GATT_SUCCESS)
                Log.w(TAG, "CharWrite failed status=" + status);
            gattBusy.set(false);
            drainQueue();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            handleNotification(characteristic, characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            handleNotification(characteristic, value);
        }
    };

    // ═════════════════════════════════════════════════════════════
    // GATT 串行队列实现
    // ═════════════════════════════════════════════════════════════

    /** 将「写 Descriptor 开通知」封装入队 */
    private void enqueueEnableNotification(BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic) {
        enqueueGattOp(() -> {
            boolean ok = gatt.setCharacteristicNotification(characteristic, true);
            Log.d(TAG, "setCharacteristicNotification=" + ok);
            BluetoothGattDescriptor desc = characteristic.getDescriptor(CCCD_UUID);
            if (desc != null) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean wr = gatt.writeDescriptor(desc);
                Log.d(TAG, "writeDescriptor=" + wr);
                if (!wr) { gattBusy.set(false); drainQueue(); } // 写失败也要解锁
            } else {
                // 无 CCCD，直接解锁并启动心跳
                gattBusy.set(false);
                drainQueue();
                mainHandler.post(() -> { keepHeartbeat = true; startHeartbeat(); });
            }
        });
    }

    /** 将「写 Characteristic」封装入队 */
    private void enqueueWrite(BluetoothGattCharacteristic characteristic, byte[] data) {
        enqueueGattOp(() -> {
            if (bluetoothGatt == null || !isConnected) {
                gattBusy.set(false); drainQueue(); return;
            }
            boolean ok;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 新 API，返回 0 = SUCCESS
                int ret = bluetoothGatt.writeCharacteristic(
                        characteristic, data,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                ok = (ret == 0);
            } else {
                characteristic.setValue(data);
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                ok = bluetoothGatt.writeCharacteristic(characteristic);
            }
            if (!ok) { gattBusy.set(false); drainQueue(); } // 写失败也要解锁
        });
    }

    /** 入队 */
    private synchronized void enqueueGattOp(Runnable op) {
        gattQueue.add(op);
        drainQueue();
    }

    /** 如果队列空闲则取出并执行队头 */
    private synchronized void drainQueue() {
        if (gattBusy.get() || gattQueue.isEmpty()) return;
        Runnable next = gattQueue.poll();
        if (next == null) return;
        gattBusy.set(true);
        next.run();
    }

    // ═════════════════════════════════════════════════════════════
    // 通知数据解析
    // ═════════════════════════════════════════════════════════════

    private void handleNotification(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (value == null || value.length == 0) return;
        String charUuid    = characteristic.getUuid().toString();
        String dataUuidStr = etDataUuid.getText().toString().trim();
        String hbUuidStr   = etHeartbeatUuid.getText().toString().trim();

        if (charUuid.equalsIgnoreCase(dataUuidStr)) {
            String received = new String(value, StandardCharsets.UTF_8);
            mainHandler.post(() -> appendToDataWindow("收到数据: " + received + "\n"));

        } else if (charUuid.equalsIgnoreCase(hbUuidStr)) {
            String dataStr = new String(value, StandardCharsets.UTF_8);
            try {
                String[] parts = dataStr.split(",");
                if (parts.length < 2) return;
                int    intPart   = Integer.parseInt(parts[0].trim());
                double floatPart = Double.parseDouble(parts[1].trim());

                List<String> flags = new ArrayList<>();
                for (Map.Entry<Integer, String> e : CONTROL_FUNCTION.entrySet())
                    if ((intPart & e.getKey()) != 0) flags.add(e.getValue());
                String flagsStr = flags.isEmpty() ? "NUL" : String.join("\n\t", flags);

                long   currentMs  = System.currentTimeMillis();
                double displayFlow = 0, periodFlow = 0;
                if (lastTimestampMs >= 0) {
                    double dt  = (currentMs - lastTimestampMs) / 1000.0;
                    displayFlow = floatPart * 0.4;
                    periodFlow  = (displayFlow / 60.0) * dt;
                    totalFlow  += periodFlow;
                }
                lastTimestampMs = currentMs;

                final double df = displayFlow, pf = periodFlow, tf = totalFlow;
                final String fs = flagsStr;
                mainHandler.post(() -> appendToHeartbeatWindow(
                        "收到返回值:\n\t" + fs + "\n"
                                + String.format("\t流速: %.6f L/Min\n", df)
                                + String.format("\t本周期流量: %.6f L\n", pf)
                                + String.format("\t总流量: %.6f L\n", tf)));
            } catch (Exception e) {
                mainHandler.post(() ->
                        appendToHeartbeatWindow("解析错误: " + e.getMessage() + "\n"));
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 控制消息
    // ═════════════════════════════════════════════════════════════

    private void sendControlMessage(int cmd) {
        if (!isConnected || bluetoothGatt == null) {
            showDialog("错误", "设备未连接！"); return;
        }
        if (dataCharacteristic == null) {
            try {
                UUID u = UUID.fromString(etDataUuid.getText().toString().trim());
                for (BluetoothGattService s : bluetoothGatt.getServices()) {
                    BluetoothGattCharacteristic c = s.getCharacteristic(u);
                    if (c != null) { dataCharacteristic = c; break; }
                }
            } catch (Exception ignored) {}
        }
        if (dataCharacteristic == null) { showDialog("错误", "数据特性未找到！"); return; }
        enqueueWrite(dataCharacteristic, String.valueOf(cmd).getBytes(StandardCharsets.UTF_8));
        mainHandler.post(() -> appendToDataWindow("已发送控制消息: " + cmd + "\n"));
    }

    // ═════════════════════════════════════════════════════════════
    // 心跳
    // ═════════════════════════════════════════════════════════════

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        // ★ 首次延迟 1s，让 Descriptor 写入彻底完成后再开始心跳
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!keepHeartbeat || !isConnected || heartbeatCharacteristic == null) {
                stopHeartbeat(); return;
            }
            // 心跳也走队列，不和其他写操作并发
            enqueueWrite(heartbeatCharacteristic, "1".getBytes(StandardCharsets.UTF_8));
            mainHandler.post(() -> appendToHeartbeatWindow("已发送心跳: 1\n"));
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        keepHeartbeat = false;
        if (heartbeatFuture   != null) { heartbeatFuture.cancel(true);   heartbeatFuture   = null; }
        if (heartbeatExecutor != null) { heartbeatExecutor.shutdownNow(); heartbeatExecutor = null; }
    }

    // ═════════════════════════════════════════════════════════════
    // 工具方法
    // ═════════════════════════════════════════════════════════════

    private boolean hasPerm(String p) {
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }

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
                .setTitle(title).setMessage(msg)
                .setPositiveButton("确定", null).show());
    }
}
