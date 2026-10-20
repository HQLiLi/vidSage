package com.vidsage.application.maintenance.query;

/**
 * 对账报告里的一行问题：任务号（①②④）或目录名（③）+ 文件名。
 *
 * <p>「问题类别」由它所属的数组表达（{@code danglingFiles} / {@code reclaimPending} / …），
 * 不再冗余一个 {@code category} 字段。①②④ 的行 {@code dirName} 为 {@code null}、③ 的行
 * {@code taskNo} 为 {@code null}——<b>对外 JSON 里那一项不出现</b>，由 web 侧的响应类型管
 * （本类是读模型，不带序列化注解）。
 *
 * @param taskNo   对外任务号；③ 的孤儿不在表里，没有任务号可带，此项为 {@code null}
 * @param dirName  工作区目录名；①②④ 的行有任务号，此项为 {@code null}
 * @param fileName 工作区内文件名（不是绝对路径）
 */
public record ReconcileIssue(String taskNo, String dirName, String fileName) {

    /**
     * 造一条「有任务号」的问题行（①②④）。
     *
     * @param taskNo   对外任务号
     * @param fileName 工作区内文件名
     * @return 只带任务号与文件名的问题行
     */
    public static ReconcileIssue ofTask(String taskNo, String fileName) {
        return new ReconcileIssue(taskNo, null, fileName);
    }

    /**
     * 造一条「只有目录名」的问题行（③ 孤儿——不在表里，没有任务号）。
     *
     * @param dirName  工作区目录名
     * @param fileName 工作区内文件名
     * @return 只带目录名与文件名的问题行
     */
    public static ReconcileIssue ofDir(String dirName, String fileName) {
        return new ReconcileIssue(null, dirName, fileName);
    }
}
