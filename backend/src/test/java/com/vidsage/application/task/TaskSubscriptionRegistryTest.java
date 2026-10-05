package com.vidsage.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vidsage.domain.shared.StageEvent;
import com.vidsage.domain.task.TaskStatus;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link TaskSubscriptionRegistry} 的纯单元测试：不起 Spring 容器，直接 new。
 *
 * <p>覆盖「订阅 / 广播 / 退订 / 终态收口」的主路径与并发形态：注册表是管线（虚拟线程）与
 * HTTP 线程共享的并发构件，CopyOnWrite 语义下「广播遍历快照、退订改副本」的互不阻塞要用
 * 多线程用例钉住；单个订阅者失败不连累别人（管线的编排路径上绝不能被写失败的 emitter 拖垮）。
 */
class TaskSubscriptionRegistryTest {

    private final TaskSubscriptionRegistry registry = new TaskSubscriptionRegistry();

    private static TaskProgressMessage event(TaskStatus status) {
        return new TaskProgressMessage("t0", status,
                new StageEvent("clean", 50, "第 1/2 批", StageEvent.StageState.RUNNING), null);
    }

    // ---------- 订阅与广播 ----------

    @Test
    void subscribeThenBroadcastDeliversEventToSubscriber() {
        List<TaskProgressMessage> received = new ArrayList<>();

        registry.subscribe("t0", received::add);
        registry.broadcast("t0", event(TaskStatus.RUNNING));

        assertEquals(1, received.size());
        assertEquals(TaskStatus.RUNNING, received.get(0).taskStatus());
        assertEquals("t0", received.get(0).taskNo());
    }

    @Test
    void broadcastDeliversToEverySubscriberOfTheSameTask() {
        List<TaskProgressMessage> first = new ArrayList<>();
        List<TaskProgressMessage> second = new ArrayList<>();
        registry.subscribe("t0", first::add);
        registry.subscribe("t0", second::add);

        registry.broadcast("t0", event(TaskStatus.RUNNING));

        assertEquals(1, first.size());
        assertEquals(1, second.size(), "同任务的每个订阅者都应收到事件");
    }

    @Test
    void subscribersAreIsolatedPerTaskNo() {
        List<TaskProgressMessage> forT0 = new ArrayList<>();
        List<TaskProgressMessage> forT1 = new ArrayList<>();
        registry.subscribe("t0", forT0::add);
        registry.subscribe("t1", forT1::add);

        registry.broadcast("t0", event(TaskStatus.RUNNING));

        assertEquals(1, forT0.size());
        assertEquals(0, forT1.size(), "其他任务的事件不得串台");
    }

    @Test
    void broadcastWithNoSubscribersIsNoOp() {
        assertDoesNotThrow(() -> registry.broadcast("t0", event(TaskStatus.RUNNING)));
    }

    @Test
    void broadcastAfterCompleteIsNoOp() {
        List<TaskProgressMessage> received = new ArrayList<>();
        registry.subscribe("t0", received::add);
        registry.complete("t0");

        registry.broadcast("t0", event(TaskStatus.RUNNING));

        assertEquals(0, received.size(), "终态收口后不应再向订阅者投递事件");
    }

    @Test
    void subscribeAfterCompleteWorksForFreshSubscription() {
        registry.subscribe("t0", event -> {
        });
        registry.complete("t0");
        List<TaskProgressMessage> received = new ArrayList<>();

        registry.subscribe("t0", received::add);
        registry.broadcast("t0", event(TaskStatus.RUNNING));

        assertEquals(1, received.size(), "complete 只清当时的订阅者，新订阅应正常收事件");
    }

    // ---------- 退订 ----------

    @Test
    void closeStopsDeliveryToThatSubscriberOnly() {
        List<TaskProgressMessage> stays = new ArrayList<>();
        List<TaskProgressMessage> leaves = new ArrayList<>();
        Subscription keep = registry.subscribe("t0", stays::add);
        Subscription leave = registry.subscribe("t0", leaves::add);

        leave.close();
        registry.broadcast("t0", event(TaskStatus.RUNNING));

        assertEquals(1, stays.size(), "未退订的订阅者照常收事件");
        assertEquals(0, leaves.size(), "退订后不再收事件");
        assertTrue(!keep.isClosed());
    }

    @Test
    void closeIsIdempotent() {
        List<TaskProgressMessage> received = new ArrayList<>();
        Subscription subscription = registry.subscribe("t0", received::add);

        subscription.close();
        subscription.close();   // 第二次必须是无操作，不得抛异常

        registry.broadcast("t0", event(TaskStatus.RUNNING));
        assertEquals(0, received.size());
    }

    // ---------- 单订阅者失败隔离 ----------

    @Test
    void broadcastSwallowsSubscriberFailureAndDeliversToOthers() {
        List<TaskProgressMessage> healthy = new ArrayList<>();
        registry.subscribe("t0", event -> {
            throw new IllegalStateException("模拟写失败的 emitter");
        });
        registry.subscribe("t0", healthy::add);

        assertDoesNotThrow(() -> registry.broadcast("t0", event(TaskStatus.RUNNING)));

        assertEquals(1, healthy.size(), "坏订阅者不得连累其他订阅者收到事件");
    }

    // ---------- 卫语句 ----------

    @Test
    void rejectsBlankTaskNo() {
        assertThrows(IllegalArgumentException.class, () -> registry.subscribe(" ", event -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> registry.broadcast("", event(TaskStatus.RUNNING)));
        assertThrows(IllegalArgumentException.class, () -> registry.complete(null));
    }

    @Test
    void rejectsNullConsumerAndNullEvent() {
        assertThrows(IllegalArgumentException.class, () -> registry.subscribe("t0", null));
        assertThrows(IllegalArgumentException.class, () -> registry.broadcast("t0", null));
    }

    // ---------- 并发形态 ----------

    @Test
    void concurrentSubscribesAndBroadcastsDeliverEverythingToEachSubscriber() throws Exception {
        int subscriberCount = 8;
        int eventCount = 200;
        List<List<TaskProgressMessage>> boxes = new ArrayList<>();
        for (int i = 0; i < subscriberCount; i++) {
            boxes.add(new CopyOnWriteArrayList<>());
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch subscribed = new CountDownLatch(subscriberCount);
            for (List<TaskProgressMessage> box : boxes) {
                pool.execute(() -> {
                    registry.subscribe("t0", box::add);
                    subscribed.countDown();
                });
            }
            assertTrue(subscribed.await(5, TimeUnit.SECONDS), "全部订阅应完成");

            // 广播跑在「另一条线程」（模拟管线的虚拟线程）上，与订阅并发
            Thread broadcaster = new Thread(() -> {
                for (int i = 0; i < eventCount; i++) {
                    registry.broadcast("t0", event(TaskStatus.RUNNING));
                }
            });
            broadcaster.start();
            broadcaster.join(10_000);

        } finally {
            pool.shutdownNow();
        }
        for (int i = 0; i < subscriberCount; i++) {
            final int index = i;
            assertEquals(eventCount, boxes.get(index).size(),
                    () -> "订阅者 " + index + " 应完整收到全部事件，实际 " + boxes.get(index).size());
        }
    }

    /**
     * 断言「动作不抛异常」的最小表述：注册表对外的广播 / 收口路径承诺不上抛。
     */
    private static void assertDoesNotThrow(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            throw new AssertionError("不应抛出异常：" + e.getMessage(), e);
        }
    }
}
