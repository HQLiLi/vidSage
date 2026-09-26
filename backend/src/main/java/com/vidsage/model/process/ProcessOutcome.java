package com.vidsage.model.process;

import java.util.List;

/**
 * 一次外部进程调用的结果快照。<b>非零退出码也会正常返回本类型，不抛异常</b>——
 * 外部命令的退出码语义极弱（同一个码值可能对应多种失败），错误分类交给调用方。
 *
 * @param exitCode  进程退出码；<b>超时被强制结束时固定为 -1</b>（此时真实退出码不可靠）
 * @param stdout    标准输出全文，已按本机原生编码解码
 * @param stderr    标准错误全文，已按本机原生编码解码
 * @param command   实际执行的命令行（原样回传，便于调用方复现与排查）
 * @param timedOut  是否因超时被强制结束
 * @param elapsedMs 从启动到结束（或超时回收）的耗时，毫秒
 */
public record ProcessOutcome(int exitCode, String stdout, String stderr,
                             List<String> command, boolean timedOut, long elapsedMs) {
}
