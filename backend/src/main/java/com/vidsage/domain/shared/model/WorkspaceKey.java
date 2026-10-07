package com.vidsage.domain.shared.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.util.StringUtils;

/**
 * 工作区短哈希值对象：内容寻址 key（输入内容 sha256 摘要的前 12 位十六进制，与工作区目录名的
 * key 后缀同宽）。属<b>共享内核</b>——任务上下文（{@code VideoTask} 的 workspace_key 查重列）与
 * 媒体上下文（{@code MediaInput.InWorkspace} 携带的 key）共用同一词汇，放 shared 避免
 * domain/task → domain/media 的跨上下文依赖。
 *
 * <p>生成入口只有本类的两个静态工厂（原 {@code WorkspaceService.shortHash} 与
 * {@code VideoTaskService.KEY_LENGTH} 两条同宽公式于此合一）：常规路线 {@link #ofHash}
 * （身份串 → sha256 → key）与上传路线 {@link #ofDigest}（流式边写边算出的摘要 → key）。
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

    /** sha256 摘要取前多少位十六进制做 workspace_key / 目录后缀；全项目宽度唯一定义点。 */
    public static final int KEY_WIDTH = 12;

    /** 12 位十六进制对应的摘要字节数（每字节 2 个 hex 字符）。 */
    private static final int DIGEST_BYTES = KEY_WIDTH / 2;

    /**
     * 常规路线：规范化输入串 → sha256 前 12 位十六进制，包成 {@link WorkspaceKey}。
     * 12 位已足够避免单机任务目录碰撞。原 {@code WorkspaceService.shortHash} 吸收于此。
     *
     * @param value 规范化输入串（{@code SourceUri.value()}）
     * @return 12 位十六进制摘要（工作区短哈希值对象）
     * @throws IllegalStateException 运行环境被裁剪到不支持 SHA-256（标准 JVM 必带）
     */
    public static WorkspaceKey ofHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return ofDigest(digest);
        } catch (NoSuchAlgorithmException e) {
            // 标准 JVM 必带 SHA-256，走到这里说明运行环境被裁剪过
            throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法计算任务工作目录："
                    + "请使用标准 JDK 运行本服务", e);
        }
    }

    /**
     * 上传路线：已算好的 sha256 摘要 → 前 12 位十六进制，包成 {@link WorkspaceKey}。
     * 上传流式落盘是<b>边写边算</b>，内容摘要到手时不再二次哈希（对摘要再哈希会算出另一个 key）。
     *
     * @param sha256Digest 内容的 sha256 摘要（如 {@code MessageDigest.digest()} 的出参）
     * @return 12 位十六进制摘要（工作区短哈希值对象）
     */
    public static WorkspaceKey ofDigest(byte[] sha256Digest) {
        return new WorkspaceKey(HexFormat.of().formatHex(sha256Digest, 0, DIGEST_BYTES));
    }

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
