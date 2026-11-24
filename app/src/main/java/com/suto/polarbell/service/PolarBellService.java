
package com.suto.polarbell.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.suto.polarbell.MainActivity;
import com.suto.polarbell.network.SocketServer;
import com.suto.polarbell.plan.PhonePlanEngine;

import java.util.Locale;

public class PolarBellService extends Service {

    private static final String TAG = "PolarBellService";

    public static final String ACTION_CONNECTION_CHANGED =
            "com.suto.polarbell.CONNECTION_CHANGED";

    public static final String EXTRA_CONNECTED = "connected";

    public static final String ACTION_LIBRARY_CHANGED =
            "com.suto.polarbell.LIBRARY_CHANGED";

    /** 手机计划状态变化（界面刷新用）。 */
    public static final String ACTION_PLAN_CHANGED =
            "com.suto.polarbell.PLAN_CHANGED";

    // 发给服务的手机计划命令（界面按钮、通知按钮、定时唤醒）
    public static final String ACTION_PLAN_START = "com.suto.polarbell.PLAN_START";
    public static final String ACTION_PLAN_PAUSE = "com.suto.polarbell.PLAN_PAUSE";
    public static final String ACTION_PLAN_SKIP = "com.suto.polarbell.PLAN_SKIP";
    public static final String ACTION_PLAN_STOP = "com.suto.polarbell.PLAN_STOP";
    public static final String ACTION_PLAN_WAKE = "com.suto.polarbell.PLAN_WAKE";

    private volatile boolean computerConnected = false;

    private static final String CHANNEL_ID = "polarbell_service";
    private static final int NOTIFICATION_ID = 1;

    private static volatile PolarBellService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SocketServer socketServer;
    private PhonePlanEngine planEngine;
    private PowerManager.WakeLock wakeLock;

    /** 界面读取手机计划状态；服务未运行时返回 null。 */
    public static PhonePlanEngine.Snapshot planSnapshot() {
        PolarBellService service = instance;
        return service == null || service.planEngine == null ? null : service.planEngine.snapshot();
    }

    public static boolean isComputerConnected() {
        PolarBellService service = instance;
        return service != null && service.computerConnected;
    }

    /** 启动服务并执行一个命令（action 可为 null）。 */
    public static void send(Context context, String action) {
        Intent intent = new Intent(context, PolarBellService.class);
        if (action != null) {
            intent.setAction(action);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        createNotificationChannel();

        // 将服务提升为前台服务
        startForeground(NOTIFICATION_ID, createNotification());

        planEngine = new PhonePlanEngine(this, this::onPlanChanged);

        // 创建并启动 Socket 服务
        socketServer = new SocketServer(this, new SocketServer.Listener() {
            @Override
            public void onConnected() {
                Log.i(TAG, "电脑已连接");
                computerConnected = true;
                notifyConnectionChanged(true);
                // 电脑端 StudyTimer 接管：停止手机独立计划
                mainHandler.post(() -> {
                    if (planEngine != null && planEngine.isRunning()) {
                        planEngine.stop("电脑已连接，手机计划已停止");
                    }
                });
            }

            @Override
            public void onDisconnected() {
                Log.i(TAG, "电脑已断开");
                computerConnected = false;
                notifyConnectionChanged(false);
                mainHandler.post(() -> updateNotification());
            }

            @Override
            public void onLibraryChanged() {
                Intent intent = new Intent(ACTION_LIBRARY_CHANGED);
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
            }
        });

        socketServer.start();

        // 服务被系统杀掉后重启：接上未结束的计划
        planEngine.restore();

        Log.i(TAG, "PolarBell 服务已启动");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "PolarBell 通信服务",
                    NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription("保持电脑与手机之间的 USB 通信，显示手机计划进度");

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        builder.setContentIntent(PendingIntent.getActivity(this, 0, open, pendingFlags()))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        PhonePlanEngine.Snapshot s = planEngine == null ? null : planEngine.snapshot();
        if (s == null || !s.running) {
            return builder
                    .setContentTitle("PolarBell 正在运行")
                    .setContentText(computerConnected
                            ? "已连接电脑，接收提示音指令"
                            : "等待电脑连接，或在手机上开始计划")
                    .build();
        }

        String title = s.stageName + "（" + (s.stageIndex + 1) + "/" + s.stageCount + "）"
                + (s.paused ? " · 已暂停" : "");
        String text = s.paused
                ? "剩余 " + formatRemaining(s.remainingMs)
                : s.planName + (s.nowPlaying.isEmpty() ? "" : " · ♪ " + s.nowPlaying);
        builder.setContentTitle(title).setContentText(text);
        if (!s.paused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 系统自己倒计时，不需要每秒刷新通知
            builder.setWhen(s.stageEndWallMs)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true);
        }
        return builder.build();
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private static String formatRemaining(long ms) {
        long seconds = (ms + 999) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification());
        }
    }

    private void onPlanChanged() {
        updateNotification();
        updateWakeLock();
        scheduleWake();
        Intent intent = new Intent(ACTION_PLAN_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    /** 计划运行中保持 CPU 唤醒，熄屏后照常计时。 */
    private void updateWakeLock() {
        PhonePlanEngine.Snapshot s = planEngine.snapshot();
        boolean need = s.running && !s.paused;
        if (need) {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PolarBell:plan");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                // 上限 12 小时，防止异常时一直耗电；状态变化时会重新申请
                wakeLock.acquire(12L * 60 * 60 * 1000);
            }
        } else if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    /** 闹钟兜底：即使系统进入 Doze，也在音乐开始/阶段结束时唤醒一次。 */
    private void scheduleWake() {
        AlarmManager alarm = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarm == null) {
            return;
        }
        Intent intent = new Intent(this, PolarBellService.class).setAction(ACTION_PLAN_WAKE);
        // 服务被杀后后台不能 startService，用 getForegroundService 拉起
        PendingIntent pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(this, 10, intent, pendingFlags())
                : PendingIntent.getService(this, 10, intent, pendingFlags());
        long at = planEngine.nextWakeWallMs();
        alarm.cancel(pending);
        if (at <= 0) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            } else {
                alarm.setExact(AlarmManager.RTC_WAKEUP, at, pending);
            }
        } catch (SecurityException e) {
            // 新版系统未授予精确闹钟权限时退回普通闹钟
            alarm.set(AlarmManager.RTC_WAKEUP, at, pending);
        }
    }

    private void notifyConnectionChanged(boolean connected) {
        Intent intent = new Intent(ACTION_CONNECTION_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_CONNECTED, connected);
        sendBroadcast(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 每次 startForegroundService 后都要求调用 startForeground
        startForeground(NOTIFICATION_ID, createNotification());

        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAN_START.equals(action)) {
            planEngine.start();
        } else if (ACTION_PLAN_PAUSE.equals(action)) {
            planEngine.togglePause();
        } else if (ACTION_PLAN_SKIP.equals(action)) {
            planEngine.skip();
        } else if (ACTION_PLAN_STOP.equals(action)) {
            planEngine.stop("");
        } else if (ACTION_PLAN_WAKE.equals(action)) {
            planEngine.wake();
            scheduleWake();
        }

        // Activity 再次打开时，同步当前电脑连接状态
        notifyConnectionChanged(computerConnected);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (socketServer != null) {
            socketServer.stop();
            socketServer = null;
        }
        if (planEngine != null) {
            planEngine.release();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        instance = null;

        Log.i(TAG, "PolarBell 服务已停止");

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
