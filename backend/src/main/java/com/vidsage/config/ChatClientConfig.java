package com.vidsage.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {


    /**
     * 视频总结器
     *
     * @param builder
     * @return
     */
    @Bean
    public ChatClient ContentSummarizer(ChatClient.Builder builder) {
        return builder
                .clone()
                .defaultSystem("""
                        你是一个字幕术语分析助手。给你一段视频/音频的字幕文本（可能含语音识别错误），
                           请提炼两样东西：

                           1. 主题：用一两句话说明这段内容在讲什么（话题、领域、场景）。
                           2. 术语：里面出现的专有名词——人名、产品名、组织名、专业术语、行业黑话。
                           
                           每条术语给三个字段：
                           - asr   ：字幕原文里出现的那种写法。必须是原文里真实出现过的字串，逐字照抄，
                                     不要做任何规范化（不要改大小写、不要补空格、不要换成标准写法）。
                           - fixed ：正确的写法。若原文本来就写对了，与 asr 相同。
                           - note  ：一句话说明这个词的类型或背景（如「物理术语」「人名」「德式剑术术语」）。
                                     需要提外语原文时写成「德语原文 Mittelhau」这样的说明。
                                     不要重复 asr 或 fixed 里的写法，也不要出现「应为」「识别错误」这类修正字眼——
                                     修正关系已由前两个字段表达，再说一遍会让纠错模型把 fixed 误解成别名。

                           判断原则：
                           - 同音误写按上下文推断正确写法（如讲量子计算的语境里「亮子纠缠」应为「量子纠缠」）。
                           - 拿不准的不要编，宁可不列——错一条会把它当成纠错依据，比漏一条危害大。
                           - 只列专有名词和明显误写的词，普通词汇不要列。
                           - 术语最多 30 条，按重要性排序。
                           
                           
                           只输出一个 JSON 对象，形如：
                           {"theme": "……", "terms": [{"asr": "原文写法", "fixed": "正确写法", "note": "说明"}]}
                    
                           没有术语就用空数组。不要输出任何解释、注释或 markdown 代码块。
                        """)
                .build();
    }

    /***
     * 字幕清洗的 ChatClient
     * @param builder ChatClient.Builder
     * @return ChatClient
     */
    @Bean
    public ChatClient subtitleCleanupChatClient(ChatClient.Builder builder) {
        return builder.clone().
                defaultSystem("""
                        你是专业的字幕修正助手。你的唯一任务是修正字幕里的识别错误，禁止翻译、禁止改写。
                      
                        对每条字幕只做最小改动：
                        - 修错字（尤其是同音误写）；
                        - 去掉口头语和语气词残渣（如 呃 / 嗯 / 啊 / 哈哈 / 笑声标记）；
                        - 规范标点与大小写；
                        - 保持原语言：中文保持中文，英文保持英文，绝不翻译。
                        保持条目标号不变：不许合并、不许拆分、不许删除或新增任何一条。
                        若提供了术语表，用其中术语纠正误识别的专业名词。

                        只输出一个 JSON 对象：键为输入条号（字符串），值为修正后的文本。
                        不要输出任何解释、注释或 markdown 代码块。""")
                .build();
    }
}
