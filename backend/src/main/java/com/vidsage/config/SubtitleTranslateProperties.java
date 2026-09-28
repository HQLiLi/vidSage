package com.vidsage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 字幕翻译的调参，前缀 {@code vidsage.subtitle.translate}，见 application.yml。
 *
 * <p>与 {@link SubtitleCleanupProperties} <b>故意分成两个类</b>：批参数（句数 / 字符上限）的字段名
 * 同形，但校准依据不同（清洗是等长替换，翻译是膨胀任务），合并会让将来只调一边的人误改另一边。
 *
 * @param targetLanguage  目标语种（BCP-47 短码，如 zh / en）。源语种与它相同则跳过翻译、不调用 LLM。
 *                        不引「短码 → 自然语言名」的映射表：短码直接写进提示词，模型自会理解。
 *                        <b>缺席时默认 zh</b>（{@code @DefaultValue}），不会落成空串去拼出坏产物名。
 * @param batchSize       一次丢给 LLM 翻译多少句（一「批」）；与 maxBatchChars 双判据，先到为准。
 *                        这个判据管「一次要模型保证多少条 JSON 键完整」——同样字数下短句越多掉行
 *                        概率越高，而掉行 → 重试 → 延迟翻倍。
 *                        <b>缺席时绑定成 0</b>：内层循环一句都装不进、退化成每批 1 句，
 *                        是静默的行为漂移，所以 yml 里必须显式写。
 * @param maxBatchChars   一次最多多少字。这个判据管「单次请求 / 回复的规模」，是延迟的主旋钮。
 *                        翻译是膨胀任务（输出 token 比输入长 1.5~2 倍），取值应小于清洗的 maxBatchChars。
 *                        <b>缺席时绑定成 0</b>：同样让每批退化成 1 句，必须显式写。
 * @param maxSteps        翻译最多重试轮数（模型掉行 / 给空译文就重来，最多重来几次）。
 *                        <b>缺席时绑定成 0</b>：一次都不会真跑（循环零次 → 整批回退原文），必须显式写。
 * @param historyMaxChars 跨批对话历史的字符上限：把前几批的「批次原文 + 已采纳的译文回复」作为多轮
 *                        历史带给后续批次，是「术语与称谓全片一致」的主要载体。累计超过就从最老的
 *                        一轮开始丢。设为 0 或负数即关闭（退回「每批只看自己」）。按字符估 token。
 *                        <b>缺席时绑定成 0 = 静默关闭</b>，所以 yml 里必须显式写。
 */
@ConfigurationProperties(prefix = "vidsage.subtitle.translate")
public record SubtitleTranslateProperties(
        @DefaultValue("zh") String targetLanguage,
        int batchSize,
        int maxBatchChars,
        int maxSteps,
        int historyMaxChars) {
}
