
package com.suto.polarbell.media;

public class AudioItem {

    private String audioId;
    private String name;
    private String fileName;
    private long duration;
    private long fileSize;
    /** 所在文件夹；空字符串表示“未分类”。 */
    private String folder;

    public AudioItem(
            String audioId,
            String name,
            String fileName,
            long duration,
            long fileSize
    ) {
        this(audioId, name, fileName, duration, fileSize, "");
    }

    public AudioItem(
            String audioId,
            String name,
            String fileName,
            long duration,
            long fileSize,
            String folder
    ) {
        this.audioId = audioId;
        this.name = name;
        this.fileName = fileName;
        this.duration = duration;
        this.fileSize = fileSize;
        this.folder = folder == null ? "" : folder;
    }

    public String getAudioId() {
        return audioId;
    }

    public String getName() {
        return name;
    }

    public String getFileName() {
        return fileName;
    }

    public long getDuration() {
        return duration;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getFolder() {
        return folder;
    }

    public AudioItem withName(String newName) {
        return new AudioItem(audioId, newName, fileName, duration, fileSize, folder);
    }

    public AudioItem withFolder(String newFolder) {
        return new AudioItem(audioId, name, fileName, duration, fileSize, newFolder);
    }
}
