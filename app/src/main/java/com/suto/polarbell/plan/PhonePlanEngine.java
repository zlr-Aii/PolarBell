package com.suto.polarbell.plan;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.suto.polarbell.audio.AudioPlayer;
import com.suto.polarbell.media.AudioItem;
import com.suto.polarbell.media.AudioLibrary;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 手机独立计划的计时与播放。所有方法在主线程调用。
 *
 * 计时按墙上时间（System.currentTimeMillis）记录阶段结束时刻，熄屏或进程被杀后重启都能接上；
 * 状态保存在 files/phone-plan-state.json。
 */
public final class PhonePlanEngine {

    private static final String TAG = "PhonePlanEngine";
    private static final String STATE_FILE = "phone-plan-state.json";

    /** 状态变化（开始、暂停、换阶段、结束）时回调，用于刷新通知和定时唤醒。 */
    public interface Listener {
        void onStateChanged();
    }

    /** 界面显示用的快照。 */
    public static final class Snapshot {
        public final boolean running;
        public final boolean paused;
        public final String planName;
        public final String stageName;
        public final int stageIndex;
        public final int stageCount;
        public final long remainingMs;
        /** 运行且未暂停时阶段结束的墙上时间。 */
        public final long stageEndWallMs;
        public final String nowPlaying;
        public final String lastMessage;

        Snapshot(boolean running, boolean paused, String planName, String stageName, int stageIndex,
                 int stageCount, long remainingMs, long stageEndWallMs, String nowPlaying, String lastMessage) {
            this.running = running;
            this.paused = paused;
            this.planName = planName;
            this.stageName = stageName;
            this.stageIndex = stageIndex;
            this.stageCount = stageCount;
            this.remainingMs = remainingMs;
            this.stageEndWallMs = stageEndWallMs;
            this.nowPlaying = nowPlaying;
            this.lastMessage = lastMessage;
        }
    }

    private final Context context;
    private final AudioLibrary library;
    private final AudioPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Listener listener;

    private PhonePlan plan;
    private boolean running;
    private boolean paused;
    private int index;
    private long stageEndWallMs;
    private long pausedRemainingMs;
    private boolean musicTriggered;

    /** 当前连续播放的文件夹；null 表示没有在播文件夹。 */
    private String playingFolder;
    private boolean playingRandom;
    private String lastAudioId;
    private String nowPlaying = "";
    private String lastMessage = "";
    /** 每次换曲/停止递增，丢弃迟到的播完回调。 */
    private int playToken;

    private final Runnable tick = this::tick;

