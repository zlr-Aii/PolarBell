
package com.suto.polarbell.network;

import android.content.Context;

import com.suto.polarbell.audio.AudioPlayer;
import com.suto.polarbell.media.AudioItem;
import com.suto.polarbell.media.AudioLibrary;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * PolarBell TCP 命令服务（端口 8988）。
 *
 * 既有命令：PING / LIST / PLAY_AUDIO / PLAY:1-3 / STOP
 * 本批新增：
 *   CAPABILITIES
 *   PLAY_UPLOAD:&lt;byteLength&gt;:&lt;fileName&gt;
 *   PLAY_UPLOAD_HASH:&lt;byteLength&gt;:&lt;sha256&gt;:&lt;fileName&gt;
 * 0.92 新增分块/断点续传（能力 UPLOAD_CHUNKED，上限 512MB）：
 *   UPLOAD_BEGIN / UPLOAD_CHUNK / UPLOAD_PLAY
 * 1.0 新增电脑端管理音频库（能力 LIBRARY_MANAGE）：
 *   LIB_ADD:&lt;sha256&gt;:&lt;name&gt;（分块传完后加入库）/ LIB_DELETE:&lt;id&gt; / LIB_RENAME:&lt;id&gt;:&lt;name&gt;
 * 1.0 新增音量（能力 VOLUME）：SET_VOLUME:&lt;0-100&gt;
 * 1.4 新增逻辑文件夹（能力 LIB_FOLDERS，只一层，空 = 未分类）：
 *   LIST 每项带 folder，并附 folders 列表；
 *   LIB_FOLDER_CREATE:&lt;name&gt; / LIB_FOLDER_RENAME:&lt;old&gt;:&lt;new&gt; / LIB_FOLDER_DELETE:&lt;name&gt;
 *   LIB_MOVE:&lt;id&gt;:&lt;folder&gt; / LIB_ADD_TO:&lt;sha256&gt;:&lt;folder&gt;:&lt;name&gt;；失败回复 ERROR:&lt;原因&gt;
 * 1.6 新增：PLAY_STATE（能力 PLAY_STATE，回复 PLAYING / IDLE，电脑端据此接播下一首）；
 *   LIB_READ:&lt;id&gt;:&lt;offset&gt;:&lt;len&gt;（能力 LIB_READ，回复 DATA:&lt;总大小&gt;:&lt;扩展名&gt;:&lt;Base64&gt;，供电脑端试听）
 *
 * 上传用于 “电脑显示视频画面、手机播放视频原声”：
 * 电脑把视频文件按字节发送过来，手机用 MediaPlayer 播放其中的音轨。
 * PLAY_UPLOAD_HASH 会按 SHA-256 复用已上传文件，避免重复传输。
 */
public class SocketServer {

    private static final int PORT = 8988;

    /** 单次上传上限 32MB，防止异常大包占满存储。 */
    private static final long MAX_UPLOAD_BYTES = 32L * 1024L * 1024L;

    /** 分块上传总大小上限 512MB；每块最多 4MB。 */
    private static final long MAX_CHUNKED_UPLOAD_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_CHUNK_BYTES = 4 * 1024 * 1024;

    /** 上传缓存保留 7 天，过期文件在服务启动时清理。 */
    private static final long UPLOAD_CACHE_MAX_AGE_MS =
            7L * 24L * 60L * 60L * 1000L;

    /** 连接空闲超时。电脑端每 5 秒 PING 一次，正常连接不会被误断开。 */
    private static final int CLIENT_READ_TIMEOUT_MS = 120_000;

    private ServerSocket serverSocket;

    private final AudioPlayer audioPlayer;
    private final AudioLibrary audioLibrary;
    private final File uploadDirectory;

    private volatile boolean running = false;

    public interface Listener {
        void onConnected();
        void onDisconnected();
        /** 电脑端修改了音频库（添加 / 删除 / 重命名）。 */
        void onLibraryChanged();
    }

    private final Listener listener;

