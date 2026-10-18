package com.vidsage.application.task;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 进程启动时收口上个进程留下的残留 run：启动后跑一次
 * {@link TaskRunSupervisor#recoverInterruptedRuns()}。
 *
 * <p>挂在 {@link ApplicationRunner} 上——Spring Boot 标准做法，收口痕迹在启动日志里看得见。
 *
 * <p><b>已知代价</b>：此刻连接器已在监听，窗口是毫秒级——撞上的请求会看到一行其实已死的
 * {@code RUNNING}（提交拿到 409，重试即可）。单实例假设下接受。
 */
@Component
public class TaskRunStartupRecovery implements ApplicationRunner {

    private final TaskRunSupervisor supervisor;

    public TaskRunStartupRecovery(TaskRunSupervisor supervisor) {
        this.supervisor = supervisor;
    }

    @Override
    public void run(ApplicationArguments args) {
        supervisor.recoverInterruptedRuns();
    }
}
