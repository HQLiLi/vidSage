package com.vidsage.web.controller;

import com.vidsage.application.task.exception.TaskConflictException;
import com.vidsage.application.task.exception.TaskNotFoundException;
import com.vidsage.domain.exception.UnsupportedLanguageException;
import com.vidsage.web.dto.TaskConflictResponse;
import com.vidsage.web.dto.TaskResponse;

import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST 全局异常处理：把各层抛出的异常映射为对应的 HTTP 状态与统一错误体
 * {@code { "error": message }}。
 *
 * <p><b>职责边界</b>：只做「已处理异常 → HTTP 响应」的映射，处理完即返回、<b>不再上抛</b>。
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
     * 目标语种不在支持列表（错在调用方）→ 400，记 WARN。
     *
     * @param e 语种解析失败异常
     * @return 错误体 {@code { "error": message }}
     */
    @ExceptionHandler(UnsupportedLanguageException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleUnsupportedLanguage(UnsupportedLanguageException e) {
        log.warn("目标语种不支持: {}", errorMessage(e));
        return Map.of("error", errorMessage(e));
    }


    /**
     * taskNo 对应的任务不存在（错在调用方传了未知的任务号）→ 404，记 WARN。
     *
     * <p>与「参数不合法」（400）的区别：请求格式没错，只是任务号不命中。
     *
     * @param e 任务不存在异常（查询 / SSE 订阅 / 产物下载统一抛出）
     * @return 错误体 {@code { "error": message }}；message 提示核对 taskNo
     */
    @ExceptionHandler(TaskNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleTaskNotFound(TaskNotFoundException e) {
        log.warn("任务不存在: {}", errorMessage(e));
        return Map.of("error", errorMessage(e));
    }

    /**
     * 冲突（错在调用方）→ 409，记 WARN。两个场景共用本映射：提交查重命中、
     * 同工作区已有后置会话（重新生成字幕）在跑——故日志文案写「冲突」不写「重复提交」，
     * 具体是哪种由异常携带的 message 说明。
     *
     * <p>响应体是 {@code { "error": ... }} 的有意扩展（{@link TaskConflictResponse}）：冲突的可操作
     * 出路是「改用既有任务」，光有文字说明不够，把既有任务视图一并返回。异常载荷是应用层读模型
     * {@code TaskSnapshot}（零 web 注解），经 {@link TaskResponse#from} 映射为对外响应。
     *
     * @param e 冲突异常（携带既有任务快照）
     * @return 冲突体：错误说明 + 既有任务视图
     */
    @ExceptionHandler(TaskConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public TaskConflictResponse handleTaskConflict(TaskConflictException e) {
        log.warn("任务冲突被拒绝: {}", errorMessage(e));
        return new TaskConflictResponse(errorMessage(e), TaskResponse.from(e.getExistingTask()));
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
