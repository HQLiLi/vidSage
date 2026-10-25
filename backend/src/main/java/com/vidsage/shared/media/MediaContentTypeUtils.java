package com.vidsage.shared.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 按文件头猜 MIME 类型，猜不出来再退回扩展名。
 *
 * <p>为什么不能只看扩展名：上传路线无论传什么都落成 {@code source.mp4}，一个上传的 mp3 会被
 * 声明成 {@code video/mp4}，浏览器直接拒播。文件头才是真实格式。
 *
 * <p>嗅探失败不打挂播放：读不到文件头就退回扩展名，全认不出才给 {@code application/octet-stream}。
 *
 * <p>为什么自己写而不是用现成的：JDK 的 {@code Files.probeContentType} 交给各平台的文件类型探测，
 * Linux 容器里常常直接返回 {@code null}，结果随环境变化、不能当判据；Apache Tika 能认，
 * 但为这十来个魔数多引一个重依赖不值。就按浏览器要播的那几种格式手写一张表。
 */
public final class MediaContentTypeUtils {

    /** 读这么多字节做判定：这些格式的魔数与 Matroska 的 docType 都落在文件头部很靠前的位置。 */
    private static final int HEADER_BYTES = 64;

    /** 完全认不出时的兜底类型。 */
    private static final String OCTET_STREAM = "application/octet-stream";

    private MediaContentTypeUtils() {
    }

    /**
     * 猜一个媒体文件的 MIME 类型。
     *
     * @param file 本地文件；不要求一定存在
     * @return MIME 字符串，最差也会给出 {@code application/octet-stream}
     */
    public static String detect(Path file) {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(HEADER_BYTES);
        } catch (IOException e) {
            // 嗅探只是锦上添花，读不到就按扩展名兜底，不让它把播放打挂
            return fromFileName(file);
        }
        String byHeader = fromHeader(head);
        return byHeader != null ? byHeader : fromFileName(file);
    }

    /**
     * 按文件头的前几十个字节判定格式；认不出时返回 {@code null}。
     *
     * <p>顺序有意义：{@code ID3} / 帧同步先判，避免带 mp3 标签的文件被后面某种宽泛匹配抢走。
     */
    private static String fromHeader(byte[] head) {
        if (startsWithAt(head, 0, "ID3") || looksLikeMpegAudioFrame(head)) {
            return "audio/mpeg";
        }
        if (looksLikeAdtsAacFrame(head)) {
            return "audio/aac";
        }
        if (startsWithAt(head, 4, "ftyp")) {
            // brand 以 M4A 开头的是纯音频 mp4，别报成 video/mp4
            return startsWithAt(head, 8, "M4A") ? "audio/mp4" : "video/mp4";
        }
        if (startsWithAt(head, 0, "RIFF")) {
            if (startsWithAt(head, 8, "WAVE")) {
                return "audio/wav";
            }
            if (startsWithAt(head, 8, "AVI ")) {
                return "video/x-msvideo";
            }
        }
        if (startsWithAt(head, 0, "OggS")) {
            return "audio/ogg";
        }
        if (startsWithAt(head, 0, "fLaC")) {
            return "audio/flac";
        }
        if (startsWithAt(head, 0, new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})) {
            // Matroska 家族：头部 docType 写了 webm 才算 webm，其余一律按 mkv
            return containsAscii(head, "webm") ? "video/webm" : "video/x-matroska";
        }
        return null;
    }

    /**
     * mp3（MPEG 音频）的帧头：首字节 0xFF、次字节高三位全 1，且 layer 位不是保留值 00。
     *
     * <p>layer 位必须一起看：AAC 的 ADTS 头同样以 {@code 0xFF 0xF?} 起头，layer 位恒为 00，
     * 不排除它就会把 .aac 文件报成 {@code audio/mpeg}。
     */
    private static boolean looksLikeMpegAudioFrame(byte[] head) {
        return head.length >= 2
                && (head[0] & 0xFF) == 0xFF
                && (head[1] & 0xE0) == 0xE0
                && layerBits(head) != 0;
    }

    /**
     * AAC 的 ADTS 帧头：12 位同步字 {@code FFF} + layer 位为保留值 00。
     */
    private static boolean looksLikeAdtsAacFrame(byte[] head) {
        return head.length >= 2
                && (head[0] & 0xFF) == 0xFF
                && (head[1] & 0xF0) == 0xF0
                && layerBits(head) == 0;
    }

    /**
     * 帧头次字节里的 layer 位（第 2、1 位）：00 是保留值，01/10/11 才是 MPEG 音频的层。
     */
    private static int layerBits(byte[] head) {
        return (head[1] >> 1) & 0x03;
    }

    /**
     * 末尾兜底：按扩展名映射，未知扩展名或无扩展名给 {@code application/octet-stream}。
     */
    private static String fromFileName(Path file) {
        String name = file == null || file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return OCTET_STREAM;
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "mp4", "m4v" -> "video/mp4";
            case "m4a" -> "audio/mp4";
            case "webm" -> "video/webm";
            case "mkv" -> "video/x-matroska";
            case "mp3" -> "audio/mpeg";
            case "aac" -> "audio/aac";
            case "wav" -> "audio/wav";
            case "ogg", "opus" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "ts" -> "video/mp2t";
            default -> OCTET_STREAM;
        };
    }

    /**
     * 头部指定偏移处是不是这段 ASCII。
     */
    private static boolean startsWithAt(byte[] head, int offset, String ascii) {
        if (offset < 0 || offset + ascii.length() > head.length) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if ((head[offset + i] & 0xFF) != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 头部指定偏移处是不是这几个字节。
     */
    private static boolean startsWithAt(byte[] head, int offset, byte[] magic) {
        if (offset < 0 || offset + magic.length > head.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (head[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 头部任意位置是否出现过这段 ASCII。
     */
    private static boolean containsAscii(byte[] head, String ascii) {
        for (int offset = 0; offset <= head.length - ascii.length(); offset++) {
            if (startsWithAt(head, offset, ascii)) {
                return true;
            }
        }
        return false;
    }
}
