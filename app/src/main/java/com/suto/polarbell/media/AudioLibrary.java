
package com.suto.polarbell.media;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AudioLibrary {

    private final File audioDirectory;
    private final File indexFile;
    private final File folderFile;

    public AudioLibrary(Context context) {
        audioDirectory = new File(
                context.getExternalFilesDir(null),
                "audio"
        );

        if (!audioDirectory.exists()) {
            audioDirectory.mkdirs();
        }

        indexFile = new File(audioDirectory, "library.json");
        folderFile = new File(audioDirectory, "folders.json");
    }

    public File getAudioDirectory() {
        return audioDirectory;
    }

    public synchronized AudioItem importAudio(Context context, Uri uri)
            throws IOException {
        return importAudio(context, uri, "");
    }

    /** 导入到指定文件夹（空 = 未分类）。 */
    public synchronized AudioItem importAudio(Context context, Uri uri, String folder)
            throws IOException {

        String originalName = getDisplayName(context, uri);

        if (originalName == null || originalName.trim().isEmpty()) {
            originalName = "未命名音频";
        }

        String extension = ".audio";
        int dotIndex = originalName.lastIndexOf('.');

        if (dotIndex >= 0 && dotIndex < originalName.length() - 1) {
            extension = originalName.substring(dotIndex);
        }

        String audioId = "audio_" +
                UUID.randomUUID().toString().replace("-", "");

        String fileName = audioId + extension;
        File destination = new File(audioDirectory, fileName);

        try (
                InputStream input = context.getContentResolver()
                        .openInputStream(uri);
                FileOutputStream output = new FileOutputStream(destination)
        ) {
            if (input == null) {
                throw new IOException("无法读取所选音频文件");
            }

            byte[] buffer = new byte[8192];
            int length;

            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }

        } catch (IOException e) {
            destination.delete();
            throw e;
        }

        String displayTitle = originalName;
        if (dotIndex > 0) {
            displayTitle = originalName.substring(0, dotIndex);
        }

        AudioItem item = new AudioItem(
                audioId,
                displayTitle,
                fileName,
                getDuration(destination),
                destination.length(),
                existingFolder(folder)
        );

        try {
            saveItem(item);
        } catch (IOException e) {
            destination.delete();
            throw e;
        }

        return item;
    }

    /** 把电脑上传完成的文件复制进音频库；name 为原文件名（含扩展名）。 */
    public synchronized AudioItem importFile(File source, String name) throws IOException {
        return importFile(source, name, "");
    }

    /** 文件夹不存在时放到“未分类”。 */
    private String existingFolder(String folder) {
        String clean = folder == null ? "" : folder.trim();
        return !clean.isEmpty() && getFolders().contains(clean) ? clean : "";
    }

    public synchronized AudioItem importFile(File source, String name, String folder) throws IOException {
        String originalName = name == null || name.trim().isEmpty() ? "未命名音频" : name.trim();

        String extension = ".audio";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < originalName.length() - 1) {
            extension = originalName.substring(dotIndex);
        }

        String audioId = "audio_" + UUID.randomUUID().toString().replace("-", "");
        String fileName = audioId + extension;
        File destination = new File(audioDirectory, fileName);

        try (
                InputStream input = new java.io.FileInputStream(source);
                FileOutputStream output = new FileOutputStream(destination)
        ) {
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
        } catch (IOException e) {
            destination.delete();
            throw e;
        }

        String displayTitle = dotIndex > 0 ? originalName.substring(0, dotIndex) : originalName;
        AudioItem item = new AudioItem(audioId, displayTitle, fileName,
                getDuration(destination), destination.length(), existingFolder(folder));
        try {
            saveItem(item);
        } catch (IOException e) {
            destination.delete();
            throw e;
        }
        return item;
    }

    public synchronized List<AudioItem> getAudioItems() {

        List<AudioItem> items = new ArrayList<>();

        if (!indexFile.exists()) {
            return items;
        }

        try {
            String content = readIndexFile();
            JSONArray array = new JSONArray(content);

            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);

                AudioItem item = new AudioItem(
                        object.getString("audioId"),
                        object.getString("name"),
                        object.getString("fileName"),
                        object.optLong("duration"),
                        object.optLong("fileSize"),
                        object.optString("folder", "")
                );

                File audioFile = new File(
                        audioDirectory,
                        item.getFileName()
                );

                if (audioFile.exists()) {
                    items.add(item);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return items;
    }

    private void saveItem(AudioItem item) throws IOException {
        List<AudioItem> items = getAudioItems();
        items.add(item);
        saveAll(items);
    }

    /** 删除音频：移出索引并删除文件。 */
    public synchronized void delete(String audioId) throws IOException {
        List<AudioItem> items = getAudioItems();
        AudioItem removed = null;
        for (AudioItem item : items) {
            if (item.getAudioId().equals(audioId)) {
                removed = item;
                break;
            }
        }
        if (removed == null) {
            return;
        }
        items.remove(removed);
        saveAll(items);
        //noinspection ResultOfMethodCallIgnored
        new File(audioDirectory, removed.getFileName()).delete();
    }

    /** 重命名（只改显示名称，文件和 ID 不变，电脑端引用不受影响）。 */
    public synchronized void rename(String audioId, String newName) throws IOException {
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty()) {
            throw new IOException("名称不能为空");
        }
        List<AudioItem> items = getAudioItems();
        for (int i = 0; i < items.size(); i++) {
            AudioItem item = items.get(i);
            if (item.getAudioId().equals(audioId)) {
                items.set(i, item.withName(name));
                saveAll(items);
                return;
            }
        }
    }

    /** 上移 / 下移一位，调整列表顺序（也是电脑端看到的顺序）。 */
    public synchronized void move(String audioId, int delta) throws IOException {
        List<AudioItem> items = getAudioItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getAudioId().equals(audioId)) {
                int target = i + delta;
                if (target < 0 || target >= items.size()) {
                    return;
                }
                AudioItem item = items.remove(i);
                items.add(target, item);
                saveAll(items);
                return;
            }
        }
    }

    public synchronized long getTotalBytes() {
        long total = 0;
        for (AudioItem item : getAudioItems()) {
            total += item.getFileSize();
        }
        return total;
    }

    // ==================== 文件夹（逻辑分组，只一层） ====================

    public static final int MAX_FOLDER_NAME = 40;

    /** 文件夹名称列表（不含“未分类”），按创建顺序；音频引用但列表里没有的也会补上。 */
    public synchronized List<String> getFolders() {
        List<String> folders = readFolderFile();
        for (AudioItem item : getAudioItems()) {
            if (!item.getFolder().isEmpty() && !folders.contains(item.getFolder())) {
                folders.add(item.getFolder());
            }
        }
        return folders;
    }

    public synchronized void createFolder(String name) throws IOException {
        String clean = checkFolderName(name);
        List<String> folders = getFolders();
        if (folders.contains(clean)) {
            throw new IOException("文件夹已存在");
        }
        folders.add(clean);
        saveFolders(folders);
    }

    /** 改名：同时更新其中所有音频的 folder 字段（文件和 audioId 不变）。 */
    public synchronized void renameFolder(String oldName, String newName) throws IOException {
        String clean = checkFolderName(newName);
        List<String> folders = getFolders();
        int index = folders.indexOf(oldName);
        if (index < 0) {
            throw new IOException("文件夹不存在");
        }
        if (clean.equals(oldName)) {
            return;
        }
        if (folders.contains(clean)) {
            throw new IOException("文件夹已存在");
        }
        folders.set(index, clean);
        List<AudioItem> items = getAudioItems();
        for (int i = 0; i < items.size(); i++) {
            if (oldName.equals(items.get(i).getFolder())) {
                items.set(i, items.get(i).withFolder(clean));
            }
        }
        saveAll(items);
        saveFolders(folders);
    }

    /** 删除文件夹：其中的音频回到“未分类”，不删除音频。 */
    public synchronized void deleteFolder(String name) throws IOException {
        List<String> folders = getFolders();
        if (!folders.remove(name)) {
            throw new IOException("文件夹不存在");
        }
        List<AudioItem> items = getAudioItems();
        for (int i = 0; i < items.size(); i++) {
            if (name.equals(items.get(i).getFolder())) {
                items.set(i, items.get(i).withFolder(""));
            }
        }
        saveAll(items);
        saveFolders(folders);
    }

    /** 移动音频到文件夹；folder 为空表示“未分类”。 */
    public synchronized void moveToFolder(String audioId, String folder) throws IOException {
        String target = folder == null ? "" : folder.trim();
        if (!target.isEmpty() && !getFolders().contains(target)) {
            throw new IOException("文件夹不存在");
        }
        List<AudioItem> items = getAudioItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getAudioId().equals(audioId)) {
                items.set(i, items.get(i).withFolder(target));
                saveAll(items);
                return;
            }
        }
        throw new IOException("音频不存在");
    }

    private static String checkFolderName(String name) throws IOException {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new IOException("文件夹名称不能为空");
        }
        if (clean.length() > MAX_FOLDER_NAME || clean.contains(":")
                || clean.contains("\n") || clean.contains("\r")) {
            throw new IOException("文件夹名称不能包含冒号或换行，且不超过 " + MAX_FOLDER_NAME + " 个字");
        }
        if ("未分类".equals(clean)) {
            throw new IOException("“未分类”是保留名称");
        }
        return clean;
    }

    private List<String> readFolderFile() {
        List<String> folders = new ArrayList<>();
        if (!folderFile.exists()) {
            return folders;
        }
        try {
            JSONArray array = new JSONArray(readText(folderFile));
            for (int i = 0; i < array.length(); i++) {
                String name = array.optString(i, "").trim();
                if (!name.isEmpty() && !folders.contains(name)) {
                    folders.add(name);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return folders;
    }

    private void saveFolders(List<String> folders) throws IOException {
        JSONArray array = new JSONArray();
        for (String folder : folders) {
            array.put(folder);
        }
        try {
            writeAtomically(folderFile, array.toString(2));
        } catch (JSONException e) {
            throw new IOException("保存文件夹失败", e);
        }
    }

    /** 先写临时文件再替换，避免写到一半导致索引损坏。 */
    private void saveAll(List<AudioItem> items) throws IOException {
        JSONArray array = new JSONArray();
        for (AudioItem item : items) {
            array.put(itemToJson(item));
        }
        try {
            writeAtomically(indexFile, array.toString(2));
        } catch (JSONException e) {
            throw new IOException("保存音频列表失败", e);
        }
    }

    private void writeAtomically(File target, String content) throws IOException {
        File temp = new File(audioDirectory, target.getName() + ".tmp");
        try (java.io.Writer writer = new java.io.OutputStreamWriter(
                new FileOutputStream(temp), java.nio.charset.StandardCharsets.UTF_8)) {
            writer.write(content);
        }
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!temp.renameTo(target)) {
                throw new IOException("保存失败：" + target.getName());
            }
        }
    }

    private JSONObject itemToJson(AudioItem item) throws IOException {

        JSONObject object = new JSONObject();

        try {
            object.put("audioId", item.getAudioId());
            object.put("name", item.getName());
            object.put("fileName", item.getFileName());
            object.put("duration", item.getDuration());
            object.put("fileSize", item.getFileSize());
            if (!item.getFolder().isEmpty()) {
                object.put("folder", item.getFolder());
            }

            return object;

        } catch (Exception e) {
            throw new IOException("保存音频信息失败", e);
        }
    }

    private String readIndexFile() throws IOException {
        return readText(indexFile);
    }

    private String readText(File file) throws IOException {

        StringBuilder builder = new StringBuilder();

        try (java.io.BufferedReader reader =
                     new java.io.BufferedReader(
                             new java.io.InputStreamReader(
                                     new java.io.FileInputStream(file),
                                     java.nio.charset.StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }

        return builder.toString();
    }

    private String getDisplayName(Context context, Uri uri) {

        try (Cursor cursor = context.getContentResolver().query(
                uri,
                null,
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                );

                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        }

        return null;
    }

    private long getDuration(File file) {

        MediaMetadataRetriever retriever =
                new MediaMetadataRetriever();

        try {
            retriever.setDataSource(file.getAbsolutePath());

            String value = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
            );

            return value == null ? 0 : Long.parseLong(value);

        } catch (Exception e) {
            return 0;

        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }
}
