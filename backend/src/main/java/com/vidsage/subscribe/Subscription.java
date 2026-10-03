package com.vidsage.subscribe;

import com.vidsage.model.progress.TaskProgressMessage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 任务进度的退订句柄：{@link TaskSubscriptionRegistry#subscribe} 的返回值。
 *
 * <p>调用 {@link #close()} 即退订，之后不再收到该任务的任何事件。close <b>幂等</b>：
 * 重复调用是无害的空操作（用 CAS 保证恰有一次退订生效），调用方不必自己记「退过没有」。
 * 实现了 {@link AutoCloseable}，可在 try-with-resources 里做「订阅周期 = 作用域」的用法。
 *
 * <p>由 {@code TaskSubscriptionRegistry} 创建，一般不需要业务代码自己构造。
 */
public final class Subscription implements AutoCloseable {

    /** 真正执行退订的动作（由注册表在创建时给出）。 */
    private final Runnable unsubscribe;

    /** 幂等闸门：true 表示已退订过。 */
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
