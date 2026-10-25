/**
 * 共享工具：跨层复用的技术性助手——{@code fs}（产物文件定位与 JSON 读写）、
 * {@code media}（媒体身份 → 管线输入的变体选择、按文件头猜 MIME）、{@code progress}（进度区间线性映射）、
 * {@code subtitle}（SRT 渲染、段落切分、术语命中、文本相似度）、{@code text}（文本尾部处理）。
 *
 * <p>准入判据：纯静态方法工具，无状态、不发起网络传输或外部进程；可引用 spring-core 级工具类
 * （如 {@code StringUtils}），但不依赖 Spring 容器与 Web 类型——去掉容器仍可编译。依赖限
 * JDK、Jackson、slf4j 与 {@code domain} 的领域词汇（如 {@code StageProgress}、
 * {@code TranscriptionSegment}），不含业务编排。
 *
 * <p>有状态、或需要 Spring 容器的技术机制不归本层，落 {@code infrastructure/}——那里的准入判据是
 * 「有没有状态」，不是「跟不跟外部世界打交道」（如 {@code infrastructure/concurrency} 的任务并发闸）。
 */
package com.vidsage.shared;
