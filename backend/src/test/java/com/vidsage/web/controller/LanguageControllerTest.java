package com.vidsage.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vidsage.application.subtitle.SubtitleLanguageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/languages} 的切片测试：清单的形状、顺序与取值（AC1）。
 *
 * <p>清单是前端下拉的唯一来源——源语种与目标语种共用它，前端把 {@code code} 原样回传给
 * {@code POST /api/tasks/{taskNo}/translate}。服务层用**真实实例**（{@code @Import}）：
 * 清单就是从白名单枚举摊出来的，mock 掉它这条测试就没有内容可断言了。
 */
@WebMvcTest(LanguageController.class)
@Import(SubtitleLanguageService.class)
class LanguageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsFourLanguagesCodeAndLabelInFixedOrder() throws Exception {
        mockMvc.perform(get("/api/languages"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].code").value("zh-Hans"))
                .andExpect(jsonPath("$[0].label").value("简体中文"))
                .andExpect(jsonPath("$[1].code").value("zh-Hant"))
                .andExpect(jsonPath("$[1].label").value("繁体中文"))
                .andExpect(jsonPath("$[2].code").value("en"))
                .andExpect(jsonPath("$[2].label").value("英语"))
                .andExpect(jsonPath("$[3].code").value("ja"))
                .andExpect(jsonPath("$[3].label").value("日语"));
    }

    @Test
    void eachItemCarriesOnlyCodeAndLabel() throws Exception {
        // 前端要的只有两项：回传用的 code 与显示用的 label。
        // 别名表是**输入侧**的宽容度（zh / zh-CN 也认），不该外泄成对外契约
        mockMvc.perform(get("/api/languages"))
                .andExpect(content().string(not(containsString("matchKeys"))))
                .andExpect(content().string(not(containsString("zh-CN"))));
    }
}
