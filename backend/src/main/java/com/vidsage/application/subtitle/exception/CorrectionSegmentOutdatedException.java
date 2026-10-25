package com.vidsage.application.subtitle.exception;

/**
 * 要改的字幕条目已经不在当前字幕里（字幕重新生成过，切分变了）。
 *
 * <p><b>为什么不复用 {@code TaskConflictException}</b>：那个的 409 响应体是 {@code {taskNo, runNo}}、
 * 不含文案，可这里的用户需要读到「刷新后重试」这句话。所以单开一个类型，走通用 {@code {error}} 形状。
 */
public class CorrectionSegmentOutdatedException extends RuntimeException {

    /**
     * @param taskNo    对外任务号
     * @param segmentId 客户端想改、但当前字幕里已经找不到的段 id
     */
    public CorrectionSegmentOutdatedException(String taskNo, String segmentId) {
        super("字幕条目已过期，请刷新后重试：taskNo=" + taskNo + " 的当前字幕里没有 segmentId=" + segmentId
                + "；可能字幕刚重新生成过，请刷新页面拿到新的条目后再改");
    }

    private CorrectionSegmentOutdatedException(String message) {
        super(message);
    }

    /**
     * 译文纠错专用的「对不上」：当前原文里有这个条目，但该语种译文是照着更旧的原文翻的。
     *
     * <p>为什么不复用上面那句「刷新后重试」：这种情况刷新页面没用，得先把译文重翻一遍，
     * 否则用户会陷进「刷新 → 还是改不了」的死循环。
     *
     * @param taskNo    对外任务号
     * @param segmentId 客户端想改的段 id
     * @param lang      目标语种短码
     * @return 提示「重新翻译后再改」的 409 异常
     */
    public static CorrectionSegmentOutdatedException forOutdatedTranslation(
            String taskNo, String segmentId, String lang) {
        return new CorrectionSegmentOutdatedException(
                "译文字幕版本比原文旧，请重新翻译后再改：taskNo=" + taskNo + " 的 " + lang
                        + " 译文里没有 segmentId=" + segmentId
                        + "；这份译文是照着一版更旧的原文翻的，刷新页面也不会好。"
                        + "请先重新翻译（POST /api/tasks/" + taskNo + "/translate）让译文跟上当前原文后再改");
    }
}
