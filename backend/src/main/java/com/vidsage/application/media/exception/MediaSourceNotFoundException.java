package com.vidsage.application.media.exception;

/**
 * 任务在、但没有可播放的媒体源：还没跑出媒体文件，或文件已经被回收。
 *
 * <p>与 {@code TaskNotFoundException} 分开：一个是「任务号不命中」，一个是「任务在、媒体暂时没有」，
 * HTTP 层都映射 404，但消息不能互相冒充。
 */
public class MediaSourceNotFoundException extends RuntimeException {

    /**
     * @param taskNo 对外任务号
     */
    public MediaSourceNotFoundException(String taskNo) {
        super("媒体源不可用：taskNo=" + taskNo
                + "；该任务还没有可播放的媒体源，或媒体文件已被回收，请等任务跑完或重新生成后再试");
    }
}
