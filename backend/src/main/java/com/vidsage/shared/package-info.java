/**
 * 共享工具（原 utils/ 改名）：跨层复用的技术性助手——{@code fs}（产物文件定位与 JSON 读写）、
 * {@code progress}（进度区间线性映射）、{@code subtitle}（SRT 渲染、段落切分、术语命中、
 * 文本相似度）、{@code text}（文本尾部处理）。
 *
 * <p>准入判据：纯静态方法工具，无状态、不发起网络传输或外部进程；可引用 spring-core 级工具类
 * （如 {@code StringUtils}），但不依赖 Spring 容器与 Web 类型——去掉容器仍可编译。依赖限
 * JDK、Jackson、slf4j 与 {@code domain} 的领域词汇（如 {@code StageEvent}、
 * {@code TranscriptionSegment}），不含业务编排。
 */
package com.vidsage.shared;
