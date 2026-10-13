package com.vidsage.domain.subtitle.port;

import com.vidsage.domain.subtitle.model.term.TermLookupCommand;
import com.vidsage.domain.subtitle.model.term.TermLookupResult;

/**
 * 术语译名查证：查一个专有名词在目标语种里的官方/通用译名。
 *
 * <p>这是「同一能力、多种来源」的缝（见 provider-pattern）：具体来源可替换——接独立搜索 API
 * 或其他带检索的模型时各成一个实现，由 {@code vidsage.term-lookup.provider} 配置切换。
 *
 * <p>触发确定性（哪些词要查、什么时机查）由上层编排负责，<b>不属于本接口</b>：
 * 「要不要查」不能交给人或模型临场决定——模型不觉得自己在猜，给不出可依据的信号。
 *
 * <p>失败语义：查不到（含证据不全被弃权）返回 {@link TermLookupResult#none()}，<b>不抛异常</b>；
 * 网络 / 配置 / 响应无法解析等失败直接抛运行时异常，由上层决定降级。两类结论必须可区分——
 * 前者要持久化（不再重搜），后者保持未查证（下次补跑）。
 */
public interface TermLookupProvider {

    /**
     * 查证一条术语的目标语种译名。
     *
     * @param command 术语、目标语种与消歧主题；term 与 targetLanguage 必须非空白
     * @return 查证结论；查不到时为 {@link TermLookupResult#none()}
     * @throws IllegalArgumentException command 的必填字段空白
     * @throws IllegalStateException    配置缺失（如 API key）
     * @throws RuntimeException         外部调用失败或响应无法解析
     */
    TermLookupResult lookup(TermLookupCommand command);
}
