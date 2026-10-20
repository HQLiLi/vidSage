/**
 * 手工对账（只读运维能力）：服务门面 {@code ReconcileService} 留在根；
 * 读模型 {@code ReconcileReport} 与它的子结构（{@code ReconcileSummary} / {@code ReconcileIssueGroup} /
 * {@code ReconcileIssue}）放 {@code query/} 子包，<b>零 web 注解</b>——对外形状是
 * {@code web/dto/ReconcileResponse}，那边以静态工厂 {@code from(读模型)} 纯映射
 * （判据见 directory-structure.md「契约类的位置」判据 2）；
 * 端口 {@code ReconcileReader} 与它的行类型放 {@code port/} 子包。
 *
 * <p>端口定义在 application 而不是 domain——对账是运维排障能力，要的是跨任务全量读，
 * 不是「这个 task 当前有效产物是什么」这种领域行为。判据见 directory-structure.md「端口归属判据」。
 */
package com.vidsage.application.maintenance;
