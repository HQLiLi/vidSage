package com.vidsage.domain.subtitle.port;

import com.vidsage.domain.shared.model.WorkspaceKey;
import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;

/**
 * 字幕关键词的出口：领域侧只说「能做什么」，底下是 MySQL 还是别的存储，领域层不关心。
 *
 * <p><b>为什么外键是 {@code workspace_key} 而不是 {@code task_no}</b>：关键词是字幕产物的生成输入，
 * 该跟产物同粒度。挂 {@code task_no} 会漏——同一 URL 重新提交会新插一行 task（查重只拦
 * PENDING / RUNNING，{@code workspace_key} 没有唯一约束），新行打开时看到的是<b>同一个目录里那份
 * 用旧关键词生成的字幕</b>，而自己名下一片空白；用户此时点重跑清洗会拿空列表覆盖，把字幕退化成没修过的版本。
 *
 * <p>连/拆分分隔符不进本接口：领域侧只见 {@link SubtitleKeywords}，成对转换收敛在持久化实现里。
 */
public interface SubtitleKeywordRepository {

    /**
     * 按工作区读回关键词清单；一个工作区一份清单。
     *
     * @param workspaceKey 工作区短哈希
     * @return 该工作区的关键词；从未存过时是<b>空清单</b>（不是 {@code null}，调用方不必判空）
     */
    SubtitleKeywords findByWorkspaceKey(WorkspaceKey workspaceKey);

    /**
     * 覆盖式写入：整份替换，不做增量追加。用户改一次关键词就是覆盖一次，
     * 「留一条旧记录但标记失效」那种形态会让读回来的那份永远说不清是哪一版。
     *
     * @param workspaceKey 工作区短哈希（决定写哪一行）
     * @param keywords     要落库的清单；空清单就是「清空该工作区的关键词」，不是「不写」
     */
    void replace(WorkspaceKey workspaceKey, SubtitleKeywords keywords);
}
