package com.vidsage.web.controller;

import com.vidsage.application.subtitle.SubtitleLanguageService;
import com.vidsage.web.dto.LanguageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 翻译语种清单的 HTTP 入口：只读、与任务无关，前端下拉的清单来源。
 *
 * <p>不塞进任务视图（每个任务响应背一份静态清单没道理），也不让前端写死（两端各存一份迟早对不上）。
 */
@Tag(name = "字幕", description = "翻译语种清单（只读）：前端下拉的清单来源，源语种与目标语种共用这一份")
@RestController
@RequestMapping("/api/languages")
public class LanguageController {

    private final SubtitleLanguageService languageService;

    public LanguageController(SubtitleLanguageService languageService) {
        this.languageService = languageService;
    }

    /**
     * 列出支持的语种：四项，各带短码与中文名，顺序固定。
     *
     * @return 语种清单（简体中文 / 繁体中文 / 英语 / 日语）
     */
    @Operation(summary = "列出翻译语种",
            description = "只读：返回支持的语种（code + 中文 label），顺序固定。code 回传给 "
                    + "POST /api/tasks/{taskNo}/translate 使用；源语种与目标语种共用这一份清单。"
                    + "清单之外的写法一律 400，但后端也认 zh / zh-CN / zh-TW 这类常见别名"
                    + "（裸 zh 等同简体中文，不会出现在清单里）")
    @GetMapping
    public List<LanguageResponse> languages() {
        return languageService.listSupportedLanguages().stream().map(LanguageResponse::from).toList();
    }
}
