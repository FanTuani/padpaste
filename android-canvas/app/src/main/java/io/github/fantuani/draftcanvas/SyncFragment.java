package io.github.fantuani.draftcanvas;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.fantuani.padpaste.DuplexSync;
import io.github.fantuani.padpaste.SyncService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class SyncFragment extends Fragment {
    private static final int PICK_PAIRING = 630;
    private static final int STORAGE_PERMISSION = 103;
    private static final int BLUETOOTH_PERMISSION = 104;
    private static final int INK = 0xFF243137;
    private static final int MUTED = 0xFF596663;
    private static final int TEAL = 0xFF167B70;

    private MainActivity activity;
    private DuplexSync link;
    private SharedPreferences preferences;
    private TextView connection, address, receiveStatus, sendStatus;
    private Switch receive, send, bluetooth;
    private TextView bluetoothStatus;
    private Button reconnect, grant;
    private LinearLayout pairing;
    private EditText pairingCode;
    private boolean updating;
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { render(); }
    };

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        activity = (MainActivity) getActivity();
        link = DuplexSync.get(activity);
        preferences = QuestionInbox.preferences(activity);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        FrameLayout center = new FrameLayout(activity);
        scroll.addView(center, new ScrollView.LayoutParams(-1, -2));
        LinearLayout content = column();
        content.setPadding(dp(24), dp(16), dp(24), dp(32));
        center.addView(content, new FrameLayout.LayoutParams(
                Math.min(dp(600), getResources().getDisplayMetrics().widthPixels), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        Button back = button("‹  返回草稿", activity::onBackPressed);
        content.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = label("同步", 32, INK);
        title.setTypeface(null, Typeface.BOLD);
        content.addView(title);

        LinearLayout status = card(content);
        status.addView(label("连接状态", 14, MUTED));
        connection = label("", 23, INK);
        connection.setTypeface(null, Typeface.BOLD);
        status.addView(connection);
        address = label("", 14, MUTED);
        status.addView(address);

        LinearLayout directions = card(content);
        receiveStatus = label("", 13, MUTED);
        receive = direction(directions, "Mac 剪贴板 → 草稿纸", receiveStatus);
        receive.setOnCheckedChangeListener((view, enabled) -> {
            if (updating) return;
            preferences.edit().putBoolean(QuestionInbox.RECEIVE_ENABLED, enabled).apply();
            activity.receiveSettingChanged();
            render();
        });
        sendStatus = label("", 13, MUTED);
        send = direction(directions, "平板截图 → Mac 剪贴板", sendStatus);
        send.setOnCheckedChangeListener((view, enabled) -> {
            if (updating) return;
            SyncService.setEnabled(activity, enabled);
            render();
            if (enabled && !SyncService.filesAllowed(activity)) requestStorage();
        });
        grant = button("授予文件访问权限", this::requestStorage);
        directions.addView(grant, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout configuration = card(content);
        configuration.addView(label("连接配置", 16, INK));
        configuration.addView(label("从 Mac 的 PadPaste 菜单获取同步配对码或配对文件。", 14, MUTED));
        if (Build.VERSION.SDK_INT >= 31) {
            bluetoothStatus = label("", 13, MUTED);
            bluetooth = direction(configuration, "蓝牙备用", bluetoothStatus);
            bluetooth.setOnCheckedChangeListener((view, enabled) -> {
                if (updating) return;
                if (enabled && !link.bluetoothAllowed()) {
                    render();
                    requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION);
                    return;
                }
                setBluetoothEnabled(enabled);
            });
        }
        LinearLayout controls = new LinearLayout(activity);
        configuration.addView(controls);
        Button edit = button("输入配对码", this::togglePairing);
        controls.addView(edit, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(button("导入配对文件", this::pickPairing), new LinearLayout.LayoutParams(0, -2, 1));
        pairing = column();
        pairing.setVisibility(View.GONE);
        configuration.addView(pairing);
        pairingCode = new EditText(activity);
        pairingCode.setHint("粘贴配对码");
        pairingCode.setSingleLine(true);
        pairingCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pairing.addView(pairingCode, new LinearLayout.LayoutParams(-1, -2));
        pairing.addView(button("保存配对", () -> pair(pairingCode.getText().toString())),
                new LinearLayout.LayoutParams(-2, -2));
        reconnect = button("重新连接", () -> {
            link.reconnect();
            SyncService.resumeMonitoring(activity);
            render();
        });
        configuration.addView(reconnect, new LinearLayout.LayoutParams(-2, -2));
        render();
        return scroll;
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(SyncService.STATE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(changes, filter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(changes, filter);
        IntentFilter bluetoothState = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(changes, bluetoothState, Context.RECEIVER_EXPORTED);
        else activity.registerReceiver(changes, bluetoothState);
        render();
    }

    @Override public void onResume() {
        super.onResume();
        render();
    }

    @Override public void onStop() {
        activity.unregisterReceiver(changes);
        super.onStop();
    }

    private void render() {
        boolean paired = link.paired();
        boolean connected = link.connected();
        boolean receiving = preferences.getBoolean(QuestionInbox.RECEIVE_ENABLED, true);
        boolean sending = SyncService.enabled(activity);
        boolean allowed = SyncService.filesAllowed(activity);
        boolean paused = !receiving && !sending;

        updating = true;
        receive.setChecked(receiving);
        send.setChecked(sending);
        if (bluetooth != null) {
            bluetooth.setChecked(link.bluetoothEnabled() && link.bluetoothAllowed());
        }
        updating = false;

        if (bluetoothStatus != null) {
            bluetoothStatus.setText(link.connectedViaBluetooth() ? "正在使用蓝牙"
                    : !link.bluetoothEnabled() ? "Wi-Fi 不可用时接管，需要在 Mac 上一并开启"
                    : !link.bluetoothAllowed() ? "需要附近设备权限"
                    : !link.bluetoothRadioOn() ? "蓝牙已关闭" : "Wi-Fi 优先 · 蓝牙已待命");
        }

        connection.setText(!paired ? "尚未配对" : paused ? "同步已暂停"
                : connected ? (link.connectedViaBluetooth() ? "已连接 Mac · 蓝牙"
                    : link.duplex() ? "已连接 Mac · Wi-Fi" : "已连接 · 旧版 Mac") : "等待 Mac 连接");
        connection.setTextColor(connected && !paused ? TEAL : INK);
        String endpoint = link.connectedAddress();
        address.setText(endpoint);
        address.setVisibility(!paused && !endpoint.isEmpty() ? View.VISIBLE : View.GONE);

        receiveStatus.setText(!receiving ? "已关闭" : !paired ? "配对后可接收"
                : connected ? "草稿纸在前台时接收" : "等待连接");
        sendStatus.setText(!sending ? "已关闭" : !allowed ? "需要文件访问权限"
                : SyncService.monitorStatus());
        grant.setVisibility(sending && !allowed ? View.VISIBLE : View.GONE);
        reconnect.setEnabled(paired && (receiving || sending && allowed));
    }

    private void setBluetoothEnabled(boolean enabled) {
        link.setBluetoothEnabled(enabled);
        SyncService.refreshBluetoothForegroundType(activity);
        render();
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == BLUETOOTH_PERMISSION) {
            if (link.bluetoothAllowed()) setBluetoothEnabled(true);
            else render();
        }
        if (request == STORAGE_PERMISSION) {
            SyncService.resumeMonitoring(activity);
            render();
        }
    }

    private void pickPairing() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE), PICK_PAIRING);
        } catch (ActivityNotFoundException error) {
            message("找不到文件选择器");
        }
    }

    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PAIRING || result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream stream = activity.getContentResolver().openInputStream(data.getData())) {
            if (stream == null) throw new IOException();
            byte[] bytes = new byte[8193];
            int length = 0;
            while (length < bytes.length) {
                int count = stream.read(bytes, length, bytes.length - length);
                if (count == -1) break;
                length += count;
            }
            if (length == bytes.length) throw new IOException();
            pair(new String(bytes, 0, length, StandardCharsets.UTF_8));
        } catch (IOException | SecurityException error) {
            message("无法读取配对文件");
        }
    }

    private void togglePairing() {
        if (pairing.isShown()) closePairing();
        else pairing.setVisibility(View.VISIBLE);
    }

    private void closePairing() {
        activity.getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(pairingCode.getWindowToken(), 0);
        pairingCode.setText("");
        pairing.setVisibility(View.GONE);
    }

    private void pair(String code) {
        try {
            link.pair(code);
        } catch (Exception error) {
            message("无法读取配对码");
            return;
        }
        closePairing();
        SyncService.resumeMonitoring(activity);
        render();
        message("配对已保存");
    }

    private void message(String text) { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show(); }

    @android.annotation.SuppressLint("UseSwitchCompatOrMaterialCode")
    private Switch direction(LinearLayout group, String text, TextView detail) {
        Switch toggle = new Switch(activity);
        toggle.setText(text);
        toggle.setTextSize(16);
        toggle.setTextColor(INK);
        toggle.setMinHeight(dp(48));
        group.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        group.addView(detail);
        return toggle;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFFFFFF);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), 0xFFE4EAE6);
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        parent.addView(card, params);
        return card;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String text, Runnable action) {
        Button view = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        view.setText(text);
        view.setAllCaps(false);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
