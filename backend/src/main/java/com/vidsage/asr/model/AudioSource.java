package com.vidsage.asr.model;

/**
 * 音频来源抽象：契约只描述「音频从哪里来」，不绑定任何实现方的获取方式。
 *
 * <p>各 Provider 实现自行声明并校验自己支持的子类型；遇到不支持的类型时
 * 抛 {@link IllegalArgumentException}，错误信息必须说明该实现的输入要求
 * 与可行的替代做法（如「先上传生成外链」）。
 */
public sealed interface AudioSource {

    /**
     * 公网可访问的音频 URL（云端异步转写接口通常只接受此种来源）。
     *
     * @param value 完整 URL
     */
    record AudioUrl(String value) implements AudioSource {
    }

    /**
     * 本地文件系统路径（本地推理模型或本机上传型微服务使用）。
     *
     * @param value 绝对或相对路径
     */
    record AudioFilePath(String value) implements AudioSource {
    }
}
