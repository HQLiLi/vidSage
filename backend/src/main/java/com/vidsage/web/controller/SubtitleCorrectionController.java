package com.vidsage.web.controller;

import com.vidsage.application.subtitle.SubtitleCorrectionService;
import com.vidsage.application.subtitle.query.CorrectionView;
import com.vidsage.web.dto.ClearStaleCorrectionsResponse;
import com.vidsage.web.dto.SaveCorrectionRequest;
import com.vidsage.web.dto.SubtitleCorrectionListResponse;
import com.vidsage.web.dto.SubtitleCorrectionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字幕手动纠错的 HTTP 入口：查询、保存、删除单条、清理失效。
 *
 * <p>本类只做「接收 → 卫语句 → 委托 → 返回」。纠错是覆盖层，<b>磁盘上的产物不动</b>；
 * 保存后读到的是「机器文本 + 用户改动」，删除即恢复机器文本。
 */
@Slf4j
@Tag(name = "字幕纠错", description = "对字幕原文 / 译文的手动纠错：保存即覆盖、删除即恢复机器文本；"
        + "字幕重新生成后旧条目会标记为失效，可一次清理")
@RestController
@RequestMapping("/api/tasks")
public class SubtitleCorrectionController {

    private final SubtitleCorrectionService correctionService;

    /**
     * @param correctionService 纠错用例服务
     */
    public SubtitleCorrectionController(SubtitleCorrectionService correctionService) {
        this.correctionService = correctionService;
    }

    /**
     * 查该任务的全部纠错，带「还对不对得上当前字幕」的判据。
     *
     * @param taskNo 提交响应返回的任务号
     * @return 纠错列表（外面包一层 {@code items}）
     * @throws IllegalArgumentException                                     taskNo 为空（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     */
    @Operation(summary = "查询字幕纠错", description = "返回该任务全部纠错；每条带 matched（当前字幕里还有没有这个条目）"
            + "与 sourceChanged（机器源文本是否已变，需要复核）；任务不存在返回 404")
    @GetMapping("/{taskNo}/corrections")
    public SubtitleCorrectionListResponse list(@PathVariable String taskNo) {
        validateTaskNo(taskNo);
        return SubtitleCorrectionListResponse.from(correctionService.list(taskNo));
    }

    /**
     * 保存一条纠错：同一段同一项重复保存就是覆盖，不追加历史。
     *
     * @param taskNo    提交响应返回的任务号
     * @param segmentId 字幕段 id，形如 {@code seg-0-3520}
     * @param request   请求体（field 必填、value 必填、译文纠错 lang 必填）
     * @return 200 与该条纠错（{@code matched=true}）
     * @throws IllegalArgumentException                                    参数不合法 / 该语种没有可用译文（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     * @throws com.vidsage.application.task.exception.TaskConflictException 该任务正在跑（409）
     * @throws com.vidsage.application.subtitle.exception.CorrectionSegmentOutdatedException 当前字幕里没有这个条目（409），
     *                                                                                        或译文版本比原文旧（也是 409）
     */
    @Operation(summary = "保存一条字幕纠错", description = "body 为 {field, lang?, value}：field 取 text（改原文）或 translation（改译文，"
            + "此时 lang 必填且该语种要有完好译文）；重复保存同一段同一项即覆盖。"
            + "空内容 / 超过 2000 字符 / 缺 lang 返回 400；任务正在跑返回 409；"
            + "segmentId 已不在当前字幕里返回 409（提示刷新后重试）；"
            + "该语种译文里没有这个条目返回 409（译文比原文旧，提示重新翻译后再改）")
    @PutMapping("/{taskNo}/corrections/{segmentId}")
    public SubtitleCorrectionResponse save(@PathVariable String taskNo, @PathVariable String segmentId,
                                           @RequestBody SaveCorrectionRequest request) {
        validateTaskNo(taskNo);
        validateSegmentId(segmentId);
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空：请传 {field, lang?, value}");
        }
        CorrectionView saved = correctionService.save(taskNo, segmentId,
                request.field(), request.lang(), request.value());
        log.info("收到字幕纠错保存: taskNo={}, segmentId={}", taskNo, segmentId);
        return SubtitleCorrectionResponse.from(saved);
    }

    /**
     * 删除一条纠错（恢复机器文本）；删不存在的条目也是成功。
     *
     * @param taskNo    提交响应返回的任务号
     * @param segmentId 字幕段 id
     * @param field     原文还是译文：{@code text} / {@code translation}
     * @param lang      译文纠错的目标语种短码；{@code field=text} 时可留空
     * @throws IllegalArgumentException                                    参数不合法（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     * @throws com.vidsage.application.task.exception.TaskConflictException 该任务正在跑（409）
     */
    @Operation(summary = "删除一条字幕纠错", description = "按 ?field=text|translation（译文再带 &lang=en）删除；"
            + "删除即恢复机器文本，条目不存在的也是 204；任务正在跑返回 409")
    @DeleteMapping("/{taskNo}/corrections/{segmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String taskNo, @PathVariable String segmentId,
                       @RequestParam("field") String field,
                       @RequestParam(value = "lang", required = false) String lang) {
        validateTaskNo(taskNo);
        validateSegmentId(segmentId);
        correctionService.delete(taskNo, segmentId, field, lang);
        log.info("收到字幕纠错删除: taskNo={}, segmentId={}, field={}", taskNo, segmentId, field);
    }

    /**
     * 清理失效纠错：只删当前字幕里已经找不到的条目。
     *
     * <p><b>必须显式传 {@code staleOnly=true}</b>：本端点不支持「清空全部」，要求一个明确的开关，
     * 免得误调用把还能用的纠错一起删了。
     *
     * @param taskNo    提交响应返回的任务号
     * @param staleOnly 必须是 {@code true}
     * @return 200 与 {@code {removed: N}}
     * @throws IllegalArgumentException                                    没传 staleOnly=true（400）
     * @throws com.vidsage.application.task.exception.TaskNotFoundException 任务不存在（404）
     * @throws com.vidsage.application.task.exception.TaskConflictException 该任务正在跑（409）
     */
    @Operation(summary = "清理失效字幕纠错", description = "只删 matched=false 的条目（字幕重新生成后旧条目失效）；"
            + "必须传 ?staleOnly=true，否则 400；返回 {removed: 删除条数}；任务正在跑返回 409")
    @DeleteMapping("/{taskNo}/corrections")
    public ClearStaleCorrectionsResponse clearStale(
            @PathVariable String taskNo,
            @RequestParam(value = "staleOnly", required = false) Boolean staleOnly) {
        validateTaskNo(taskNo);
        if (!Boolean.TRUE.equals(staleOnly)) {
            throw new IllegalArgumentException("只支持清理失效纠错：请传 ?staleOnly=true");
        }
        int removed = correctionService.clearStale(taskNo);
        log.info("收到失效纠错清理: taskNo={}, removed={}", taskNo, removed);
        return new ClearStaleCorrectionsResponse(removed);
    }

    private static void validateTaskNo(String taskNo) {
        if (!StringUtils.hasText(taskNo)) {
            throw new IllegalArgumentException("taskNo 不能为空：请传提交任务时响应里的 taskNo");
        }
    }

    private static void validateSegmentId(String segmentId) {
        if (!StringUtils.hasText(segmentId)) {
            throw new IllegalArgumentException("segmentId 不能为空：请传字幕段 id（形如 seg-0-3520）");
        }
    }
}
