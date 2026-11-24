package io.github.fantuani.draftcanvas;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Fragment;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
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
    private static final int PAPER = 0xFFFAF9F5;
    private static final int INK = 0xFF23312C;
    private static final int MUTED = 0xFF586760;
    private static final int LINE = 0xFFDBE0D9;
    private static final int GREEN = 0xFF206452;
    private static final int OFF = 0xFFB7BFB9;
    private static final PathInterpolator EASE = new PathInterpolator(.2f, .8f, .25f, 1);

    private MainActivity activity;
    private DuplexSync link;
    private SharedPreferences preferences;
    private TextView connection, route, address, detail, bluetoothStatus, settingsToggle;
    private View dot;
    private Switch receive, send, bluetooth;
    private Button nextStep, grant, reconnect, editPairing;
    private LinearLayout content, settings, pairing;
    private ImageView chevron;
    private EditText pairingCode;
    private ScrollView scroll;
    private boolean expanded, editingPairing, updating;

    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { render(); }
    };

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        activity = (MainActivity) getActivity();
        link = DuplexSync.get(activity);
        preferences = QuestionInbox.preferences(activity);
        expanded = state != null && state.getBoolean("expanded");
        editingPairing = state != null && state.getBoolean("editingPairing");

        scroll = new ScrollView(activity);
        scroll.setId(R.id.sync_scroll);
        scroll.setBackgroundColor(PAPER);
        scroll.setFillViewport(true);
        FrameLayout center = new FrameLayout(activity);
        scroll.addView(center);
        content = new LinearLayout(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = Math.min(MeasureSpec.getSize(widthSpec), dp(518));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(20), dp(24), dp(32));
        content.setFocusableInTouchMode(true);
        center.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        LinearLayout header = row();
        content.addView(header);
        ImageButton back = new ImageButton(activity, null, android.R.attr.borderlessButtonStyle);
        back.setImageResource(R.drawable.ic_sync_back);
        back.setContentDescription("返回草稿");
        back.setOnClickListener(v -> activity.onBackPressed());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = label("同步", 15, MUTED);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title);

        buildStatus();
        divider(content);
        receive = direction(content, "接收 Mac 题图", R.drawable.ic_sync_receive);
        receive.setOnCheckedChangeListener((view, enabled) -> {
            if (updating) return;
            preferences.edit().putBoolean(QuestionInbox.RECEIVE_ENABLED, enabled).apply();
            activity.receiveSettingChanged();
            render();
        });
        divider(content);
        send = direction(content, "发送平板截图到 Mac", R.drawable.ic_sync_send);
        send.setOnCheckedChangeListener((view, enabled) -> {
            if (updating) return;
            SyncService.setEnabled(activity, enabled);
            render();
            if (enabled && !SyncService.filesAllowed(activity)) requestStorage();
        });
        grant = button("授予截图文件访问权限", this::requestStorage);
        content.addView(grant, new LinearLayout.LayoutParams(-2, -2));
        divider(content);
        buildSettings();
        render();
        return scroll;
    }

    private void buildStatus() {
        LinearLayout summary = row();
        LinearLayout.LayoutParams margins = new LinearLayout.LayoutParams(-1, -2);
        margins.topMargin = dp(20);
        margins.bottomMargin = dp(24);
        content.addView(summary, margins);
        ImageView laptop = icon(R.drawable.ic_sync_laptop);
        GradientDrawable outline = rounded(PAPER, 13);
        outline.setStroke(dp(1), LINE);
        laptop.setBackground(outline);
        laptop.setScaleType(ImageView.ScaleType.CENTER);
        summary.addView(laptop, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout labels = column();
        LinearLayout.LayoutParams besideIcon = new LinearLayout.LayoutParams(0, -2, 1);
        besideIcon.setMarginStart(dp(16));
        summary.addView(labels, besideIcon);
        connection = label("", 26, INK);
        connection.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        connection.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        labels.addView(connection);
        LinearLayout channel = row();
        labels.addView(channel);
        dot = new View(activity);
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dotLayout = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLayout.setMarginEnd(dp(7));
        channel.addView(dot, dotLayout);
        route = label("", 13, MUTED);
        channel.addView(route);
        nextStep = button("", () -> {
            if (link.paired()) reconnect();
            else {
                showSettings(true, true);
                showPairing(true);
                revealPairing();
            }
        });
        content.addView(nextStep, new LinearLayout.LayoutParams(-2, -2));
    }

    private void buildSettings() {
        FrameLayout disclosure = new FrameLayout(activity);
        content.addView(disclosure);
        settingsToggle = label("连接设置", 14, MUTED);
        settingsToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_sync_settings, 0, 0, 0);
        settingsToggle.setCompoundDrawablePadding(dp(12));
        settingsToggle.setPaddingRelative(0, 0, dp(30), 0);
        settingsToggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        settingsToggle.setMinHeight(dp(60));
        settingsToggle.setFocusable(true);
        TypedValue ripple = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        settingsToggle.setBackgroundResource(ripple.resourceId);
        settingsToggle.setOnClickListener(v -> showSettings(!expanded, true));
        disclosure.addView(settingsToggle, new FrameLayout.LayoutParams(-1, -2));
        chevron = icon(R.drawable.ic_sync_chevron);
        disclosure.addView(chevron, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.END | Gravity.CENTER_VERTICAL));
        settings = column();
        settings.setPaddingRelative(dp(30), 0, 0, dp(8));
        content.addView(settings);

        if (Build.VERSION.SDK_INT >= 31) {
            bluetooth = direction(settings, "蓝牙备用", 0);
            bluetoothStatus = label("", 13, MUTED);
            settings.addView(bluetoothStatus);
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
        detail = label("", 13, MUTED);
        detail.setPadding(0, dp(12), 0, 0);
        settings.addView(detail);
        address = label("", 13, MUTED);
        address.setTextIsSelectable(true);
        settings.addView(address);
        reconnect = button("重新连接", this::reconnect);
        settings.addView(reconnect, new LinearLayout.LayoutParams(-2, -2));
        editPairing = button("", () -> {
            showPairing(!editingPairing);
            if (editingPairing) revealPairing();
        });
        settings.addView(editPairing, new LinearLayout.LayoutParams(-2, -2));

        pairing = column();
        settings.addView(pairing);
        pairing.addView(label("在 Mac 的 PadPaste 菜单复制配对码，或导出配对文件。", 14, MUTED));
        pairingCode = new EditText(activity);
        pairingCode.setId(R.id.sync_pairing_code);
        pairingCode.setHint("粘贴配对码");
        pairingCode.setSingleLine(true);
        pairingCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pairingCode.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        pairing.addView(pairingCode, new LinearLayout.LayoutParams(-1, -2));
        pairing.addView(button("保存配对", () -> pair(pairingCode.getText().toString())),
                new LinearLayout.LayoutParams(-2, -2));
        pairing.addView(button("导入配对文件", this::pickPairing), new LinearLayout.LayoutParams(-2, -2));
        showSettings(expanded, false);
        showPairing(editingPairing);
    }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("expanded", expanded);
        state.putBoolean("editingPairing", editingPairing);
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

        boolean viaBluetooth = connected && link.connectedViaBluetooth();
        String status = paused ? "同步已暂停" : !paired ? "尚未配对 Mac"
                : connected ? "已连接 Mac" : "等待 Mac 连接";
        String channel;
        if (paused) channel = "收发均已关闭";
        else if (!paired) channel = "配对后即可同步截图";
        else if (!connected) channel = "正在寻找已配对的 Mac";
        else if (viaBluetooth) channel = "通过蓝牙";
        else channel = link.duplex() ? "通过 Wi-Fi" : "通过 Wi-Fi · 旧版 Mac";
        if (!channel.contentEquals(route.getText()) || !status.contentEquals(connection.getText())) {
            if (route.isLaidOut() && ValueAnimator.areAnimatorsEnabled()) {
                route.animate().cancel();
                route.setAlpha(.45f);
                route.setTranslationY(dp(2));
                route.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(EASE).start();
            }
            connection.setText(status);
            route.setText(channel);
            dot.setBackground(rounded(connected && paired && !paused ? GREEN : OFF, 4));
        }

        boolean needsReconnect = paired && !paused && !connected;
        nextStep.setText(paired ? "重新连接" : "配对 Mac");
        nextStep.setVisibility(!paused && (!paired || !connected) ? View.VISIBLE : View.GONE);
        boolean canConnect = receiving || (sending && allowed);
        nextStep.setEnabled(!paired || canConnect);
        grant.setVisibility(sending && !allowed ? View.VISIBLE : View.GONE);
        if (bluetoothStatus != null) {
            bluetoothStatus.setText(viaBluetooth ? "已通过蓝牙连接，Wi-Fi 恢复后优先使用 Wi-Fi。"
                    : !link.bluetoothEnabled() ? "Wi-Fi 不可用时可通过蓝牙连接。Mac 上也需开启蓝牙备用。"
                    : !link.bluetoothAllowed() ? "需要附近设备权限"
                    : !link.bluetoothRadioOn() ? "系统蓝牙已关闭" : "Wi-Fi 不可用时尝试蓝牙连接。");
        }
        detail.setText("接收题图 · " + (!receiving ? "已关闭" : !paired ? "等待配对" : "草稿纸在前台时接收")
                + "\n发送截图 · " + (!sending ? "已关闭" : !allowed ? "需要文件访问权限"
                    : !paired ? "等待配对" : SyncService.monitorStatus()));
        String endpoint = link.connectedAddress();
        address.setText(endpoint.isEmpty() ? "" : "连接地址 · " + endpoint);
        address.setVisibility(!paused && !endpoint.isEmpty() ? View.VISIBLE : View.GONE);
        reconnect.setVisibility(paired && !needsReconnect ? View.VISIBLE : View.GONE);
        reconnect.setEnabled(canConnect);
        editPairing.setText(editingPairing ? "收起配对" : paired ? "更换配对" : "配对 Mac");
    }

    private void reconnect() {
        link.reconnect();
        SyncService.resumeMonitoring(activity);
        render();
    }

    private void showSettings(boolean show, boolean animate) {
        expanded = show;
        boolean motion = animate && content.isLaidOut() && ValueAnimator.areAnimatorsEnabled();
        if (motion && (settings.getVisibility() == View.VISIBLE) != show) {
            TransitionManager.beginDelayedTransition(content,
                    new AutoTransition().setDuration(135).setInterpolator(EASE));
        }
        if (!show && pairingCode.hasFocus()) hideKeyboard();
        settings.setVisibility(show ? View.VISIBLE : View.GONE);
        chevron.animate().cancel();
        if (motion) chevron.animate().rotation(show ? 90 : 0).setDuration(180).setInterpolator(EASE).start();
        else chevron.setRotation(show ? 90 : 0);
        settingsToggle.setContentDescription(show ? "连接设置，已展开" : "连接设置，已收起");
    }

    private void showPairing(boolean show) {
        editingPairing = show;
        if (!show && pairingCode.hasFocus()) hideKeyboard();
        pairing.setVisibility(show ? View.VISIBLE : View.GONE);
        editPairing.setText(show ? "收起配对" : link.paired() ? "更换配对" : "配对 Mac");
    }

    private void revealPairing() {
        pairing.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                pairing.getViewTreeObserver().removeOnPreDrawListener(this);
                if (expanded && editingPairing) {
                    Rect bounds = new Rect();
                    pairing.getDrawingRect(bounds);
                    pairing.requestRectangleOnScreen(bounds, !ValueAnimator.areAnimatorsEnabled());
                }
                return true;
            }
        });
    }

    private void hideKeyboard() {
        activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(pairingCode.getWindowToken(), 0);
        content.requestFocus();
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

    private void pair(String code) {
        try {
            link.pair(code);
        } catch (Exception error) {
            message("无法读取配对码");
            return;
        }
        pairingCode.setText("");
        showPairing(false);
        showSettings(false, true);
        scroll.scrollTo(0, 0);
        SyncService.resumeMonitoring(activity);
        render();
        message("配对已保存");
    }

    private void message(String text) { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show(); }

    @android.annotation.SuppressLint("UseSwitchCompatOrMaterialCode")
    private Switch direction(LinearLayout group, String text, int icon) {
        Switch toggle = new Switch(activity);
        toggle.setText(text);
        toggle.setTextSize(icon == 0 ? 14 : 16);
        toggle.setTextColor(INK);
        toggle.setMinHeight(dp(icon == 0 ? 48 : 60));
        toggle.setSwitchPadding(dp(12));
        toggle.setSwitchMinWidth(dp(40));
        toggle.setSplitTrack(false);
        toggle.setShowText(false);
        GradientDrawable track = rounded(OFF, 12);
        track.setSize(dp(40), dp(22));
        toggle.setTrackDrawable(track);
        toggle.setTrackTintList(new ColorStateList(new int[][]{
                {android.R.attr.state_checked}, {}}, new int[]{GREEN, OFF}));
        GradientDrawable thumb = rounded(0xFFFFFFFF, 9);
        thumb.setSize(dp(18), dp(18));
        toggle.setThumbDrawable(new InsetDrawable(thumb, dp(2)));
        toggle.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        if (icon != 0) {
            toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            toggle.setCompoundDrawablePadding(dp(12));
        }
        group.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        return toggle;
    }

    private void divider(LinearLayout parent) {
        View line = new View(activity);
        line.setBackgroundColor(LINE);
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private ImageView icon(int resource) {
        ImageView view = new ImageView(activity);
        view.setImageResource(resource);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(activity);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
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
        view.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{MUTED, GREEN}));
        view.setMinHeight(dp(48));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
