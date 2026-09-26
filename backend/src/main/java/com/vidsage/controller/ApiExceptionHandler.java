package com.vidsage.controller;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST 全局异常处理：把调用方错误映射为 400、环境 / 外部故障映射为 500，
 * 响应体统一为 {@code { "error": message }}。
 *
 * <p><b>为什么现在才提取</b>：{@code error-handling.md} 预告了这一步——「暂不建全局
 * {@code @ControllerAdvice}：等出现第二个 Controller、局部处理开始重复时再提取」。
 * 本任务新增的 {@code MediaController} 正是第二个 Controller，因此把原先
 * {@code AsrController} 内部的局部 {@code @ExceptionHandler} 收敛到这里。响应形状与那时的
 * 局部处理完全一致（都是 {@code Map.of("error", message)}），故 {@code AsrController} 的行为不变。
 *
 * <p><b>职责边界</b>：只做「已处理异常 → HTTP 响应」的映射，处理完即返回、<b>不再上抛</b>，
 * 因此不存在「先 log 再原样上抛」的双重记录问题（那是 {@code logging-guidelines.md} 的反模式）。
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 调用方传参不合法（错在调用方）→ 400，记 WARN。
     *
     * <p>WARN 而非 ERROR：此类失败是使用者输入问题，不是服务端故障，不该触发 ERROR 告警。
     *
     * @param e 卫语句抛出的 {@link IllegalArgumentException}
     * @return 错误体 {@code { "error": message }}；message 说明「缺什么 + 怎么补」
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("请求参数不合法: {}", errorMessage(e));
        return Map.of("error", errorMessage(e));
    }

    /**
     * 配置缺失或外部系统未按预期返回（错在环境 / 外部系统）→ 500，记 ERROR 并<b>带上异常对象</b>
     * （保留调用栈供定位）。
     *
     * @param e 外部工具缺失、外部命令失败或超时抛出的 {@link IllegalStateException}
     * @return 错误体 {@code { "error": message }}
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> handleIllegalState(IllegalStateException e) {
        log.error("服务端处理失败: {}", errorMessage(e), e);
        return Map.of("error", errorMessage(e));
    }

    /**
     * 异常消息：{@code getMessage()} 可能为 {@code null}，此时退回简单类名，
     * 避免响应体里出现字面量 "null"（{@code Map.of} 也不接受 null 值，本方法同时兜住这一点）。
     */
    private static String errorMessage(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
