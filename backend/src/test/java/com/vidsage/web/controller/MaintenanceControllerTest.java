package com.vidsage.web.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vidsage.application.maintenance.ReconcileService;
import com.vidsage.application.maintenance.query.ReconcileIssue;
import com.vidsage.application.maintenance.query.ReconcileIssueGroup;
import com.vidsage.application.maintenance.query.ReconcileReport;
import com.vidsage.application.maintenance.query.ReconcileSummary;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/maintenance/reconcile} 的切片测试：只装配 web 层，服务层用
 * {@link MockitoBean} 替换。断言的正是 implement.md 步骤 5 要钉的报告形状——
 * 四类恒在、空类是 {@code {"count":0,"items":[]}} 而非 {@code null}、一行里没用的字段不出现在 JSON 里。
 */
@WebMvcTest(MaintenanceController.class)
class MaintenanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReconcileService reconcileService;

    /** 固定的扫描时刻夹具：秒位非零，好让 {@code toString()} 与 Jackson 的 ISO 输出逐字相同。 */
    private static final LocalDateTime CHECKED_AT = LocalDateTime.of(2026, 10, 20, 15, 30, 45);

    @Test
    void returnsAllFourGroupsEvenWhenEverythingIsClean() throws Exception {
        // 空态：四类都必须在场（不是 null、也不省略），count 与 items 都为空
        when(reconcileService.reconcile()).thenReturn(emptyReport());

        mockMvc.perform(post("/api/maintenance/reconcile"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.checkedAt").value(CHECKED_AT.toString()))
                .andExpect(jsonPath("$.summary.dirs").value(0))
                .andExpect(jsonPath("$.summary.files").value(0))
                .andExpect(jsonPath("$.danglingFiles.count").value(0))
                .andExpect(jsonPath("$.danglingFiles.items").isArray())
                .andExpect(jsonPath("$.reclaimPending.count").value(0))
                .andExpect(jsonPath("$.reclaimPending.items").isArray())
                .andExpect(jsonPath("$.orphanFiles.count").value(0))
                .andExpect(jsonPath("$.orphanFiles.items").isArray())
                .andExpect(jsonPath("$.hashMismatch.count").value(0))
                .andExpect(jsonPath("$.hashMismatch.items").isArray());
    }

    @Test
    void eachIssueCarriesOnlyItsOwnIdentityField() throws Exception {
        // ①②④ 的行带 taskNo、不带 dirName；③ 的行带 dirName、不带 taskNo（@JsonInclude(NON_NULL)）
        when(reconcileService.reconcile()).thenReturn(reportWithIssues());

        mockMvc.perform(post("/api/maintenance/reconcile"))
                .andExpect(status().isOk())
                // ① 带 taskNo + fileName，不带 dirName
                .andExpect(jsonPath("$.danglingFiles.count").value(1))
                .andExpect(jsonPath("$.danglingFiles.items[0].taskNo").value("3f2a9c1e"))
                .andExpect(jsonPath("$.danglingFiles.items[0].fileName").value("cleaned-segments.json"))
                .andExpect(jsonPath("$.danglingFiles.items[0].dirName").doesNotExist())
                // ③ 带 dirName + fileName，不带 taskNo
                .andExpect(jsonPath("$.orphanFiles.count").value(1))
                .andExpect(jsonPath("$.orphanFiles.items[0].dirName").value("BV11QMd6WEJp_p1"))
                .andExpect(jsonPath("$.orphanFiles.items[0].fileName").value("junk.srt"))
                .andExpect(jsonPath("$.orphanFiles.items[0].taskNo").doesNotExist());
    }

    private static ReconcileReport emptyReport() {
        return new ReconcileReport(CHECKED_AT, new ReconcileSummary(0, 0),
                ReconcileIssueGroup.of(List.of()), ReconcileIssueGroup.of(List.of()),
                ReconcileIssueGroup.of(List.of()), ReconcileIssueGroup.of(List.of()));
    }

    private static ReconcileReport reportWithIssues() {
        return new ReconcileReport(CHECKED_AT, new ReconcileSummary(3, 23),
                ReconcileIssueGroup.of(List.of(ReconcileIssue.ofTask("3f2a9c1e", "cleaned-segments.json"))),
                ReconcileIssueGroup.of(List.of()),
                ReconcileIssueGroup.of(List.of(ReconcileIssue.ofDir("BV11QMd6WEJp_p1", "junk.srt"))),
                ReconcileIssueGroup.of(List.of()));
    }
}
