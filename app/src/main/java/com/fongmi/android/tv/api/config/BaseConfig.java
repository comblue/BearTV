package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.net.OkHttp;

import java.io.InterruptedIOException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

abstract class BaseConfig {

    public static final int VOD = 0;
    public static final int LIVE = 1;
    public static final int WALL = 2;

    private final AtomicInteger taskId = new AtomicInteger(0);
    private Future<?> future;

    protected Config config;
    protected boolean sync;

    protected abstract String getTag();

    protected abstract Config defaultConfig();

    protected abstract void load(Config config) throws Throwable;

    protected void postEvent() {
        ConfigEvent.common();
    }

    public boolean needSync(String url) {
        return sync || TextUtils.isEmpty(config.getUrl()) || url.equals(config.getUrl());
    }

    public Config getConfig() {
        return config == null ? defaultConfig() : config;
    }

    public static String getUrl(BaseConfig instance) {
        return instance.getConfig().getUrl();
    }

    public static String getDesc(BaseConfig instance) {
        return instance.getConfig().getDesc();
    }

    public void load(Callback callback) {
        int id = taskId.incrementAndGet();
        if (future != null && !future.isDone()) future.cancel(true);
        future = App.submit(() -> loadConfig(id, config, callback));
        callback.start();
    }

    protected void loadConfig(int id, Config config, Callback callback) {
        try {
            Server.get().start();
            OkHttp.cancel(getTag());
            load(config);
            if (taskId.get() != id) return;
            if (config.equals(this.config)) config.update();
            App.post(() -> Notify.show(config.getNotice()));
            App.post(callback::success);
        } catch (Throwable e) {
            e.printStackTrace();
            if (isCanceled(e)) return;
            if (taskId.get() != id) return;
            if (TextUtils.isEmpty(config.getUrl())) App.post(() -> callback.error(""));
            else App.post(() -> callback.error(Notify.getError(R.string.error_config_get, e)));
        } finally {
            if (taskId.get() == id) postEvent();
        }
    }

    protected boolean isCanceled(Throwable e) {
        return "Canceled".equals(e.getMessage()) || e instanceof InterruptedException || e instanceof InterruptedIOException || e.getCause() instanceof InterruptedIOException;
    }
}
