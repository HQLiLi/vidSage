package com.vidsage.domain.subtitle.model.keyword;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 用户手填的一份关键词清单：纯字符串数组，<b>没有</b>「原写法 → 正确写法」的对照关系
 * （对照关系要的是 {@code SubtitleContext.Term}，那份由摘要器产出）。
 *
 * <p>最典型的用途是视频发布者的名字——ASR 几乎必然把它转错，而用户知道正确写法。
 * 无论从哪条路径构造，拿到的都是归一化、去重、通过上限校验的一份。
 *
 * <p><b>为什么超限抛 {@link IllegalArgumentException} 而不是 {@code IllegalStateException}</b>：
 * 清单里的每一条都是调用方（用户）发过来的，越界是「调用方传参不合法」，既有 REST 映射接成 400；
 * 构造器当场就炸，什么都不会落库、不会重跑。
 *
 * @param values 归一化后的关键词清单（用户填写顺序、无重复、无空串）；{@code null} 当空列表处理
 */
public record SubtitleKeywords(List<String> values) {

    /**
     * 单条关键词的字数上限（含标点与空格）。超了多半不是人写的专有名词，拦下来早省一次 LLM 钱。
     */
    public static final int MAX_VALUE_LENGTH = 50;

    /**
     * 关键词条数上限。
     */
    public static final int MAX_COUNT = 50;

    /**
     * 落库时连接多条关键词的分隔符：ISO 控制字符，不会跟真术语（{@code Dr. Smith, Jr.} 里的逗号）撞。
     * 归一化已把它换成空格，所以到不了落库这一层——不需要转义，也不需要读回时还原。
     */
    public static final String STORAGE_SEPARATOR = String.valueOf((char) 0x01);

    /**
     * 指纹取 SHA-256 十六进制的前多少位：50 条以内的短列表，16 位（64 bit）碰撞概率可忽略。
     */
    private static final int FINGERPRINT_WIDTH = 16;

    /**
     * 紧凑构造器：归一化 → 去重保序 → 上限校验。
     *
     * @param values 用户填的原始列表，可为 {@code null}（当空列表处理）；元素为 {@code null} 的条目直接丢弃
     * @throws IllegalArgumentException 单条超过 {@value #MAX_VALUE_LENGTH} 字，或总数超过 {@value #MAX_COUNT} 条
     */
    public SubtitleKeywords {
        values = List.copyOf(normalizeAndDeduplicate(values));
        if (values.size() > MAX_COUNT) {
            throw new IllegalArgumentException("关键词条数不能超过 " + MAX_COUNT + " 条：本次收到 "
                    + values.size() + " 条；请删掉不用的，或分几次提交");
        }
        for (String value : values) {
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("单条关键词不能超过 " + MAX_VALUE_LENGTH + " 字："
                        + "本次最长的一条 " + value.length() + " 字；请只填专有名词本身，别整句贴进来");
            }
        }
    }

    /**
     * 这份清单是不是空的：没有关键词时走的就是「按原样清洗」那条路。
     *
     * @return 无关键词时为 {@code true}
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * 算这份清单的指纹：给「当前这份字幕是不是用这份关键词生成的」当判据。
     *
     * <p><b>先排序是刻意的</b>：用户调换填写顺序不该导致指纹变化、进而白白触发一次重跑。
     * 排序 → 以 {@code U+0001} 连接 → SHA-256 → 取十六进制前 16 位。
     *
     * <p>空清单也算得出指纹（就是空串的哈希），不是 {@code null}——
     * 「当前关键词是空的」是一个需要参与判定的正经事实。
     *
     * @return 16 位十六进制指纹；同样的内容永远得到同样的值
     */
    public String computeFingerprint() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join(STORAGE_SEPARATOR, sortedValues()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, FINGERPRINT_WIDTH / 2);
        } catch (NoSuchAlgorithmException e) {
            // 标准 JVM 必带 SHA-256，走到这里说明运行环境被裁剪过
            throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法计算关键词指纹："
                    + "请使用标准 JDK 运行本服务", e);
        }
    }

    /**
     * 排序后的副本（算指纹用）：不改本对象的顺序——原顺序要原样还给前端。
     */
    private List<String> sortedValues() {
        return values.stream().sorted().toList();
    }

    /**
     * 归一化 + 去重：逐条 trim、结构性字符转空格、再 trim，丢掉空串；同一条只留首次出现的那份。
     *
     * @param raw 原始列表，可为 {@code null}
     * @return 干净且不重复的列表（保持用户填写顺序）
     */
    private static List<String> normalizeAndDeduplicate(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        // LinkedHashSet 就是「去重 + 保首次出现顺序」，不必自己维护一张已见集合
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String value = replaceStructuralCharacters(item).trim();
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return new ArrayList<>(normalized);
    }

    /**
     * 把结构性字符（换行、回车与其它 ISO 控制字符）替换成空格——换行能从结构上凭空造出提示词里的
     * 「另一段指令」，落库与进提示词之前就该处理掉；落库的分隔符 {@code U+0001} 也在其中，
     * 顺带就到不了持久化层。
     *
     * <p><b>只动结构性的，内容性字符（{@code <}、{@code "}、{@code {} 等）一律保留</b>——
     * 剥字符是黑名单思路，会误伤真术语（比如一个叫 {@code C++ <memory>} 的词）。那类字符靠标签
     * 包裹与 system 提示词里的声明来定性。
     */
    private static String replaceStructuralCharacters(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
        }
        return sb.toString();
    }
}
