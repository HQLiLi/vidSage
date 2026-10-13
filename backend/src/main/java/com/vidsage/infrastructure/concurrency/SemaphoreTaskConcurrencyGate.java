package com.vidsage.infrastructure.concurrency;

import com.vidsage.application.task.port.TaskConcurrencyGate;
import com.vidsage.config.TaskProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;


/**
 * {@link TaskConcurrencyGate} 的进程内实现：一段活占一根虚拟线程，用信号量把同时在跑的限到
 * vidsage.task.max-concurrent 个。
 * <p>超出配额的就阻塞在 acquire 上排队——排队的实体就是那根虚拟线程本身，没有额外的队列结构；
 * 信号量是非公平的（JDK 默认），所以排队不保证先来先跑。
 * body 不管正常结束还是抛异常，都要放掉许可（漏了就把队列永久堵死）；
 * 排队期间被打断时只回调 onQueueInterrupted。
 */
@Component
public class SemaphoreTaskConcurrencyGate implements TaskConcurrencyGate {

    private final Semaphore semaphore;

    private final ExecutorService executorService;

    @Autowired
    public SemaphoreTaskConcurrencyGate(TaskProperties properties) {
        this(properties.maxConcurrent(), Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("vidsage-task-", 0).factory()));
    }

    SemaphoreTaskConcurrencyGate(int maxConcurrent, ExecutorService executorService) {
        if (maxConcurrent < 1) {
            throw new IllegalStateException("vidsage.task.max-concurrent 必须 ≥ 1：当前为 "
                    + maxConcurrent + "；请修正配置");
        }
        this.semaphore = new Semaphore(maxConcurrent);
        this.executorService = executorService;
    }

    /**
     * 提交任务到虚拟线程排队执行。
     *
     * @param body               主任务
     * @param onQueueInterrupted 排队等待时被打断时执行的回调
     */
    @Override
    public void submit(Runnable body, Runnable onQueueInterrupted) {
        executorService.execute(() -> {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                onQueueInterrupted.run();
                return;
            }
            try {
                body.run();
            } finally {
                semaphore.release();
            }
        });
    }
}
