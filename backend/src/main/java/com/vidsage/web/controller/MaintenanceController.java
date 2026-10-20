package com.vidsage.web.controller;

import com.vidsage.application.maintenance.ReconcileService;
import com.vidsage.web.dto.ReconcileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手工对账的 HTTP 入口（只读）：全量扫工作区目录与两张文件表，把不一致四件事全报。
 *
 * <p>本类只做「接收 → 委托 → 返回」，不写业务逻辑；对账能力经 {@link ReconcileService} 取得。
 * 无请求体、无查询参数、不加鉴权（全站零鉴权是既有缺口，另记）。
 */
@Slf4j
@Tag(name = "运维", description = "手工对账（只读）：全量扫工作区目录与两张文件表，不一致四件事全报，只报不修")
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final ReconcileService reconcileService;

    public MaintenanceController(ReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    /**
     * 触发一次全量对账，同步返回完整报告。
     *
     * @return 只读对账报告（四类不一致 + 扫描时刻 + 扫到的目录/文件数）
     */
    @Operation(summary = "只读对账",
            description = "全量扫工作区目录与两张文件表，把不一致四件事全报（① 表 READY 但文件不在；"
                    + "② 表 SUPERSEDED / DELETED 但文件还在；③ 盘上有文件、表里没登记；④ 表 READY 但哈希对不上）。"
                    + "只报不修、不加鉴权、同步返回；全量扫，无请求体、无查询参数")
    @PostMapping("/reconcile")
    public ReconcileResponse reconcile() {
        long start = System.currentTimeMillis();
        ReconcileResponse response = ReconcileResponse.from(reconcileService.reconcile());
        // 脱敏：只记四类条数与耗时，不打目录名、不打文件名、不打路径
        log.info("对账端点返回: dangling={}, reclaimPending={}, orphan={}, hashMismatch={}, cost={}ms",
                response.danglingFiles().count(), response.reclaimPending().count(),
                response.orphanFiles().count(), response.hashMismatch().count(),
                System.currentTimeMillis() - start);
        return response;
    }
}
