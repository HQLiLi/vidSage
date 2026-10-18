package com.vidsage.application.task.port;

import com.vidsage.application.task.message.TaskRunProgress;
import com.vidsage.domain.task.model.RunRef;

/**
 * 把一条 run 的进度广播给它的全部连接。
 *
 * <p>只管「发」：连接怎么建、谁能收由 web 层管——HTTP 类型不进本接口，所以「开流」不在端口上，
 * 由 controller 直接调 web 层的实现。
 */
public interface TaskProgressBroadcaster {

    /**
     * 发给这条 run 的全部连接；事件 {@code terminal} 为真时发完即关。
     *
     * @param run   目标 run
     * @param event 要广播的进度事件
     */
    void publish(RunRef run, TaskRunProgress event);

    /**
     * 无条件兜底：关掉这条 run 的全部连接并摘表（落库成败都该走到这里）。
     *
     * @param run 目标 run
     */
    void complete(RunRef run);
}
