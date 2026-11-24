package com.suto.polarbell.plan;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 手机独立计划（只有一个），保存在 files/phone-plan.json。
 * 每个阶段在结束前 leadSeconds 秒开始连续播放某个文件夹，直到下一段音乐开始或计划停止。
 */
public final class PhonePlan {

    public static final String FILE_NAME = "phone-plan.json";

    /** 阶段音乐：不改变（上一段音乐继续）/ 播放文件夹 / 停止音乐。 */
    public static final int MUSIC_NONE = 0;
    public static final int MUSIC_FOLDER = 1;
    public static final int MUSIC_STOP = 2;

    public String name = "手机计划";
    public List<Stage> stages = new ArrayList<>();
    /** 完成提示音：空 = 内置提示音，否则为音频库 audioId。 */
    public String finishAudioId = "";
    /** 完成提示音最多播放秒数，0 = 播完为止。 */
    public int finishSeconds = 15;
    /** 播放音量 0-100（相对手机媒体音量）。 */
    public int volume = 100;

    public static final class Stage {
        public String name = "专注";
        public int minutes = 25;
        public int leadSeconds = 10;
        public int music = MUSIC_NONE;
        /** music = MUSIC_FOLDER 时的文件夹；空 = 未分类。 */
        public String folder = "";
        public boolean random = true;

        public Stage copy() {
            Stage s = new Stage();
            s.name = name;
            s.minutes = minutes;
            s.leadSeconds = leadSeconds;
            s.music = music;
            s.folder = folder;
            s.random = random;
            return s;
        }

        public long durationMs() {
            return minutes * 60_000L;
        }
    }

    public static PhonePlan defaultPlan() {
        PhonePlan plan = new PhonePlan();
        Stage focus = new Stage();
        focus.name = "专注";
        focus.minutes = 25;
        Stage rest = new Stage();
        rest.name = "休息";
        rest.minutes = 5;
        plan.stages.add(focus);
        plan.stages.add(rest);
        return plan;
    }

    public PhonePlan copy() {
        PhonePlan p = new PhonePlan();
        p.name = name;
        for (Stage s : stages) {
            p.stages.add(s.copy());
        }
        p.finishAudioId = finishAudioId;
        p.finishSeconds = finishSeconds;
        p.volume = volume;
        return p;
    }

    public void normalize() {
        if (name == null || name.trim().isEmpty()) {
            name = "手机计划";
        }
        name = name.trim();
        if (finishAudioId == null) {
            finishAudioId = "";
        }
        finishSeconds = Math.max(0, Math.min(600, finishSeconds));
        volume = Math.max(0, Math.min(100, volume));
        List<Stage> clean = new ArrayList<>();
        for (Stage s : stages) {
            if (s == null) {
                continue;
            }
            s.name = s.name == null || s.name.trim().isEmpty() ? "阶段" : s.name.trim();
            s.minutes = Math.max(1, Math.min(600, s.minutes));
            s.leadSeconds = Math.max(0, Math.min(s.minutes * 60, s.leadSeconds));
            if (s.music < MUSIC_NONE || s.music > MUSIC_STOP) {
                s.music = MUSIC_NONE;
            }
            if (s.folder == null) {
                s.folder = "";
            }
            clean.add(s);
        }
        stages = clean;
    }

    // ==================== 读写 ====================

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /** 读取计划；文件缺失或损坏时返回默认计划。 */
    public static PhonePlan load(Context context) {
        File file = file(context);
        if (!file.isFile()) {
            return defaultPlan();
        }
        try {
            return fromJson(new JSONObject(readText(file)));
        } catch (IOException | JSONException e) {
            return defaultPlan();
        }
    }

    public void save(Context context) throws IOException {
        normalize();
        File file = file(context);
        File temp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(toJson().toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("保存计划失败", e);
        }
        if (!temp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!temp.renameTo(file)) {
                throw new IOException("保存计划失败");
            }
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("schemaVersion", 1);
        o.put("name", name);
        o.put("finishAudioId", finishAudioId);
        o.put("finishSeconds", finishSeconds);
        o.put("volume", volume);
        JSONArray array = new JSONArray();
        for (Stage s : stages) {
            JSONObject so = new JSONObject();
            so.put("name", s.name);
            so.put("minutes", s.minutes);
            so.put("leadSeconds", s.leadSeconds);
            so.put("music", s.music);
            so.put("folder", s.folder);
            so.put("random", s.random);
            array.put(so);
        }
        o.put("stages", array);
        return o;
    }

    public static PhonePlan fromJson(JSONObject o) {
        PhonePlan p = new PhonePlan();
        p.name = o.optString("name", p.name);
        p.finishAudioId = o.optString("finishAudioId", "");
        p.finishSeconds = o.optInt("finishSeconds", 15);
        p.volume = o.optInt("volume", 100);
        JSONArray array = o.optJSONArray("stages");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject so = array.optJSONObject(i);
                if (so == null) {
                    continue;
                }
                Stage s = new Stage();
                s.name = so.optString("name", s.name);
                s.minutes = so.optInt("minutes", s.minutes);
                s.leadSeconds = so.optInt("leadSeconds", s.leadSeconds);
                s.music = so.optInt("music", MUSIC_NONE);
                s.folder = so.optString("folder", "");
                s.random = so.optBoolean("random", true);
                p.stages.add(s);
            }
        }
        p.normalize();
        return p;
    }

    static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), 4 * 1024 * 1024)];
            int total = 0;
            int read;
            while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) > 0) {
                total += read;
            }
            return new String(buffer, 0, total, StandardCharsets.UTF_8);
        }
    }

    static void writeText(File file, String text) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!temp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!temp.renameTo(file)) {
                throw new IOException("写入失败：" + file.getName());
            }
        }
    }
}