    public PhonePlanEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.library = new AudioLibrary(this.context);
        this.player = new AudioPlayer(this.context);
        this.listener = listener;
    }

    // ==================== 控制 ====================

    public void start() {
        stopPlayback();
        plan = PhonePlan.load(context);
        if (plan.stages.isEmpty()) {
            lastMessage = "计划里没有阶段，请先编辑计划";
            changed();
            return;
        }
        player.setVolume(plan.volume);
        running = true;
        paused = false;
        index = 0;
        beginStage(System.currentTimeMillis());
        lastMessage = "";
        changed();
        tick();
    }

    public void togglePause() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        if (paused) {
            paused = false;
            stageEndWallMs = now + pausedRemainingMs;
            player.setPaused(false);
        } else {
            paused = true;
            pausedRemainingMs = Math.max(0, stageEndWallMs - now);
            player.setPaused(true);
        }
        saveState();
        changed();
        tick();
    }

    public void skip() {
        if (!running) {
            return;
        }
        if (paused) {
            paused = false;
            player.setPaused(false);
        }
        advance(System.currentTimeMillis(), false);
        changed();
        tick();
    }

    /** 停止计划和音乐。reason 非空时作为提示保留（例如“电脑已连接”）。 */
    public void stop(String reason) {
        boolean wasRunning = running;
        running = false;
        paused = false;
        handler.removeCallbacks(tick);
        stopPlayback();
        player.stop();
        if (reason != null) {
            lastMessage = reason;
        }
        saveState();
        if (wasRunning || reason != null) {
            changed();
        }
    }

    /** 定时唤醒时立即检查一次。 */
    public void wake() {
        tick();
    }

    public boolean isRunning() {
        return running;
    }

    /** 下一次需要唤醒的墙上时间（音乐开始或阶段结束）；不需要时返回 0。 */
    public long nextWakeWallMs() {
        if (!running || paused) {
            return 0;
        }
        PhonePlan.Stage stage = plan.stages.get(index);
        long musicAt = stageEndWallMs - stage.leadSeconds * 1000L;
        if (!musicTriggered && stage.music != PhonePlan.MUSIC_NONE && musicAt > System.currentTimeMillis()) {
            return musicAt;
        }
        return stageEndWallMs;
    }

    public Snapshot snapshot() {
        if (!running) {
            return new Snapshot(false, false, plan == null ? PhonePlan.load(context).name : plan.name,
                    "", 0, 0, 0, 0, nowPlaying, lastMessage);
        }
        long remaining = paused ? pausedRemainingMs : Math.max(0, stageEndWallMs - System.currentTimeMillis());
        return new Snapshot(true, paused, plan.name, plan.stages.get(index).name, index, plan.stages.size(),
                remaining, paused ? 0 : stageEndWallMs, nowPlaying, lastMessage);
    }

    // ==================== 计时 ====================

    private void tick() {
        handler.removeCallbacks(tick);
        if (!running) {
            return;
        }
        if (!paused) {
            long now = System.currentTimeMillis();
            boolean changed = false;
            // 熄屏/被杀后可能一次跨过多个阶段：中间阶段不再补放音乐
            while (running && now >= stageEndWallMs) {
                boolean last = now - stageEndWallMs < 60_000;
                advance(stageEndWallMs, last);
                changed = true;
            }
            if (running) {
                PhonePlan.Stage stage = plan.stages.get(index);
                if (!musicTriggered && now >= stageEndWallMs - stage.leadSeconds * 1000L) {
                    musicTriggered = true;
                    applyStageMusic(stage);
                    saveState();
                    changed = true;
                }
            }
            if (changed) {
                changed();
            }
        }
        if (running) {
            handler.postDelayed(tick, 1000);
        }
    }

    private void beginStage(long startWallMs) {
        stageEndWallMs = startWallMs + plan.stages.get(index).durationMs();
        musicTriggered = false;
        saveState();
    }

    /** 当前阶段结束，进入下一阶段或完成。playSounds=false 时只推进不放声音（补算错过的阶段）。 */
    private void advance(long endWallMs, boolean playSounds) {
        index++;
        if (index >= plan.stages.size()) {
            running = false;
            handler.removeCallbacks(tick);
            stopPlayback();
            lastMessage = "“" + plan.name + "”已完成";
            if (playSounds) {
                playFinishSound();
            } else {
                player.stop();
            }
            saveState();
            return;
        }
        beginStage(endWallMs);
    }

    // ==================== 音乐 ====================

    private void applyStageMusic(PhonePlan.Stage stage) {
        if (stage.music == PhonePlan.MUSIC_STOP) {
            stopPlayback();
            player.stop();
        } else if (stage.music == PhonePlan.MUSIC_FOLDER) {
            playingFolder = stage.folder == null ? "" : stage.folder;
            playingRandom = stage.random;
            lastAudioId = null;
            playNextInFolder();
        }
    }

    private void playNextInFolder() {
        if (playingFolder == null) {
            return;
        }
        List<AudioItem> items = new ArrayList<>();
        for (AudioItem item : library.getAudioItems()) {
            if (playingFolder.equals(item.getFolder())) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            lastMessage = "文件夹“" + folderLabel(playingFolder) + "”里没有音频";
            playingFolder = null;
            nowPlaying = "";
            player.stop();
            changed();
            return;
        }
        AudioItem next = pick(items);
        lastAudioId = next.getAudioId();
        int token = ++playToken;
        try {
            player.play(new File(library.getAudioDirectory(), next.getFileName()), () ->
                    handler.post(() -> {
                        if (token == playToken && playingFolder != null) {
                            playNextInFolder();
                        }
                    }));
            if (paused) {
                player.setPaused(true);
            }
            nowPlaying = next.getName();
        } catch (IOException e) {
            Log.w(TAG, "播放失败：" + next.getName(), e);
            nowPlaying = "";
            // 坏文件：稍后试下一首，避免死循环
            handler.postDelayed(() -> {
                if (token == playToken && playingFolder != null) {
                    playNextInFolder();
                }
            }, 2000);
        }
        changed();
    }

    private AudioItem pick(List<AudioItem> items) {
        int last = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getAudioId().equals(lastAudioId)) {
                last = i;
            }
        }
        if (!playingRandom) {
            return items.get((last + 1) % items.size());
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        int i;
        do {
            i = random.nextInt(items.size());
        } while (i == last);
        return items.get(i);
    }

    private void stopPlayback() {
        playToken++;
        playingFolder = null;
        nowPlaying = "";
    }

    private void playFinishSound() {
        int token = ++playToken;
        boolean played = false;
        String audioId = plan.finishAudioId;
        if (audioId != null && !audioId.isEmpty()) {
            for (AudioItem item : library.getAudioItems()) {
                if (audioId.equals(item.getAudioId())) {
                    try {
                        player.play(new File(library.getAudioDirectory(), item.getFileName()));
                        played = true;
                    } catch (IOException e) {
                        Log.w(TAG, "完成提示音播放失败", e);
                    }
                    break;
                }
            }
        }
        if (!played) {
            player.play(1);
        }
        int seconds = plan.finishSeconds;
        // 内置提示音是整首歌，必须限时
        if (seconds <= 0 && !played) {
            seconds = 15;
        }
        if (seconds > 0) {
            handler.postDelayed(() -> {
                if (token == playToken && !running) {
                    player.stop();
                }
            }, seconds * 1000L);
        }
    }

    public static String folderLabel(String folder) {
        return folder == null || folder.isEmpty() ? "未分类" : folder;
    }

    private void changed() {
        if (listener != null) {
            listener.onStateChanged();
        }
    }

    // ==================== 状态保存 ====================

    private void saveState() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", running);
            o.put("paused", paused);
            o.put("index", index);
            o.put("stageEndWallMs", stageEndWallMs);
            o.put("pausedRemainingMs", pausedRemainingMs);
            o.put("musicTriggered", musicTriggered);
            if (running && plan != null) {
                o.put("plan", plan.toJson());
            }
            PhonePlan.writeText(new File(context.getFilesDir(), STATE_FILE), o.toString());
        } catch (IOException | JSONException e) {
            Log.w(TAG, "保存计划状态失败", e);
        }
    }

    /** 服务重启时恢复上次未结束的计划（错过的阶段直接跳过，不补放声音）。 */
    public void restore() {
        File file = new File(context.getFilesDir(), STATE_FILE);
        if (!file.isFile()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(PhonePlan.readText(file));
            if (!o.optBoolean("running") || o.optJSONObject("plan") == null) {
                return;
            }
            plan = PhonePlan.fromJson(o.getJSONObject("plan"));
            index = o.optInt("index");
            if (plan.stages.isEmpty() || index < 0 || index >= plan.stages.size()) {
                return;
            }
            running = true;
            paused = o.optBoolean("paused");
            stageEndWallMs = o.optLong("stageEndWallMs");
            pausedRemainingMs = o.optLong("pausedRemainingMs");
            // 音乐进程被杀后已经停了；如果还在本阶段的提前播放时间内，重新开始
            musicTriggered = false;
            player.setVolume(plan.volume);
            lastMessage = "已恢复上次的计划";
            Log.i(TAG, "恢复手机计划：阶段 " + index);
            changed();
            tick();
        } catch (IOException | JSONException e) {
            Log.w(TAG, "读取计划状态失败", e);
        }
    }

    public void release() {
        handler.removeCallbacks(tick);
        stopPlayback();
        player.release();
    }
}
