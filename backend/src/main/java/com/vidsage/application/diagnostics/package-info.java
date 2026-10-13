/**
 * 外部工具诊断：根留出参 {@code ToolDiagnosticReport}（诊断端点直接返回它，形状即对外契约），
 * 端口 {@code ToolDiagnostics} 放 {@code port/} 子包。
 *
 * <p>端口定义在 application 而不是 domain——诊断是环境自检 / 运维排障能力，契约里没有一个领域词汇
 * （工具名、版本号、路径、可用与否），判据见 directory-structure.md「端口归属判据」。
 * 这也是全项目唯一一处 {@code infrastructure → application} 的依赖：实现方在
 * {@code infrastructure/process/}，由 web 诊断端点直接注入端口调用，不经中间转发类。
 */
package com.vidsage.application.diagnostics;
