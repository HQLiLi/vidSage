package com.vidsage.infrastructure.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link SemaphoreTaskConcurrencyGate} 的两条命门：
 * ① 排队被打断时**不释放**许可（错放 = 并发上限悄悄变大）；
 * ② body 抛异常时**仍释放**许可（漏放 = 队列永久堵死，后面的任务永远排不上）。
 *
 * <p>用「自控执行器」注入：线程池仍按任务建虚拟线程，但工厂把每根线程记下来——测试靠它抓住
 * 排队中的那根去 interrupt。这是测试替身；生产装配走 {@code TaskProperties} 构造器。
 */
class SemaphoreTaskConcurrencyGateTest {

    /**
     * 按任务建虚拟线程，顺手做两件事：记下每根线程、把逃出线程的异常收进 failures。
     * 后者是必须的——虚拟线程上的 {@code fail(...)} 不会让本测试变红（JUnit 只看测试线程），
     * 不收起来就会出现「body 明明跑了、测试照样绿」。
     */
    private static ExecutorService recordingExecutor(List<Thread> created, List<Throwable> failures) {
        return Executors.newThreadPerTaskExecutor(r -> {
            Thread thread = Thread.ofVirtual().unstarted(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            created.add(thread);
            return thread;
        });
    }

    private static Runnable unexpectedRun(List<Throwable> failures, String what) {
        return () -> failures.add(new AssertionError(what));
    }

    @Test
    void rejectsMaxConcurrentBelowOne() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new SemaphoreTaskConcurrencyGate(0, recordingExecutor(new CopyOnWriteArrayList<>(),
                        new CopyOnWriteArrayList<>())));
        assertEquals("vidsage.task.max-concurrent 必须 ≥ 1：当前为 0；请修正配置", e.getMessage(),
                "并发数配置不合法要报出当前值与修法，文案逐字钉住");
    }

    @Test
    void bodyExceptionStillReleasesThePermit() throws Exception {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        SemaphoreTaskConcurrencyGate gate =
                new SemaphoreTaskConcurrencyGate(1, recordingExecutor(new CopyOnWriteArrayList<>(), failures));

        gate.submit(() -> {
            throw new IllegalStateException("boom");
        }, unexpectedRun(failures, "占着许可的任务不该被打断"));

        CountDownLatch second = new CountDownLatch(1);
        gate.submit(second::countDown, unexpectedRun(failures, "第二个任务不该被打断"));

        assertTrue(second.await(5, TimeUnit.SECONDS), "body 抛异常后许可必须已释放，否则队列永久堵死");
        // 第一根线程是在 release() **之后**才把异常收进 failures 的，与第二根的 countDown 之间只差纳秒：
        // 紧跟着断言偶尔会读到 0（假红），所以先等它落定再数
        long deadline = System.currentTimeMillis() + 5_000;
        while (failures.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, failures.size(), () -> "闸门不吞 body 的异常，只该有那一个 boom：" + failures);
        assertEquals("boom", failures.get(0).getMessage());
    }

    @Test
    void interruptedQueueWaitCallsBackAndDoesNotReleaseThePermit() throws Exception {
        List<Thread> created = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        SemaphoreTaskConcurrencyGate gate = new SemaphoreTaskConcurrencyGate(1, recordingExecutor(created, failures));

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        gate.submit(() -> {
            firstStarted.countDown();
            try {
                releaseFirst.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add(e);
            }
        }, unexpectedRun(failures, "占着配额的那个不该被打断"));
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "第一个任务应拿到许可并开跑");

        CountDownLatch interrupted = new CountDownLatch(1);
        gate.submit(unexpectedRun(failures, "排队被打断的 body 不该跑"), interrupted::countDown);
        assertEquals(2, created.size(), "第二次提交应已建出第二根虚拟线程（它随即阻塞在 acquire 上）");
        created.get(1).interrupt();
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "排队被打断应触发回调");

        // 许可要是被错放了，第三个会立刻跑起来（微秒级）。用「等一段时间仍没跑」钉死它——
        // 改成「提交后立刻读计数」会有调度竞态，错放时也可能读不到 0，漏判
        CountDownLatch third = new CountDownLatch(1);
        gate.submit(third::countDown, unexpectedRun(failures, "第三个任务不该被打断"));
        assertFalse(third.await(300, TimeUnit.MILLISECONDS),
                "中断路径不该释放许可：配额仍被第一个占着，第三个必须还在排队");

        releaseFirst.countDown();
        assertTrue(third.await(5, TimeUnit.SECONDS), "第一个跑完释放许可后，第三个才该跑");
        assertTrue(failures.isEmpty(), () -> "不该有 body 跑起来或断言失败：" + failures);
    }
}
