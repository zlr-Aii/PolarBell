package com.suto.polarbell;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.suto.polarbell.media.AudioItem;
import com.suto.polarbell.media.AudioLibrary;
import com.suto.polarbell.plan.PhonePlan;
import com.suto.polarbell.plan.PhonePlanEngine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 编辑手机独立计划：阶段（名称/分钟/提前秒数/音乐）、完成提示音、音量。 */
public class PlanActivity extends Activity {

    private static final int GREEN = 0xFF16A34A;
    private static final int GREEN_LIGHT = 0xFFDCFCE7;
    private static final int BORDER = 0xFFE5E7EB;
    private static final int TEXT = 0xFF111827;
    private static final int MUTED = 0xFF6B7280;
    private static final int DANGER = 0xFFDC2626;

    private PhonePlan plan;
    private AudioLibrary library;
    private LinearLayout stageList;
    private TextView finishView;
    private EditText nameInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        plan = PhonePlan.load(this);
        library = new AudioLibrary(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F7F6);
        root.setPadding(dp(20), dp(24), dp(20), dp(12));

        TextView title = text("编辑手机计划", 22, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView hint = text("不连电脑也能用：在主界面点“开始”，熄屏照常计时。"
                + "音乐在阶段结束前“提前秒数”开始，连续播放文件夹，直到下一段音乐开始或计划停止。", 12, MUTED);
        hint.setPadding(0, dp(4), 0, dp(8));
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        content.addView(label("计划名称"));
        nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setText(plan.name);
        content.addView(nameInput);

        content.addView(label("阶段"));
        stageList = new LinearLayout(this);
        stageList.setOrientation(LinearLayout.VERTICAL);
        content.addView(stageList);

        Button add = new Button(this);
        add.setText("＋ 添加阶段");
        add.setOnClickListener(v -> {
            PhonePlan.Stage s = new PhonePlan.Stage();
            s.name = "阶段 " + (plan.stages.size() + 1);
            plan.stages.add(s);
            editStage(plan.stages.size() - 1, true);
        });
        content.addView(add);

        content.addView(label("完成提示音"));
        finishView = text("", 14, TEXT);
        finishView.setPadding(dp(14), dp(12), dp(14), dp(12));
        finishView.setBackground(rounded(0xFFFFFFFF, BORDER, 12));
        finishView.setOnClickListener(v -> chooseFinishSound());
        content.addView(finishView);

        content.addView(label("音量"));
        TextView volumeText = text(plan.volume + "%", 13, MUTED);
        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(plan.volume);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                plan.volume = progress;
                volumeText.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        content.addView(volume);
        content.addView(volumeText);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(8), 0, 0);
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(v -> finish());
        Button save = new Button(this);
        save.setText("保存");
        save.setTextColor(0xFFFFFFFF);
        save.setBackground(rounded(GREEN, 0, 10));
        save.setOnClickListener(v -> save());
        buttons.addView(cancel);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.leftMargin = dp(8);
        buttons.addView(save, saveParams);
        root.addView(buttons);

