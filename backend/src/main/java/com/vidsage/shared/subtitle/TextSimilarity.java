package com.vidsage.shared.subtitle;

/**
 * 文本相似度，给字幕纠错的「最小改动」守卫用。
 *
 * <p>语义对齐 Python 的 {@code difflib.SequenceMatcher.ratio()}：取两端的最长公共子序列长度
 * {@code M}，返回 {@code 2 * M / (lenA + lenB)}。纠错阶段拿它判断「LLM 改得太多还是太少」——
 * 太高说明几乎没纠，太低说明 LLM 顺手重写了，都要拦。
 *
 * <p><b>public</b>：唯一消费者是字幕纠错器 {@code SubtitleCleanser}，跨包使用，收不进包私有；
 * 谁将来真要用，也别为「可能用到」提前造 util 桶（见 directory-structure.md）。
 */
public final class TextSimilarity {

    private TextSimilarity() {
        // 纯静态工具，不实例化
    }

    /**
     * 计算 {@code a} 与 {@code b} 的相似度，返回 {@code [0,1]}。
     *
     * <p>{@code null} 按空串处理；两者皆空返回 {@code 1.0}；其一为空返回 {@code 0.0}。
     * 完全相同的两端返回 {@code 1.0}，毫无交集返回 {@code 0.0}。
     *
     * @param a 第一段文本，可为 {@code null}
     * @param b 第二段文本，可为 {@code null}
     * @return 相似度，[0,1]
     */
    public static double ratio(String a, String b) {
        if (a == null) {
            a = "";
        }
        if (b == null) {
            b = "";
        }
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int m = longestCommonSubsequence(a, b);
        return (2.0 * m) / (a.length() + b.length());
    }

    /**
     * 最长公共子序列长度（DP，动态规划填表）。字幕单行通常几十字内，O(n*m) 足够。
     *
     * @param a 第一段文本
     * @param b 第二段文本
     * @return 最长公共子序列长度
     */
    private static int longestCommonSubsequence(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp[n][m];
    }
}