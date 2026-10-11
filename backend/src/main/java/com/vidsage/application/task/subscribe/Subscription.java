package com.vidsage.application.task.subscribe;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 媒体转字幕任务进度的订阅句柄
 * 包含了退订操作
 */
public final class Subscription implements AutoCloseable {

    /**
     * 真正执行退订的动作（由注册表在创建时给出）。
     */
    private final Runnable unsubscribe;

    /**
     * 幂等闸门：true 表示已退订过。
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @param unsubscribe 退订动作；由注册表传入，只应被执行一次
     */
    public Subscription(Runnable unsubscribe) {
        this.unsubscribe = unsubscribe;
    }

    /**
     * 退订：从注册表移除对应订阅者。幂等——第二次起是无操作。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            unsubscribe.run();
        }
    }

    /**
     * 是否已退订。给测试与排查用。
     */
    boolean isClosed() {
        return closed.get();
    }
}