        setContentView(root);
        refresh();
    }

    private void refresh() {
        stageList.removeAllViews();
        if (plan.stages.isEmpty()) {
            stageList.addView(text("还没有阶段，点“添加阶段”", 13, MUTED));
        }
        for (int i = 0; i < plan.stages.size(); i++) {
            stageList.addView(stageRow(i));
        }
        finishView.setText(finishLabel());
    }

    private View stageRow(int index) {
        PhonePlan.Stage s = plan.stages.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackground(rounded(0xFFFFFFFF, BORDER, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        row.setLayoutParams(params);

        TextView name = text((index + 1) + ". " + s.name + " · " + s.minutes + " 分钟", 15, TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name);
        row.addView(text(musicLabel(s), 12, MUTED));
        row.setOnClickListener(v -> stageMenu(index));
        return row;
    }

    private static String musicLabel(PhonePlan.Stage s) {
        switch (s.music) {
            case PhonePlan.MUSIC_FOLDER:
                return "提前 " + s.leadSeconds + " 秒播放文件夹“" + PhonePlanEngine.folderLabel(s.folder)
                        + "”（" + (s.random ? "随机" : "顺序") + "，连续）";
            case PhonePlan.MUSIC_STOP:
                return "提前 " + s.leadSeconds + " 秒停止音乐";
            default:
                return "音乐不变（上一段音乐继续）";
        }
    }

    private void stageMenu(int index) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("编辑");
        actions.add(() -> editStage(index, false));
        if (index > 0) {
            labels.add("上移");
            actions.add(() -> {
                plan.stages.add(index - 1, plan.stages.remove(index));
                refresh();
            });
        }
        if (index < plan.stages.size() - 1) {
            labels.add("下移");
            actions.add(() -> {
                plan.stages.add(index + 1, plan.stages.remove(index));
                refresh();
            });
        }
        labels.add("复制");
        actions.add(() -> {
            plan.stages.add(index + 1, plan.stages.get(index).copy());
            refresh();
        });
        labels.add("删除");
        actions.add(() -> {
            plan.stages.remove(index);
            refresh();
        });
        new AlertDialog.Builder(this)
                .setTitle(plan.stages.get(index).name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("取消", null)
                .show();
    }

    private void editStage(int index, boolean creating) {
        PhonePlan.Stage s = plan.stages.get(index).copy();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        box.addView(label("名称"));
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(s.name);
        box.addView(name);

        box.addView(label("分钟"));
        EditText minutes = number(s.minutes);
        box.addView(minutes);

        box.addView(label("音乐"));
        String[] musicNames = {"不变（上一段音乐继续）", "播放文件夹（连续）", "停止音乐"};
        int[] music = {s.music};
        TextView musicView = choice(musicNames[s.music]);
        box.addView(musicView);

        TextView folderLabel = label("文件夹");
        String[] folder = {s.folder};
        TextView folderView = choice(PhonePlanEngine.folderLabel(s.folder) + folderCount(s.folder));
        TextView orderLabel = label("顺序");
        boolean[] random = {s.random};
        TextView orderView = choice(s.random ? "随机" : "顺序");
        TextView leadLabel = label("提前秒数（阶段结束前多少秒开始）");
        EditText lead = number(s.leadSeconds);
        box.addView(folderLabel);
        box.addView(folderView);
        box.addView(orderLabel);
        box.addView(orderView);
        box.addView(leadLabel);
        box.addView(lead);

        Runnable updateVisibility = () -> {
            boolean isFolder = music[0] == PhonePlan.MUSIC_FOLDER;
            boolean hasLead = music[0] != PhonePlan.MUSIC_NONE;
            for (View v : new View[]{folderLabel, folderView, orderLabel, orderView}) {
                v.setVisibility(isFolder ? View.VISIBLE : View.GONE);
            }
            leadLabel.setVisibility(hasLead ? View.VISIBLE : View.GONE);
            lead.setVisibility(hasLead ? View.VISIBLE : View.GONE);
        };
        updateVisibility.run();

        musicView.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setItems(musicNames, (d, which) -> {
                    music[0] = which;
                    musicView.setText(musicNames[which]);
                    updateVisibility.run();
                }).show());
        folderView.setOnClickListener(v -> {
            List<String> folders = new ArrayList<>();
            folders.add("");
            folders.addAll(library.getFolders());
            String[] names = new String[folders.size()];
            for (int i = 0; i < names.length; i++) {
                names[i] = PhonePlanEngine.folderLabel(folders.get(i)) + folderCount(folders.get(i));
            }
            new AlertDialog.Builder(this)
                    .setItems(names, (d, which) -> {
                        folder[0] = folders.get(which);
                        folderView.setText(names[which]);
                    }).show();
        });
        orderView.setOnClickListener(v -> {
            random[0] = !random[0];
            orderView.setText(random[0] ? "随机" : "顺序");
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(creating ? "添加阶段" : "编辑阶段")
                .setView(scroll)
                .setPositiveButton("确定", (d, which) -> {
                    s.name = name.getText().toString().trim();
                    s.minutes = parse(minutes, s.minutes);
                    s.leadSeconds = parse(lead, s.leadSeconds);
                    s.music = music[0];
                    s.folder = folder[0];
                    s.random = random[0];
                    plan.stages.set(index, s);
                    plan.normalize();
                    refresh();
                })
                .setNegativeButton("取消", (d, which) -> {
                    if (creating) {
                        plan.stages.remove(index);
                    }
                    refresh();
                })
                .setOnCancelListener(d -> {
                    if (creating) {
                        plan.stages.remove(index);
                    }
                    refresh();
                })
                .show();
    }

    private String folderCount(String folder) {
        int count = 0;
        for (AudioItem item : library.getAudioItems()) {
            if (folder.equals(item.getFolder())) {
                count++;
            }
        }
        return "（" + count + " 首）";
    }

    private String finishLabel() {
        String sound = "内置提示音";
        if (!plan.finishAudioId.isEmpty()) {
            sound = "（音频已删除，改用内置提示音）";
            for (AudioItem item : library.getAudioItems()) {
                if (item.getAudioId().equals(plan.finishAudioId)) {
                    sound = item.getName();
                }
            }
        }
        String length = plan.finishSeconds > 0 ? "最多 " + plan.finishSeconds + " 秒" : "播完为止";
        if (plan.finishAudioId.isEmpty() && plan.finishSeconds <= 0) {
            length = "最多 15 秒";
        }
        return sound + " · " + length + "　（点击修改）";
    }

    private void chooseFinishSound() {
        List<AudioItem> items = library.getAudioItems();
        String[] names = new String[items.size() + 1];
        names[0] = "内置提示音";
        for (int i = 0; i < items.size(); i++) {
            AudioItem item = items.get(i);
            names[i + 1] = (item.getFolder().isEmpty() ? "" : item.getFolder() + " / ") + item.getName();
        }
        new AlertDialog.Builder(this)
                .setTitle("完成提示音")
                .setItems(names, (d, which) -> {
                    plan.finishAudioId = which == 0 ? "" : items.get(which - 1).getAudioId();
                    chooseFinishLength();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseFinishLength() {
        String[] names = {"最多 5 秒", "最多 10 秒", "最多 15 秒", "最多 30 秒", "最多 60 秒", "播完为止"};
        int[] values = {5, 10, 15, 30, 60, 0};
        new AlertDialog.Builder(this)
                .setTitle("播放多久")
                .setItems(names, (d, which) -> {
                    plan.finishSeconds = values[which];
                    refresh();
                })
                .setOnCancelListener(d -> refresh())
                .show();
    }

    private void save() {
        plan.name = nameInput.getText().toString();
        if (plan.stages.isEmpty()) {
            Toast.makeText(this, "至少需要一个阶段", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            plan.save(this);
            Toast.makeText(this, "已保存，下次开始时生效", Toast.LENGTH_SHORT).show();
            finish();
        } catch (IOException e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 工具 ====================

    private EditText number(int value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(value));
        return input;
    }

    private static int parse(EditText input, int fallback) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private TextView choice(String value) {
        TextView view = text(value + "  ▾", 15, TEXT);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(rounded(0xFFFFFFFF, BORDER, 10));
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 13, MUTED);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

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
}
