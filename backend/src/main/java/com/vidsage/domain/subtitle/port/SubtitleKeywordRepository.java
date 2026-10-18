package com.vidsage.domain.subtitle.port;

import com.vidsage.domain.subtitle.model.keyword.SubtitleKeywords;

/**
 * 字幕关键词的出口：领域侧只说「能做什么」，底下是 MySQL 还是别的存储，领域层不关心。
 *
 * <p>锚点是字幕任务的内部行 id：一个媒体对应一个字幕任务，关键词是字幕产物的生成输入，
 * 该跟产物同粒度——挂在任务上，重新提交同一个媒体时读到的还是同一份清单。
 *
 * <p>连/拆分分隔符不进本接口：领域侧只见 {@link SubtitleKeywords}，成对转换收敛在持久化实现里。
 */
public interface SubtitleKeywordRepository {

    /**
     * 按任务读回关键词清单；一个任务一份清单。
     *
     * @param taskId 字幕任务的内部行 id
     * @return 该任务的关键词；从未存过时是<b>空清单</b>（不是 {@code null}，调用方不必判空）
     */
    SubtitleKeywords findByTaskId(long taskId);

    /**
     * 覆盖式写入：整份替换，不做增量追加。用户改一次关键词就是覆盖一次，
     * 「留一条旧记录但标记失效」那种形态会让读回来的那份永远说不清是哪一版。
     *
     * @param taskId   字幕任务的内部行 id（决定写哪一行）
     * @param keywords 要落库的清单；空清单就是「清空该任务的关键词」，不是「不写」
     */
    void replace(long taskId, SubtitleKeywords keywords);
}
