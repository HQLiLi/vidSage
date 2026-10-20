package com.vidsage.application.maintenance.port;

import java.util.List;
import java.util.Map;

/**
 * 对账的数据来源：跨任务全量读两张文件表，外加盘上「目录名 → 文件名清单」。
 *
 * <p>为什么新开一个端口而不是给领域仓储加 {@code findAll()}：对账要的是跨任务全量读，
 * 而三个仓储端口全是「按 taskId 查」——那是领域行为的出口，没有「把全表捞出来」这种领域语义。
 * 给它们各长一个「给对账用」的方法，等于为运维需求污染领域端口。
 *
 * <p>端口放 application 的理由是「这是运维能力」，不是「契约里没有领域词汇」——本端口<b>有</b>
 * 领域词汇（{@code taskNo} / {@code fileName}），但它同样不是领域行为（对照 {@code diagnostics}）。
 *
 * <p>实现方只为实现这个端口，<b>不得反向调用本层的用例服务</b>。
 */
public interface ReconcileReader {

    /**
     * 全部产物登记行，带任务号与目录名。任何状态都读（③ 的已登记集合要含全部状态）。
     *
     * @return 全部产物登记行；没有时是空列表
     */
    List<TaskScopedArtifact> readAllArtifacts();

    /**
     * 全部中间产物登记行，带任务号与目录名。任何状态都读。
     *
     * @return 全部中间产物登记行；没有时是空列表
     */
    List<TaskScopedIntermediateFile> readAllIntermediateFiles();

    /**
     * 工作区根下每个一层子目录名 → 该目录下的普通文件名清单（不含子目录、不递归）。
     *
     * <p>一次给全景，不按目录反复回调（避免端口长出「列目录」这种太细的粒度）。根目录不存在时
     * 返回空 map，不抛异常——工作区还没建过是合法状态。
     *
     * @return 目录名 → 文件名清单；根目录不存在时是空 map
     */
    Map<String, List<String>> listWorkspaceFilesByDirName();
}
