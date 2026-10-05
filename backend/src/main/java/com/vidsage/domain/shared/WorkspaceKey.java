package com.vidsage.domain.shared;

import org.springframework.util.StringUtils;

/**
 * 工作区短哈希值对象：内容寻址 key（输入内容 sha256 摘要的前 12 位十六进制，与工作区目录名的
 * key 后缀同宽）。属<b>共享内核</b>——任务上下文（{@code VideoTask} 的 workspace_key 查重列）与
 * 媒体上下文（{@code MediaInput.InWorkspace} 携带的 key）共用同一词汇，放 shared 避免
 * domain/task → domain/media 的跨上下文依赖。
 *
 * <p>值的来源：URL / 本地路径路线取「规范化输入串摘要前 12 位」，上传路线取「文件内容 sha256
 * 前 12 位」，另可经持久化层从数据库行还原。compact constructor 只校验非空白这一条硬不变量，
 * 刻意<b>不做 12 位十六进制的正则强校验</b>——存量数据与测试夹具的取值形态不应被构造器拒之门外；
 * 生成侧的格式由哈希计算点单点保证。
 *
 * <p>流转边界：只在 domain ↔ application ↔ persistence 之间流转——目录名拼接、数据库列等
 * String 场景由调用方用 {@link #value()} 取回原值。
 */
public record WorkspaceKey(String value) {

    /**
     * 校验硬不变量：工作区短哈希非空白。空白说明边界卫语句漏了（未算出内容寻址 key 就往下传），
     * 属服务端缺陷。
     *
     * @throws IllegalStateException value 为 {@code null} 或空白
     */
    public WorkspaceKey {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("workspaceKey 不能为空白：工作区短哈希是输入内容"
                    + " sha256 摘要的前 12 位十六进制，请先算出内容寻址 key 再构造");
        }
    }
}
