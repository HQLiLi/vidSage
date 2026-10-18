package com.vidsage.domain.task.model;

import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * 对外任务号值对象：32 位短 UUID 语义（去掉连字符的随机 UUID，恰好装进 {@code task.task_no} 的
 * varchar(32)）。由 {@link #random()} 在任务创建时生成，此后全生命周期不变。
 *
 * <p>值对象的价值在于「让非法任务号造不出来」，但唯一的硬不变量是「非空白」：刻意<b>不做 32 位
 * 十六进制的正则强校验</b>——任务号还会经 {@code restore} 从数据库行还原，存量数据与测试夹具
 * 的任意取值形态不应被构造器拒之门外。
 *
 * <p>流转边界：只在 domain ↔ application ↔ persistence 之间流转，对外一律是 {@code String}
 * （JSON 字段、web 路径变量、SSE 消息），应用服务在边界处用 {@link #value()} 取回原值。
 */
public record TaskNo(String value) {

    /**
     * 校验硬不变量：任务号非空白。空白说明边界卫语句漏了（空值一路传到了领域层），属服务端缺陷。
     *
     * @throws IllegalStateException value 为 {@code null} 或空白
     */
    public TaskNo {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("taskNo 不能为空白：对外任务号是 32 位短 UUID"
                    + "（去掉连字符的随机 UUID），请检查上游是否漏传了任务号");
        }
    }

    /**
     * 凭空生成一个新的对外任务号：32 位十六进制（去掉连字符的随机 UUID），装进 varchar(32)。
     *
     * <p>不套 {@code of} 前缀——{@code of(x)} 在本仓读作「由 x 构成」，凭空生成没有「由谁而来」，
     * 故按来源命名（同 {@code SubtitleContext.empty()} / {@code TermLookupResult.none()} 一族）。
     *
     * @return 新任务号
     */
    public static TaskNo random() {
        return new TaskNo(UUID.randomUUID().toString().replace("-", ""));
    }
}
