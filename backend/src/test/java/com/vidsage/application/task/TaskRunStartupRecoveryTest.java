package com.vidsage.application.task;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

/**
 * {@link TaskRunStartupRecovery} 的纯单元测试：启动收口确实被调了一次。
 */
class TaskRunStartupRecoveryTest {

    @Test
    void recoversInterruptedRunsOnStartup() {
        TaskRunSupervisor supervisor = mock(TaskRunSupervisor.class);
        TaskRunStartupRecovery recovery = new TaskRunStartupRecovery(supervisor);

        recovery.run(mock(ApplicationArguments.class));

        verify(supervisor).recoverInterruptedRuns();
    }
}
