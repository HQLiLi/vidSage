package com.vidsage.domain.workspace.port;

import com.vidsage.domain.workspace.model.ResolvedMediaIdentity;

/**
 * 媒体身份解析：把用户提交的链接算成一个稳定的身份键，据此认领同一份 {@code workspace}。
 *
 * <p>同一个视频，无论是因跟踪参数不同、写成 {@code av} 号，还是短链，都必须算出同一个身份键。
 * <b>认不出视频号时不算失败</b>：回落规范化后的 URL 原文当身份键，调用方照常拿到一个结果。
 */
public interface MediaIdentityResolver {

    /**
     * 解析一条链接的来源身份。
     *
     * @param sourceUri 用户提交的链接原文
     * @return 身份键 + 规范化 URL；认不出视频号时回落到规范化 URL
     * @throws IllegalArgumentException 链接为空白
     */
    ResolvedMediaIdentity resolve(String sourceUri);
}