    public SocketServer(Context context, Listener listener) {
        this.listener = listener;
        this.audioPlayer = new AudioPlayer(context);
        this.audioLibrary = new AudioLibrary(context);
        this.uploadDirectory = new File(
                context.getCacheDir(),
                "polarbell-upload"
        );
        if (!uploadDirectory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            uploadDirectory.mkdirs();
        }
        pruneUploadDirectory();
    }

    // 启动 TCP 服务
    public void start() {
        if (running) {
            return;
        }

        running = true;

        Thread serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);

                while (running) {
                    Socket clientSocket = serverSocket.accept();

                    if (listener != null) {
                        listener.onConnected();
                    }

                    handleClient(clientSocket);
                }

            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }, "polarbell-socket-server");

        serverThread.setDaemon(true);
        serverThread.start();
    }

    // 处理电脑发送的命令
    private void handleClient(Socket socket) {
        try (
                Socket client = socket;
                InputStream input = client.getInputStream();
                OutputStream output = client.getOutputStream();
                PrintWriter writer = new PrintWriter(output, true)
        ) {
            client.setSoTimeout(CLIENT_READ_TIMEOUT_MS);

            String command;

            while (running && (command = readLine(input)) != null) {

                if ("PING".equals(command)) {
                    writer.println("PONG");

                } else if ("CAPABILITIES".equals(command)) {
                    writer.println(
                            "CAP:PLAY_UPLOAD,PLAY_UPLOAD_HASH,UPLOAD_CHUNKED,LIST,STOP,"
                                    + "LIBRARY_MANAGE,VOLUME,LIB_FOLDERS,PLAY_STATE,LIB_READ"
                    );

                } else if ("PLAY_STATE".equals(command)) {
                    writer.println(audioPlayer.isPlaying() ? "PLAYING" : "IDLE");

                } else if (command.startsWith("LIB_READ:")) {
                    writer.println(handleLibraryRead(
                            command.substring("LIB_READ:".length())));

                } else if (command.startsWith("LIB_FOLDER_CREATE:")) {
                    writer.println(handleFolderCreate(
                            command.substring("LIB_FOLDER_CREATE:".length())));

                } else if (command.startsWith("LIB_FOLDER_RENAME:")) {
                    writer.println(handleFolderRename(
                            command.substring("LIB_FOLDER_RENAME:".length())));

                } else if (command.startsWith("LIB_FOLDER_DELETE:")) {
                    writer.println(handleFolderDelete(
                            command.substring("LIB_FOLDER_DELETE:".length())));

                } else if (command.startsWith("LIB_MOVE:")) {
                    writer.println(handleLibraryMove(
                            command.substring("LIB_MOVE:".length())));

                } else if (command.startsWith("LIB_ADD_TO:")) {
                    writer.println(handleLibraryAddTo(
                            command.substring("LIB_ADD_TO:".length())));

                } else if (command.startsWith("SET_VOLUME:")) {
                    writer.println(handleSetVolume(
                            command.substring("SET_VOLUME:".length()).trim()));

                } else if (command.startsWith("LIB_ADD:")) {
                    writer.println(handleLibraryAdd(
                            command.substring("LIB_ADD:".length())));

                } else if (command.startsWith("LIB_DELETE:")) {
                    writer.println(handleLibraryDelete(
                            command.substring("LIB_DELETE:".length()).trim()));

                } else if (command.startsWith("LIB_RENAME:")) {
                    writer.println(handleLibraryRename(
                            command.substring("LIB_RENAME:".length())));

                } else if ("LIST".equals(command)) {
                    writer.println(buildAudioListResponse());

                } else if (command.startsWith("PLAY_AUDIO:")) {
                    String audioId = command.substring("PLAY_AUDIO:".length());
                    writer.println(playAudioById(audioId));

                } else if (command.startsWith("UPLOAD_BEGIN:")) {
                    writer.println(handleUploadBegin(
                            command.substring("UPLOAD_BEGIN:".length())));

                } else if (command.startsWith("UPLOAD_CHUNK:")) {
                    handleUploadChunk(
                            command.substring("UPLOAD_CHUNK:".length()),
                            input,
                            writer
                    );

                } else if (command.startsWith("UPLOAD_PLAY:")) {
                    writer.println(handleUploadPlay(
                            command.substring("UPLOAD_PLAY:".length()).trim()));

                } else if (command.startsWith("PLAY_UPLOAD_HASH:")) {
                    handlePlayUpload(
                            command.substring("PLAY_UPLOAD_HASH:".length()),
                            input,
                            writer,
                            true
                    );

                } else if (command.startsWith("PLAY_UPLOAD:")) {
                    handlePlayUpload(
                            command.substring("PLAY_UPLOAD:".length()),
                            input,
                            writer,
                            false
                    );

                } else if ("PLAY:1".equals(command)) {
                    audioPlayer.play(1);
                    writer.println("OK");

                } else if ("PLAY:2".equals(command)) {
                    audioPlayer.play(2);
                    writer.println("OK");

                } else if ("PLAY:3".equals(command)) {
                    audioPlayer.play(3);
                    writer.println("OK");

                } else if ("STOP".equals(command)) {
                    audioPlayer.stop();
                    writer.println("OK");

                } else {
                    writer.println("UNKNOWN_COMMAND");
                }
            }

        } catch (IOException e) {
            if (running) {
                e.printStackTrace();
            }
        } finally {
            if (listener != null && running) {
                listener.onDisconnected();
            }
        }
    }

    /**
     * 处理文件上传并播放。
     *
     * 协议：电脑发送命令后，本方法回复 READY；电脑随后发送精确的
     * byteLength 个字节；保存完成后播放并回复 OK（或 CACHED）。
     */
    private void handlePlayUpload(
            String payload,
            InputStream input,
            PrintWriter writer,
            boolean useHash
    ) throws IOException {

        long size;
        String sha256 = null;
        String fileName;
        File target;

        try {
            int separator = payload.indexOf(':');

            if (separator <= 0) {
                writer.println("INVALID_UPLOAD");
                return;
            }

            size = Long.parseLong(payload.substring(0, separator).trim());
            String rest = payload.substring(separator + 1);

            if (useHash) {
                int hashSeparator = rest.indexOf(':');

                if (hashSeparator <= 0) {
                    writer.println("INVALID_UPLOAD");
                    return;
                }

                sha256 = rest.substring(0, hashSeparator).trim().toLowerCase();
                fileName = rest.substring(hashSeparator + 1).trim();

                if (!sha256.matches("[0-9a-f]{64}")) {
                    writer.println("INVALID_HASH");
                    return;
                }

                String safe = sanitizeFileName(fileName);
                target = new File(uploadDirectory, sha256 + "_" + safe);

                if (target.isFile() && target.length() == size) {
                    try {
                        audioPlayer.play(target);
                        writer.println("CACHED");
                    } catch (IOException e) {
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                        writer.println("PLAY_FAILED");
                    }
                    return;
                }

            } else {
                fileName = rest.trim();
                String safe = sanitizeFileName(fileName);
                target = new File(
                        uploadDirectory,
                        "up_" + UUID.randomUUID().toString().replace("-", "")
                                + "_" + safe
                );
            }

        } catch (NumberFormatException e) {
            writer.println("INVALID_SIZE");
            return;
        }

        if (size <= 0 || size > MAX_UPLOAD_BYTES) {
            writer.println("INVALID_SIZE");
            return;
        }

        writer.println("READY");
        writer.flush();

        try (FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long remaining = size;

            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = input.read(buffer, 0, toRead);

                if (read < 0) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    writer.println("UPLOAD_INCOMPLETE");
                    writer.flush();
                    throw new IOException("上传数据不完整");
                }

                output.write(buffer, 0, read);
                remaining -= read;
            }

            output.flush();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw e;
        }

        try {
            audioPlayer.play(target);
            writer.println("OK");
            writer.flush();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            writer.println("PLAY_FAILED");
            writer.flush();
        }
    }

    /** 分块上传的会话信息：同一个 sha256 对应一个 .part 文件。 */
    private static final class ChunkedUpload {
        final long size;
        final File partFile;
        final File finalFile;

        ChunkedUpload(long size, File partFile, File finalFile) {
            this.size = size;
            this.partFile = partFile;
            this.finalFile = finalFile;
        }
    }

    private final Map<String, ChunkedUpload> chunkedUploads = new HashMap<>();

    /**
     * UPLOAD_BEGIN:&lt;size&gt;:&lt;sha256&gt;:&lt;fileName&gt;
     * 回复 DONE（文件已完整存在）或 OFFSET:&lt;已收到字节数&gt;（断点续传）。
     */
    private String handleUploadBegin(String payload) {
        String[] parts = payload.split(":", 3);
        if (parts.length < 3) {
            return "INVALID_UPLOAD";
        }

        long size;
        try {
            size = Long.parseLong(parts[0].trim());
        } catch (NumberFormatException e) {
            return "INVALID_SIZE";
        }
        if (size <= 0 || size > MAX_CHUNKED_UPLOAD_BYTES) {
            return "INVALID_SIZE";
        }

        String sha256 = parts[1].trim().toLowerCase();
        if (!sha256.matches("[0-9a-f]{64}")) {
            return "INVALID_HASH";
        }

        String safe = sanitizeFileName(parts[2].trim());
        File finalFile = new File(uploadDirectory, sha256 + "_" + safe);
        File partFile = new File(uploadDirectory, sha256 + "_" + safe + ".part");
        chunkedUploads.put(sha256, new ChunkedUpload(size, partFile, finalFile));

        if (finalFile.isFile() && finalFile.length() == size) {
            //noinspection ResultOfMethodCallIgnored
            finalFile.setLastModified(System.currentTimeMillis());
            return "DONE";
        }

        long received = partFile.isFile() ? partFile.length() : 0L;
        if (received > size) {
            //noinspection ResultOfMethodCallIgnored
            partFile.delete();
            received = 0L;
        }
        return "OFFSET:" + received;
    }

    /**
     * UPLOAD_CHUNK:&lt;sha256&gt;:&lt;offset&gt;:&lt;length&gt;
     * 偏移正确时回复 READY，接收 length 字节后回复 ACK:&lt;新长度&gt;；
     * 偏移不一致时回复 OFFSET:&lt;实际长度&gt;，电脑端据此续传。
     */
    private void handleUploadChunk(
            String payload,
            InputStream input,
            PrintWriter writer
    ) throws IOException {

        String[] parts = payload.split(":");
        if (parts.length != 3) {
            writer.println("INVALID_UPLOAD");
            return;
        }

        ChunkedUpload upload = chunkedUploads.get(parts[0].trim().toLowerCase());
        if (upload == null) {
            writer.println("NO_SESSION");
            return;
        }

        long offset;
        int length;
        try {
            offset = Long.parseLong(parts[1].trim());
            length = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            writer.println("INVALID_SIZE");
            return;
        }

        long current = upload.partFile.isFile() ? upload.partFile.length() : 0L;
        if (offset != current) {
            writer.println("OFFSET:" + current);
            return;
        }
        if (length <= 0 || length > MAX_CHUNK_BYTES
                || offset + length > upload.size) {
            writer.println("INVALID_SIZE");
            return;
        }

        writer.println("READY");
        writer.flush();

        try (FileOutputStream output = new FileOutputStream(upload.partFile, true)) {
            byte[] buffer = new byte[8192];
            int remaining = length;

            while (remaining > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    // 已写入的前缀仍然有效，下次 UPLOAD_BEGIN 会从这里续传。
                    output.flush();
                    throw new IOException("分块上传中断");
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
            output.flush();
        }

        writer.println("ACK:" + upload.partFile.length());
        writer.flush();
    }

    /**
     * UPLOAD_PLAY:&lt;sha256&gt;
     * 文件接收完整后校验 SHA-256，转为正式缓存文件并播放。
     */
    private String handleUploadPlay(String sha256) {
        ChunkedUpload upload = chunkedUploads.get(sha256.toLowerCase());
        if (upload == null) {
            return "NO_SESSION";
        }

        String error = completeUpload(upload, sha256);
        if (error != null) {
            return error;
        }

        chunkedUploads.remove(sha256.toLowerCase());

        try {
            audioPlayer.play(upload.finalFile);
            return "OK";
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            upload.finalFile.delete();
            return "PLAY_FAILED";
        }
    }

    /**
     * SET_VOLUME:&lt;0-100&gt;
     * 播放音量（相对手机媒体音量的百分比），作用于当前和之后的播放。
     */
    private String handleSetVolume(String value) {
        try {
            int volume = Integer.parseInt(value);
            if (volume < 0 || volume > 100) {
                return "INVALID_VOLUME";
            }
            audioPlayer.setVolume(volume);
            return "OK";
        } catch (NumberFormatException e) {
            return "INVALID_VOLUME";
        }
    }

    /**
     * LIB_ADD:&lt;sha256&gt;:&lt;fileName&gt;
     * 先用 UPLOAD_BEGIN / UPLOAD_CHUNK 传完文件，再把它加入音频库。
     * 回复 OK:&lt;audioId&gt;。
     */
    private String handleLibraryAdd(String payload) {
        int separator = payload.indexOf(':');
        if (separator <= 0) {
            return "INVALID_UPLOAD";
        }
        String sha256 = payload.substring(0, separator).trim().toLowerCase();
        String fileName = payload.substring(separator + 1).trim();
        return addUploadToLibrary(sha256, fileName, "");
    }

    /** LIB_ADD_TO:&lt;sha256&gt;:&lt;folder&gt;:&lt;fileName&gt;（folder 为空 = 未分类） */
    private String handleLibraryAddTo(String payload) {
        int first = payload.indexOf(':');
        int second = first < 0 ? -1 : payload.indexOf(':', first + 1);
        if (first <= 0 || second < 0) {
            return "INVALID_UPLOAD";
        }
        String sha256 = payload.substring(0, first).trim().toLowerCase();
        String folder = payload.substring(first + 1, second).trim();
        String fileName = payload.substring(second + 1).trim();
        if (!folder.isEmpty() && !audioLibrary.getFolders().contains(folder)) {
            return "FOLDER_NOT_FOUND";
        }
        return addUploadToLibrary(sha256, fileName, folder);
    }

    /** LIB_FOLDER_CREATE:&lt;name&gt; */
    private String handleFolderCreate(String name) {
        try {
            audioLibrary.createFolder(name);
            notifyLibraryChanged();
            return "OK";
        } catch (IOException e) {
            return "ERROR:" + e.getMessage();
        }
    }

    /** LIB_FOLDER_RENAME:&lt;old&gt;:&lt;new&gt;（名称不含冒号） */
    private String handleFolderRename(String payload) {
        int separator = payload.indexOf(':');
        if (separator <= 0) {
            return "INVALID_NAME";
        }
        try {
            audioLibrary.renameFolder(payload.substring(0, separator).trim(),
                    payload.substring(separator + 1));
            notifyLibraryChanged();
            return "OK";
        } catch (IOException e) {
            return "ERROR:" + e.getMessage();
        }
    }

    /** LIB_FOLDER_DELETE:&lt;name&gt;，其中音频回到未分类 */
    private String handleFolderDelete(String name) {
        try {
            audioLibrary.deleteFolder(name.trim());
            notifyLibraryChanged();
            return "OK";
        } catch (IOException e) {
            return "ERROR:" + e.getMessage();
        }
    }

    /** LIB_MOVE:&lt;audioId&gt;:&lt;folder&gt;（folder 为空 = 未分类） */
    private String handleLibraryMove(String payload) {
        int separator = payload.indexOf(':');
        if (separator <= 0) {
            return "INVALID_NAME";
        }
        String audioId = payload.substring(0, separator).trim();
        if (!libraryContains(audioId)) {
            return "AUDIO_NOT_FOUND";
        }
        try {
            audioLibrary.moveToFolder(audioId, payload.substring(separator + 1));
            notifyLibraryChanged();
            return "OK";
        } catch (IOException e) {
            return "ERROR:" + e.getMessage();
        }
    }

    private String addUploadToLibrary(String sha256, String fileName, String folder) {

        ChunkedUpload upload = chunkedUploads.get(sha256);
        if (upload == null) {
            return "NO_SESSION";
        }

        String error = completeUpload(upload, sha256);
        if (error != null) {
            return error;
        }
        chunkedUploads.remove(sha256);

        try {
            AudioItem item = audioLibrary.importFile(upload.finalFile, fileName, folder);
            //noinspection ResultOfMethodCallIgnored
            upload.finalFile.delete();
            notifyLibraryChanged();
            return "OK:" + item.getAudioId();
        } catch (IOException e) {
            e.printStackTrace();
            return "SAVE_FAILED";
        }
    }

    /** LIB_DELETE:&lt;audioId&gt; */
    private String handleLibraryDelete(String audioId) {
        if (!libraryContains(audioId)) {
            return "AUDIO_NOT_FOUND";
        }
        try {
            audioLibrary.delete(audioId);
            notifyLibraryChanged();
            return "OK";
        } catch (IOException e) {
            return "SAVE_FAILED";
        }
    }

    /** LIB_RENAME:&lt;audioId&gt;:&lt;新名称&gt;（只改显示名称） */
    private String handleLibraryRename(String payload) {
        int separator = payload.indexOf(':');
        if (separator <= 0) {
            return "INVALID_NAME";
        }
        String audioId = payload.substring(0, separator).trim();
        String name = payload.substring(separator + 1).trim();
        if (name.isEmpty()) {
            return "INVALID_NAME";
        }
        if (!libraryContains(audioId)) {
            return "AUDIO_NOT_FOUND";
        }
        try {
            audioLibrary.rename(audioId, name);
            notifyLibraryChanged();
            return "OK";
        } catch (IOException e) {
            return "SAVE_FAILED";
        }
    }

    /** LIB_READ 单次最多读取 512KB（Base64 后约 700KB 一行）。 */
    private static final int MAX_READ_BYTES = 512 * 1024;

    /**
     * LIB_READ:&lt;audioId&gt;:&lt;offset&gt;:&lt;length&gt;
     * 回复 DATA:&lt;总大小&gt;:&lt;扩展名&gt;:&lt;Base64&gt;（读到末尾时 Base64 为空）。
     * 让电脑端把音频库里的音频取回去在电脑上试听。
     */
    private String handleLibraryRead(String payload) {
        String[] parts = payload.split(":");
        if (parts.length != 3) {
            return "INVALID_READ";
        }
        long offset;
        int length;
        try {
            offset = Long.parseLong(parts[1].trim());
            length = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            return "INVALID_READ";
        }
        if (offset < 0 || length <= 0 || length > MAX_READ_BYTES) {
            return "INVALID_READ";
        }
        for (AudioItem item : audioLibrary.getAudioItems()) {
            if (!item.getAudioId().equals(parts[0].trim())) {
                continue;
            }
            File file = new File(audioLibrary.getAudioDirectory(), item.getFileName());
            if (!file.isFile()) {
                return "AUDIO_NOT_FOUND";
            }
            String name = item.getFileName();
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot + 1).replace(":", "") : "";
            long total = file.length();
            int count = (int) Math.max(0, Math.min(length, total - offset));
            byte[] data = new byte[count];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                raf.seek(offset);
                raf.readFully(data);
            } catch (IOException e) {
                return "READ_FAILED";
            }
            return "DATA:" + total + ":" + ext + ":"
                    + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        }
        return "AUDIO_NOT_FOUND";
    }

    private boolean libraryContains(String audioId) {
        for (AudioItem item : audioLibrary.getAudioItems()) {
            if (item.getAudioId().equals(audioId)) {
                return true;
            }
        }
        return false;
    }

    private void notifyLibraryChanged() {
        if (listener != null) {
            listener.onLibraryChanged();
        }
    }

    /**
     * 确认分块上传已完整接收并通过 SHA-256 校验，转为正式文件。
     * 成功返回 null，否则返回错误回复。
     */
    private String completeUpload(ChunkedUpload upload, String sha256) {
        if (!(upload.finalFile.isFile() && upload.finalFile.length() == upload.size)) {
            if (!upload.partFile.isFile() || upload.partFile.length() != upload.size) {
                return "UPLOAD_INCOMPLETE";
            }

            try {
                if (!sha256.equalsIgnoreCase(sha256Hex(upload.partFile))) {
                    //noinspection ResultOfMethodCallIgnored
                    upload.partFile.delete();
                    return "HASH_MISMATCH";
                }
            } catch (IOException e) {
                return "HASH_FAILED";
            }

            //noinspection ResultOfMethodCallIgnored
            upload.finalFile.delete();
            if (!upload.partFile.renameTo(upload.finalFile)) {
                return "SAVE_FAILED";
            }
        }
        return null;
    }

    private static String sha256Hex(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(String.format(Locale.ROOT, "%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "audio.bin";
        }

        String base = name.replaceAll("[\\\\/\\s]+", "_");
        base = base.replaceAll("[^a-zA-Z0-9._-]", "");

        if (base.isEmpty()) {
            return "audio.bin";
        }

        if (base.length() > 64) {
            base = base.substring(base.length() - 64);
        }

        return base;
    }

    /**
     * 按字节读取一行命令。
     *
     * 不能使用 BufferedReader：它会预读并吞掉上传命令之后的二进制内容。
     */
    private static String readLine(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(128);

        while (true) {
            int ch = input.read();

            if (ch < 0) {
                if (bytes.size() == 0) {
                    return null;
                }
                break;
            }

            if (ch == '\n') {
                break;
            }

            if (ch != '\r') {
                bytes.write(ch);
            }
        }

        // 电脑端按 UTF-8 发送，音频名称可能含中文。
        return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // 根据 audioId 播放媒体库中的音频
    private String playAudioById(String audioId) {

        if (audioId == null || audioId.trim().isEmpty()) {
            return "AUDIO_NOT_FOUND";
        }

        List<AudioItem> items = audioLibrary.getAudioItems();

        for (AudioItem item : items) {

            if (audioId.equals(item.getAudioId())) {

                File audioFile = new File(
                        audioLibrary.getAudioDirectory(),
                        item.getFileName()
                );

                try {
                    audioPlayer.play(audioFile);
                    return "OK";

                } catch (IOException e) {
                    e.printStackTrace();
                    return "PLAY_FAILED";
                }
            }
        }

        return "AUDIO_NOT_FOUND";
    }

    // 构建媒体库 JSON 响应
    private String buildAudioListResponse() {
        try {
            List<AudioItem> items = audioLibrary.getAudioItems();

            JSONArray audios = new JSONArray();

            for (AudioItem item : items) {
                JSONObject audio = new JSONObject();

                audio.put("audioId", item.getAudioId());
                audio.put("name", item.getName());
                audio.put("duration", item.getDuration());
                audio.put("fileSize", item.getFileSize());
                audio.put("folder", item.getFolder());

                audios.put(audio);
            }

            JSONArray folders = new JSONArray();
            for (String folder : audioLibrary.getFolders()) {
                folders.put(folder);
            }

            JSONObject response = new JSONObject();
            response.put("status", "OK");
            response.put("audios", audios);
            response.put("folders", folders);

            return response.toString();

        } catch (Exception e) {
            e.printStackTrace();

            JSONObject response = new JSONObject();

            try {
                response.put("status", "ERROR");
                response.put("message", "Failed to read audio library");
            } catch (JSONException ignored) {
            }

            return response.toString();
        }
    }

    private void pruneUploadDirectory() {
        long now = System.currentTimeMillis();
        File[] files = uploadDirectory.listFiles();

        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isFile()
                    && now - file.lastModified() > UPLOAD_CACHE_MAX_AGE_MS) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    // 停止服务
    public void stop() {
        audioPlayer.release();

        running = false;

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
