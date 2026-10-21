package com.vidsage.application.subtitle.exception;

/**
 * 任务在、但这一版产物不可用：还没有这一版，或文件已经从工作区消失。
 *
 * <p>与 {@code TaskNotFoundException} 分开：一个是「任务号不命中」，一个是「产物暂时没有」，
 * HTTP 层都映射 404，但消息不能互相冒充。
 */
public class ArtifactNotFoundException extends RuntimeException {

    /**
     * @param taskNo 对外任务号
     * @param type   请求的产物类型
     * @param file   请求的文件名段
     */
    public ArtifactNotFoundException(String taskNo, String type, String file) {
        super("产物不可用：taskNo=" + taskNo + "，type=" + type + "，file=" + file
                + "；该任务还没有这一版产物，或产物文件已被回收，请等主管线跑完或重新生成后再试");
    }
}
