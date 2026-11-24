
package com.suto.polarbell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.suto.polarbell.audio.AudioPlayer;
import com.suto.polarbell.media.AudioItem;
import com.suto.polarbell.media.AudioLibrary;
import com.suto.polarbell.service.PolarBellService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQUEST_IMPORT_AUDIO = 1001;

    private static final int GREEN = 0xFF16A34A;
    private static final int GREEN_LIGHT = 0xFFDCFCE7;
    private static final int AMBER = 0xFFB45309;
    private static final int AMBER_LIGHT = 0xFFFEF3C7;
    private static final int BORDER = 0xFFE5E7EB;
    private static final int TEXT = 0xFF111827;
    private static final int MUTED = 0xFF6B7280;

    private TextView tvStatus;
    private TextView tvDescription;
    private TextView tvLibraryInfo;
    private TextView tvCache;
    private TextView tvNowPlaying;
    private View nowPlayingCard;
    private LinearLayout audioListContainer;
    private LinearLayout folderTabs;
    /** 当前文件夹标签：null = 全部，"" = 未分类，其余为文件夹名。 */
    private String selectedFolder;

    private boolean receiverRegistered = false;
    private AudioLibrary audioLibrary;
    private AudioPlayer previewPlayer;
    private String playingAudioId;

    // 接收 PolarBellService 发来的连接状态
    private final BroadcastReceiver connectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PolarBellService.ACTION_LIBRARY_CHANGED.equals(intent.getAction())) {
                refreshAudioList();
                return;
            }
            if (PolarBellService.ACTION_PLAN_CHANGED.equals(intent.getAction())) {
                refreshPlanCard();
                return;
            }
            boolean connected = intent.getBooleanExtra(
                    PolarBellService.EXTRA_CONNECTED,
                    false
            );
            Log.i("MainActivity", "电脑连接状态：" + connected);
            if (connected) {
                showConnected();
            } else {
                showDisconnected();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvDescription = findViewById(R.id.tvDescription);
        tvLibraryInfo = findViewById(R.id.tvLibraryInfo);
        tvCache = findViewById(R.id.tvCache);
        tvNowPlaying = findViewById(R.id.tvNowPlaying);
        nowPlayingCard = findViewById(R.id.nowPlayingCard);
        audioListContainer = findViewById(R.id.audioListContainer);
        folderTabs = findViewById(R.id.folderTabs);
        planCard = findViewById(R.id.planCard);

        nowPlayingCard.setBackground(rounded(GREEN_LIGHT, 0, 12));
        planCard.setBackground(rounded(0xFFFFFFFF, BORDER, 12));

        audioLibrary = new AudioLibrary(this);
        previewPlayer = new AudioPlayer(this);

        findViewById(R.id.btnImportAudio).setOnClickListener(v -> openAudioPicker());
        findViewById(R.id.btnStop).setOnClickListener(v -> stopPreview());
        findViewById(R.id.btnClearCache).setOnClickListener(v -> confirmClearCache());

        ((TextView) findViewById(R.id.tvDevice)).setText("PolarBell " + versionName());

        refreshAudioList();
        refreshCacheInfo();
        showDisconnected();

        // 先注册广播接收器，再启动服务，避免错过初始状态
        registerConnectionReceiver();

        // 启动 PolarBell 前台服务
        Intent serviceIntent = new Intent(this, PolarBellService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCacheInfo();
        refreshPlanCard();
        uiHandler.post(planTicker);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(planTicker);
        super.onPause();
    }

    // ==================== 手机计划 ====================

    private LinearLayout planCard;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private TextView planRemainingView;

    /** 界面可见时每秒刷新剩余时间。 */
    private final Runnable planTicker = new Runnable() {
        @Override
        public void run() {
            com.suto.polarbell.plan.PhonePlanEngine.Snapshot s = PolarBellService.planSnapshot();
            if (planRemainingView != null && s != null && s.running) {
                planRemainingView.setText(formatDuration(s.remainingMs + 999));
            }
            uiHandler.postDelayed(this, 1000);
        }
    };

    private void refreshPlanCard() {
        if (planCard == null) {
            return;
        }
        planCard.removeAllViews();
        planRemainingView = null;
        com.suto.polarbell.plan.PhonePlanEngine.Snapshot s = PolarBellService.planSnapshot();
        com.suto.polarbell.plan.PhonePlan plan = com.suto.polarbell.plan.PhonePlan.load(this);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("手机计划 · " + plan.name);
        title.setTextSize(16);
        title.setTextColor(TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView guide = new TextView(this);
        guide.setText("后台设置");
        guide.setTextSize(12);
        guide.setTextColor(GREEN);
        guide.setPadding(dp(8), dp(4), 0, dp(4));
        guide.setOnClickListener(v -> showBackgroundGuide());
        header.addView(guide);
        planCard.addView(header);

        boolean running = s != null && s.running;
        if (running) {
            TextView stage = new TextView(this);
            stage.setText(s.stageName + "（" + (s.stageIndex + 1) + "/" + s.stageCount + "）"
                    + (s.paused ? " · 已暂停" : ""));
            stage.setTextSize(14);
            stage.setTextColor(TEXT);
            stage.setPadding(0, dp(6), 0, 0);
            planCard.addView(stage);

            planRemainingView = new TextView(this);
            planRemainingView.setText(formatDuration(s.remainingMs + 999));
            planRemainingView.setTextSize(36);
            planRemainingView.setTextColor(GREEN);
            planRemainingView.setTypeface(null, android.graphics.Typeface.BOLD);
            planCard.addView(planRemainingView);

            if (!s.nowPlaying.isEmpty()) {
                TextView music = new TextView(this);
                music.setText("♪ " + s.nowPlaying);
                music.setTextSize(12);
                music.setTextColor(MUTED);
                music.setSingleLine(true);
                music.setEllipsize(TextUtils.TruncateAt.END);
                planCard.addView(music);
            }
        } else {
            int total = 0;
            for (com.suto.polarbell.plan.PhonePlan.Stage stage : plan.stages) {
                total += stage.minutes;
            }
            TextView info = new TextView(this);
            info.setText(plan.stages.size() + " 个阶段 · 共 " + total + " 分钟"
                    + (s != null && !s.lastMessage.isEmpty() ? "\n" + s.lastMessage : ""));
            info.setTextSize(13);
            info.setTextColor(MUTED);
            info.setPadding(0, dp(6), 0, 0);
            planCard.addView(info);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(6), 0, 0);
        if (running) {
            buttons.addView(planButton(s.paused ? "继续" : "暂停", PolarBellService.ACTION_PLAN_PAUSE));
            buttons.addView(planButton("跳过", PolarBellService.ACTION_PLAN_SKIP));
            buttons.addView(planButton("停止", PolarBellService.ACTION_PLAN_STOP));
        } else {
            Button start = planButton("开始", PolarBellService.ACTION_PLAN_START);
            start.setOnClickListener(v -> {
                if (PolarBellService.isComputerConnected()) {
                    tvDescription.setText("已连接电脑时由电脑端 StudyTimer 计时，断开后再开始手机计划");
                    return;
                }
                PolarBellService.send(this, PolarBellService.ACTION_PLAN_START);
            });
            buttons.addView(start);
            Button edit = new Button(this);
            edit.setText("编辑计划");
            edit.setOnClickListener(v -> startActivity(new Intent(this, PlanActivity.class)));
            buttons.addView(edit);
        }
        planCard.addView(buttons);
    }

    private Button planButton(String text, String action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> PolarBellService.send(this, action));
        return button;
    }

    /** ColorOS（OPPO）需要手动允许自启动、后台运行，并关闭电池优化，否则熄屏后计划可能被系统停止。 */
    private void showBackgroundGuide() {
        String message = "为了熄屏后计划照常进行（OPPO / ColorOS）：\n\n"
                + "1. 自启动：设置 → 电池 → 应用耗电管理（或“手机管家 → 权限隐私 → 自启动管理”）→ PolarBell → 允许自启动\n\n"
                + "2. 后台运行：同一页面打开“允许后台运行”，关闭“自动优化”\n\n"
                + "3. 电池优化：点下面“电池优化”，选择“不优化 / 允许”\n\n"
                + "4. 最近任务里下拉 PolarBell 卡片加锁，防止一键清理时被关闭\n\n"
                + "5. 通知：允许 PolarBell 显示通知，可在通知栏暂停 / 跳过";
        new AlertDialog.Builder(this)
                .setTitle("后台运行设置")
                .setMessage(message)
                .setPositiveButton("电池优化", (d, w) -> openBatteryOptimization())
                .setNeutralButton("应用设置", (d, w) -> openAppDetails())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException ignored) {
            }
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        openAppDetails();
    }

    private void openAppDetails() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            tvDescription.setText("无法打开设置，请手动在系统设置中找到 PolarBell");
        }
    }

    private void registerConnectionReceiver() {
        IntentFilter filter = new IntentFilter(
                PolarBellService.ACTION_CONNECTION_CHANGED
        );
        filter.addAction(PolarBellService.ACTION_LIBRARY_CHANGED);
        filter.addAction(PolarBellService.ACTION_PLAN_CHANGED);
        ContextCompat.registerReceiver(
                this,
                connectionReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        receiverRegistered = true;
    }

    private void showDisconnected() {
        tvStatus.setText("未连接电脑");
        tvStatus.setTextColor(AMBER);
        tvStatus.setBackground(rounded(AMBER_LIGHT, 0, 999));
        tvDescription.setText("PolarBell 已启动，用 USB 连接电脑并打开 StudyTimer");
    }

    private void showConnected() {
        tvStatus.setText("已连接电脑");
        tvStatus.setTextColor(GREEN);
        tvStatus.setBackground(rounded(GREEN_LIGHT, 0, 999));
        tvDescription.setText("USB 通信正常，等待电脑的播放指令");
    }

    // ==================== 导入 ====================

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        try {
            startActivityForResult(intent, REQUEST_IMPORT_AUDIO);
        } catch (ActivityNotFoundException e) {
            tvDescription.setText("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_IMPORT_AUDIO || resultCode != RESULT_OK || data == null) {
            return;
        }

        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }

        tvDescription.setText("正在导入 " + uris.size() + " 个音频…");
        String targetFolder = selectedFolder == null ? "" : selectedFolder;

        // 复制文件可能较慢，放到后台线程。
        new Thread(() -> {
            int ok = 0;
            List<String> failed = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    audioLibrary.importAudio(this, uri, targetFolder);
                    ok++;
                } catch (IOException e) {
                    failed.add(e.getMessage());
                }
            }
            int imported = ok;
            runOnUiThread(() -> {
                String text = "已导入 " + imported + " 个音频";
                if (!failed.isEmpty()) {
                    text += "，" + failed.size() + " 个失败：" + failed.get(0);
                }
                tvDescription.setText(text);
                refreshAudioList();
            });
        }, "audio-import").start();
    }

    // ==================== 列表 ====================

    private void refreshAudioList() {
        audioListContainer.removeAllViews();

        List<AudioItem> all = audioLibrary.getAudioItems();
        List<String> folders = audioLibrary.getFolders();
        if (selectedFolder != null && !selectedFolder.isEmpty() && !folders.contains(selectedFolder)) {
            selectedFolder = null;
        }
        refreshFolderTabs(all, folders);

        List<AudioItem> items = new ArrayList<>();
        for (AudioItem item : all) {
            if (selectedFolder == null || selectedFolder.equals(item.getFolder())) {
                items.add(item);
            }
        }

        tvLibraryInfo.setText(all.isEmpty()
                ? "电脑端“手机音频”只能选择这里的音频"
                : all.size() + " 个音频 · " + formatSize(audioLibrary.getTotalBytes())
                + " · 点音频可重命名、移动或删除，长按文件夹可改名或删除");

        if (items.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(all.isEmpty()
                    ? "还没有音频，点击“＋ 导入”添加（可多选）"
                    : "这个文件夹还没有音频，导入时会放到当前文件夹");
            emptyView.setTextSize(14);
            emptyView.setTextColor(MUTED);
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setPadding(16, 64, 16, 64);
            audioListContainer.addView(emptyView);
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            audioListContainer.addView(createRow(items.get(i), i, items.size()));
        }
    }

    private View createRow(AudioItem item, int index, int total) {
        boolean playing = item.getAudioId().equals(playingAudioId);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(8), dp(10));
        row.setBackground(rounded(playing ? GREEN_LIGHT : 0xFFFFFFFF, playing ? GREEN : BORDER, 12));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.bottomMargin = dp(8);
        row.setLayoutParams(rowParams);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView nameView = new TextView(this);
        nameView.setText(item.getName());
        nameView.setTextSize(15);
        nameView.setTextColor(TEXT);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);

        TextView metaView = new TextView(this);
        metaView.setText((index + 1) + " · " + formatDuration(item.getDuration())
                + " · " + formatSize(item.getFileSize()));
        metaView.setTextSize(12);
        metaView.setTextColor(MUTED);

        texts.addView(nameView);
        texts.addView(metaView);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button playButton = new Button(this);
        playButton.setText(playing ? "停止" : "试听");
        playButton.setOnClickListener(v -> {
            if (playing) {
                stopPreview();
            } else {
                playPreview(item);
            }
        });
        row.addView(playButton);

        row.setOnLongClickListener(v -> {
            showItemMenu(item, index, total);
            return true;
        });
        row.setOnClickListener(v -> showItemMenu(item, index, total));

        return row;
    }

    private void showItemMenu(AudioItem item, int index, int total) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add("重命名");
        actions.add(() -> showRenameDialog(item));
        labels.add("移动到文件夹…");
        actions.add(() -> showMoveDialog(item));
        // 只在“全部”里调整顺序，避免和看不见的其他文件夹音频交换位置
        if (selectedFolder == null && index > 0) {
            labels.add("上移");
            actions.add(() -> move(item, -1));
        }
        if (selectedFolder == null && index < total - 1) {
            labels.add("下移");
            actions.add(() -> move(item, 1));
        }
        labels.add("删除");
        actions.add(() -> confirmDelete(item));

        new AlertDialog.Builder(this)
                .setTitle(item.getName())
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenameDialog(AudioItem item) {
        EditText input = new EditText(this);
        input.setText(item.getName());
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);

        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(box)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        audioLibrary.rename(item.getAudioId(), input.getText().toString());
                        refreshAudioList();
                    } catch (IOException e) {
                        tvDescription.setText("重命名失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 文件夹 ====================

    private void refreshFolderTabs(List<AudioItem> all, List<String> folders) {
        folderTabs.removeAllViews();
        int uncategorized = 0;
        for (AudioItem item : all) {
            if (item.getFolder().isEmpty()) {
                uncategorized++;
            }
        }
        folderTabs.addView(createTab("全部 " + all.size(), selectedFolder == null,
                v -> selectFolder(null), null));
        folderTabs.addView(createTab("未分类 " + uncategorized, "".equals(selectedFolder),
                v -> selectFolder(""), null));
        for (String folder : folders) {
            int count = 0;
            for (AudioItem item : all) {
                if (folder.equals(item.getFolder())) {
                    count++;
                }
            }
            folderTabs.addView(createTab(folder + " " + count, folder.equals(selectedFolder),
                    v -> selectFolder(folder),
                    v -> {
                        showFolderMenu(folder);
                        return true;
                    }));
        }
        folderTabs.addView(createTab("＋ 文件夹", false, v -> showCreateFolderDialog(), null));
    }

    private TextView createTab(String text, boolean selected,
                               View.OnClickListener onClick, View.OnLongClickListener onLongClick) {
        TextView tab = new TextView(this);
        tab.setText(text);
        tab.setTextSize(13);
        tab.setTextColor(selected ? 0xFFFFFFFF : TEXT);
        tab.setPadding(dp(14), dp(6), dp(14), dp(6));
        tab.setBackground(rounded(selected ? GREEN : 0xFFFFFFFF, selected ? GREEN : BORDER, 999));
        tab.setOnClickListener(onClick);
        if (onLongClick != null) {
            tab.setOnLongClickListener(onLongClick);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        tab.setLayoutParams(params);
        return tab;
    }

    private void selectFolder(String folder) {
        selectedFolder = folder;
        refreshAudioList();
    }

    private void showFolderMenu(String folder) {
        new AlertDialog.Builder(this)
                .setTitle(folder)
                .setItems(new String[]{"重命名", "删除文件夹"}, (dialog, which) -> {
                    if (which == 0) {
                        showFolderNameDialog("重命名文件夹", folder, name -> {
                            audioLibrary.renameFolder(folder, name);
                            if (folder.equals(selectedFolder)) {
                                selectedFolder = name.trim();
                            }
                        });
                    } else {
                        confirmDeleteFolder(folder);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCreateFolderDialog() {
        showFolderNameDialog("新建文件夹", "", name -> {
            audioLibrary.createFolder(name);
            selectedFolder = name.trim();
        });
    }

    private interface FolderAction {
        void run(String name) throws IOException;
    }

    private void showFolderNameDialog(String title, String initial, FolderAction action) {
        EditText input = new EditText(this);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);

        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        action.run(input.getText().toString());
                        refreshAudioList();
                    } catch (IOException e) {
                        tvDescription.setText(title + "失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteFolder(String folder) {
        new AlertDialog.Builder(this)
                .setTitle("删除文件夹")
                .setMessage("删除“" + folder + "”？\n其中的音频不会删除，会回到“未分类”。")
                .setPositiveButton("删除", (dialog, which) -> {
                    try {
                        audioLibrary.deleteFolder(folder);
                        tvDescription.setText("已删除文件夹：" + folder);
                        refreshAudioList();
                    } catch (IOException e) {
                        tvDescription.setText("删除文件夹失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMoveDialog(AudioItem item) {
        List<String> folders = audioLibrary.getFolders();
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        labels.add("未分类");
        values.add("");
        for (String folder : folders) {
            labels.add(folder);
            values.add(folder);
        }
        int checked = Math.max(0, values.indexOf(item.getFolder()));
        new AlertDialog.Builder(this)
                .setTitle("移动到文件夹")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    dialog.dismiss();
                    try {
                        audioLibrary.moveToFolder(item.getAudioId(), values.get(which));
                        refreshAudioList();
                    } catch (IOException e) {
                        tvDescription.setText("移动失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void move(AudioItem item, int delta) {
        try {
            audioLibrary.move(item.getAudioId(), delta);
            refreshAudioList();
        } catch (IOException e) {
            tvDescription.setText("调整顺序失败：" + e.getMessage());
        }
    }

    private void confirmDelete(AudioItem item) {
        new AlertDialog.Builder(this)
                .setTitle("删除音频")
                .setMessage("确定删除“" + item.getName() + "”吗？\n"
                        + "电脑端引用了这个音频的计时阶段将无法在手机上播放。")
                .setPositiveButton("删除", (dialog, which) -> {
                    if (item.getAudioId().equals(playingAudioId)) {
                        stopPreview();
                    }
                    try {
                        audioLibrary.delete(item.getAudioId());
                        tvDescription.setText("已删除：" + item.getName());
                        refreshAudioList();
                    } catch (IOException e) {
                        tvDescription.setText("删除失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 试听 ====================

    private void playPreview(AudioItem item) {
        File file = new File(audioLibrary.getAudioDirectory(), item.getFileName());
        try {
            previewPlayer.play(file);
            playingAudioId = item.getAudioId();
            tvNowPlaying.setText("正在试听：" + item.getName());
            nowPlayingCard.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            tvDescription.setText("播放失败：" + e.getMessage());
        }
        refreshAudioList();
    }

    private void stopPreview() {
        previewPlayer.stop();
        playingAudioId = null;
        nowPlayingCard.setVisibility(View.GONE);
        refreshAudioList();
    }

    // ==================== 上传缓存 ====================

    /** 电脑上传的提醒视频声音缓存（与 SocketServer 使用同一目录）。 */
    private File uploadCacheDirectory() {
        return new File(getCacheDir(), "polarbell-upload");
    }

    private void refreshCacheInfo() {
        long total = 0;
        File[] files = uploadCacheDirectory().listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    total += file.length();
                }
            }
        }
        tvCache.setText("电脑上传缓存：" + formatSize(total));
    }

    private void confirmClearCache() {
        new AlertDialog.Builder(this)
                .setTitle("清空上传缓存")
                .setMessage("缓存是电脑发来的提醒视频声音，清空后下次提醒会重新上传。")
                .setPositiveButton("清空", (dialog, which) -> {
                    File[] files = uploadCacheDirectory().listFiles();
                    if (files != null) {
                        for (File file : files) {
                            //noinspection ResultOfMethodCallIgnored
                            file.delete();
                        }
                    }
                    refreshCacheInfo();
                    tvDescription.setText("上传缓存已清空");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 工具 ====================

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "v" + info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(connectionReceiver);
            receiverRegistered = false;
        }
        if (previewPlayer != null) {
            previewPlayer.release();
        }
        super.onDestroy();
    }
}
