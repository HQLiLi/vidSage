package com.vidsage.domain.shared.model;

import java.nio.file.Path;

/**
 * 输入身份的规范化串（值对象）：内容寻址的哈希输入——同一输入必须规范化出同一串、
 * 不同输入必须规范化出不同串；工作区 key（{@link WorkspaceKey#ofHash}）由它哈希而来。
 *
 * <p>两条公式（{@code MediaInput.getContentKey} 按变体分派到这里的静态工厂，
 * 本类不感知 MediaInput）：
 * <ul>
 *   <li><b>远程 URL</b>：{@link #ofRemoteUrl}——URL 原文原样参与哈希（{@code null} 视为空串）；</li>
 *   <li><b>本地文件</b>：{@link #ofLocalFile}——{@code 绝对路径|大小|最后修改时间毫秒}。后两段构成
 *       内容指纹：同一份文件即使被重新拷贝成另一路径，只要大小与时间戳不变就落回同一身份；
 *       文件不存在 / 不可读时后两段取 {@link #UNAVAILABLE} 占位。<b>本类不做文件 I/O</b>——
 *       文件 stat（{@code Files.size} / {@code getLastModifiedTime}）由 application 读取后
 *       以三元组传入，身份公式只做纯计算。</li>
 * </ul>
 *
 * <p>与落库的 {@code source_uri} 列（原始来源描述：URL / 原始文件名）是两个概念：本类是
 * 哈希输入的身份规范化串，只在计算 key 时存在，不落库、不出境。
 *
 * @param value 规范化后的身份串
 */
public record SourceUri(String value) {

    /** 文件不存在或不可读时，大小 / 时间戳的占位值。 */
    public static final long UNAVAILABLE = -1L;

    /** 路径本身为 {@code null} 时的路径段占位值。 */
    private static final String NULL_PATH_PLACEHOLDER = "<null>";

    /**
     * 远程 URL 路线的身份：URL 原文即规范化串。
     *
     * @param url 完整 URL；{@code null} 按空串参与哈希（与原 normalize 公式一致，不做改写）
     * @return 规范化后的身份值对象
     */
    public static SourceUri ofRemoteUrl(String url) {
        return new SourceUri(url == null ? "" : url);
    }

    /**
     * 本地文件路线的身份：绝对路径 + 大小 + 最后修改时间毫秒，以 {@code |} 连接。
     *
     * @param path          本地文件路径；为 {@code null} 时整条身份为占位串
     *                      （{@code "<null>|-1|-1"}）；相对路径先转绝对并 normalize
     * @param size          文件大小（字节）；文件不存在 / 不可读时传 {@link #UNAVAILABLE}
     * @param lastModifiedMs 最后修改时间（毫秒）；文件不存在 / 不可读时传 {@link #UNAVAILABLE}
     * @return 规范化后的身份值对象
     */
    public static SourceUri ofLocalFile(Path path, long size, long lastModifiedMs) {
        if (path == null) {
            return new SourceUri(NULL_PATH_PLACEHOLDER + "|" + UNAVAILABLE + "|" + UNAVAILABLE);
        }
        Path absolute = path.toAbsolutePath().normalize();
        return new SourceUri(absolute + "|" + size + "|" + lastModifiedMs);
    }
}
