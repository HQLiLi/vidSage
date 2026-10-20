package com.vidsage.shared.fs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TransientFiles} 的纯单元测试：三个过渡态后缀各命中一次；{@code .info.json} 与普通产物名不命中。
 */
class TransientFilesTest {

    @Test
    void matchesTransientSuffixes() {
        assertTrue(TransientFiles.isTransient("a.tmp"), ".tmp 是原子写盘的中间名");
        assertTrue(TransientFiles.isTransient("source.mp4.part"), ".part 是下载器的进行中标记");
        assertTrue(TransientFiles.isTransient("source.mp4.ytdl"), ".ytdl 是 yt-dlp 的进行中标记");
    }

    @Test
    void doesNotMatchInfoJson() {
        // .info.json 已从白名单里拆掉：它现在入 intermediate_file 表（INFO_JSON），
        // 留着会让对账把真孤儿漏报
        assertFalse(TransientFiles.isTransient("source.info.json"));
    }

    @Test
    void doesNotMatchOrdinaryProductNames() {
        assertFalse(TransientFiles.isTransient("source.mp4"));
        assertFalse(TransientFiles.isTransient("cleaned-segments.json"));
        assertFalse(TransientFiles.isTransient("audio-16k-mono-t1.wav"));
    }

    @Test
    void treatsNullAsNotTransient() {
        assertFalse(TransientFiles.isTransient(null));
    }
}
