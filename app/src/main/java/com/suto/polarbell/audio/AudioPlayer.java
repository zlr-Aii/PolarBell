
package com.suto.polarbell.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

import com.suto.polarbell.R;

import java.io.File;
import java.io.IOException;

public class AudioPlayer {

    private final Context context;
    private MediaPlayer mediaPlayer;
    private float volume = 1f;

    public AudioPlayer(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void play(int type) {

        stop();

        int resourceId;

        switch (type) {
            case 1:
                resourceId = R.raw.bell_1;
                break;
            case 2:
                resourceId = R.raw.bell_2;
                break;
            case 3:
                resourceId = R.raw.bell_3;
                break;
            default:
                return;
        }

        mediaPlayer = MediaPlayer.create(context, resourceId);

        if (mediaPlayer == null) {
            return;
        }

        MediaPlayer currentPlayer = mediaPlayer;

        applyAudioAttributes(currentPlayer);
        applyVolume(currentPlayer);

        currentPlayer.setOnCompletionListener(mp -> {
            synchronized (AudioPlayer.this) {
                if (mediaPlayer == currentPlayer) {
                    mediaPlayer = null;
                }
            }
            currentPlayer.release();
        });

        currentPlayer.start();
    }

    public synchronized void play(File file) throws IOException {
        play(file, null);
    }

    /**
     * onFinished：自然播完或播放出错时回调（MediaPlayer 所在线程的 Looper，通常是主线程）；
     * 被 stop() 或下一次 play 打断时不回调。
     */
    public synchronized void play(File file, Runnable onFinished) throws IOException {

        stop();

        if (file == null || !file.exists()) {
            throw new IOException("音频文件不存在");
        }

        MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;

        try {
            player.setDataSource(file.getAbsolutePath());
            applyAudioAttributes(player);
            try {
                // 熄屏时保持 CPU 运行，连续播放不被打断
                player.setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK);
            } catch (RuntimeException ignored) {
            }

            player.setOnCompletionListener(mp -> {
                boolean current;
                synchronized (AudioPlayer.this) {
                    current = mediaPlayer == player;
                    if (current) {
                        mediaPlayer = null;
                    }
                }
                player.release();
                if (current && onFinished != null) {
                    onFinished.run();
                }
            });

            player.setOnErrorListener((mp, what, extra) -> {
                boolean current;
                synchronized (AudioPlayer.this) {
                    current = mediaPlayer == player;
                    if (current) {
                        mediaPlayer = null;
                    }
                }
                player.release();
                if (current && onFinished != null) {
                    onFinished.run();
                }
                return true;
            });

            // 同步 prepare：上传文件是本地文件，准备时间很短。
            // 这样 SocketServer 可以先确认“能播放”再回复 OK，
            // 编码不兼容时会返回 PLAY_FAILED，让电脑端及时回退扬声器。
            player.prepare();
            applyVolume(player);
            player.start();

        } catch (IOException | RuntimeException e) {
            if (mediaPlayer == player) {
                mediaPlayer = null;
            }
            player.release();

            if (e instanceof IOException) {
                throw (IOException) e;
            }

            throw new IOException("无法播放该音频", e);
        }
    }

    /**
     * 使用媒体音频通道，保证手机按媒体音量输出，
     * 而不是被当作通知/铃声通道处理。
     */
    private void applyAudioAttributes(MediaPlayer player) {
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            player.setAudioAttributes(attributes);
        } catch (RuntimeException ignored) {
        }
    }

    /** 播放音量 0-100（相对手机媒体音量），立即作用于正在播放的声音。 */
    public synchronized void setVolume(int percent) {
        volume = Math.max(0, Math.min(100, percent)) / 100f;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setVolume(volume, volume);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void applyVolume(MediaPlayer player) {
        try {
            player.setVolume(volume, volume);
        } catch (RuntimeException ignored) {
        }
    }

    /** 暂停 / 继续当前声音；暂停时 isPlaying 仍为 true。 */
    public synchronized void setPaused(boolean paused) {
        if (mediaPlayer == null) {
            return;
        }
        try {
            if (paused) {
                mediaPlayer.pause();
            } else {
                mediaPlayer.start();
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** 是否还有声音在播放（播完或出错后播放器会被置空）。 */
    public synchronized boolean isPlaying() {
        return mediaPlayer != null;
    }

    public synchronized void stop() {
        if (mediaPlayer != null) {
            MediaPlayer oldPlayer = mediaPlayer;
            mediaPlayer = null;
            oldPlayer.release();
        }
    }

    public synchronized void release() {
        stop();
    }
}
