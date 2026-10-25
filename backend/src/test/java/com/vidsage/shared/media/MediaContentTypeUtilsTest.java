package com.vidsage.shared.media;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MediaContentTypeUtils} 的单元测试：每个魔数分支各一条，外加扩展名兜底与全认不出。
 *
 * <p>会真写文件，故 {@code @TempDir} 用 {@link CleanupMode#NEVER}（Windows 上清理偶发失败）。
 */
class MediaContentTypeUtilsTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path tempDir;

    @Test
    void detectsMp4FromFtypBrand() throws IOException {
        assertEquals("video/mp4", detect("source.mp4", ftyp("isom")));
    }

    @Test
    void detectsM4aBrandAsAudioMp4() throws IOException {
        // 上传路线一律落成 source.mp4，纯音频 mp4 只能靠 brand 区分
        assertEquals("audio/mp4", detect("source.mp4", ftyp("M4A ")));
    }

    @Test
    void detectsMp3ById3TagEvenWhenNameSaysMp4() throws IOException {
        assertEquals("audio/mpeg", detect("source.mp4", ascii("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000")));
    }

    @Test
    void detectsMp3ByFrameSync() throws IOException {
        byte[] head = new byte[32];
        head[0] = (byte) 0xFF;
        head[1] = (byte) 0xFB;
        head[2] = (byte) 0x90;
        head[3] = 0x64;
        assertEquals("audio/mpeg", detect("source.mp4", head));
    }

    @Test
    void detectsAdtsAacInsteadOfMpegAudio() throws IOException {
        // ADTS 与 mp3 的帧头同样以 0xFF 起头，只有 layer 位为 00 才能分清
        byte[] head = new byte[32];
        head[0] = (byte) 0xFF;
        head[1] = (byte) 0xF1;
        head[2] = 0x50;
        head[3] = (byte) 0x80;
        assertEquals("audio/aac", detect("source.mp4", head));
    }

    @Test
    void detectsWavFromRiffWave() throws IOException {
        assertEquals("audio/wav", detect("source.wav", riff("WAVE")));
    }

    @Test
    void detectsAviFromRiffAvi() throws IOException {
        assertEquals("video/x-msvideo", detect("source.avi", riff("AVI ")));
    }

    @Test
    void detectsOgg() throws IOException {
        assertEquals("audio/ogg", detect("source.ogg", ascii("OggS\u0000\u0002")));
    }

    @Test
    void detectsFlac() throws IOException {
        assertEquals("audio/flac", detect("source.flac", ascii("fLaC\u0000\u0000\u0000")));
    }

    @Test
    void detectsWebmFromMatroskaHeaderWithWebmDocType() throws IOException {
        assertEquals("video/webm", detect("source.webm", matroska("webm")));
    }

    @Test
    void detectsMkvWhenMatroskaHeaderHasNoWebmDocType() throws IOException {
        assertEquals("video/x-matroska", detect("source.mkv", matroska("matroska")));
    }

    @Test
    void fallsBackToExtensionWhenHeaderIsUnknown() throws IOException {
        assertEquals("audio/mpeg", detect("source.mp3", ascii("not-a-real-header")));
    }

    @Test
    void fallsBackToExtensionForOggAndOpusNames() throws IOException {
        assertEquals("audio/ogg", detect("source.opus", ascii("not-a-real-header")));
    }

    @Test
    void returnsOctetStreamWhenHeaderAndExtensionAreBothUnknown() throws IOException {
        assertEquals("application/octet-stream", detect("source.bin", ascii("not-a-real-header")));
    }

    @Test
    void returnsOctetStreamWhenFileHasNoExtension() throws IOException {
        assertEquals("application/octet-stream", detect("source", ascii("not-a-real-header")));
    }

    @Test
    void returnsOctetStreamWhenFileIsEmpty() throws IOException {
        assertEquals("application/octet-stream", detect("source.txt", new byte[0]));
    }

    @Test
    void fallsBackToExtensionWhenHeaderIsShorterThanMagic() throws IOException {
        // 4 字节的 ftyp 文件头本身合法，但缺 brand：仍判 mp4
        assertEquals("video/mp4", detect("source.mp4", ascii("\u0000\u0000\u0000\u0018ftyp")));
    }

    /** 写一个临时文件再嗅探它。 */
    private String detect(String fileName, byte[] head) throws IOException {
        Path file = Files.write(tempDir.resolve(fileName), head);
        return MediaContentTypeUtils.detect(file);
    }

    /** 造一个 ISO BMFF 文件头：4 字节长度 + {@code ftyp} + brand。 */
    private static byte[] ftyp(String brand) {
        return concat(new byte[] {0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p'}, brand);
    }

    /** 造一个 RIFF 文件头：{@code RIFF} + 4 字节长度 + 格式标记。 */
    private static byte[] riff(String format) {
        return concat(new byte[] {'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00}, format);
    }

    /** 造一个 Matroska 家族文件头：四个魔数字节 + docType 原文。 */
    private static byte[] matroska(String docType) {
        return concat(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3}, docType);
    }

    /** ASCII 字符串转字节。 */
    private static byte[] ascii(String text) {
        byte[] out = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            out[i] = (byte) text.charAt(i);
        }
        return out;
    }

    /** 两段字节接起来。 */
    private static byte[] concat(byte[] prefix, String suffix) {
        byte[] tail = ascii(suffix);
        byte[] out = Arrays.copyOf(prefix, prefix.length + tail.length);
        System.arraycopy(tail, 0, out, prefix.length, tail.length);
        return out;
    }
}
