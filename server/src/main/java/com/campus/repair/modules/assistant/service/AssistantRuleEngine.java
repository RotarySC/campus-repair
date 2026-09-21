package com.campus.repair.modules.assistant.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 助手故障候选的本地规则引擎：<b>纯函数、无 Spring 依赖、无 IO、无随机</b>。
 *
 * <p>这样它既能被单元测试穷举，也保证了"同样的描述 → 同样的候选与顺序"（排产/派单类逻辑
 * 依赖顺序稳定性，见 `AGENTS.md` §2.1 分析聚合因为 `HashMap` 迭代顺序吃过亏）。
 *
 * <p>打分口径（设计文档 §3.3）：
 * <ul>
 *   <li>叶子名出现在描述里 <b>+10</b>——用户直接说出了分类名，是最强信号；</li>
 *   <li>每个关键词命中 <b>+5</b>（同一个词重复出现只计一次）；</li>
 *   <li>大类名命中 <b>+3</b>——最弱的兜底信号。</li>
 * </ul>
 *
 * <p>归一化只做两件事：<b>去掉所有空白字符</b> + ASCII 转小写。
 * 标点刻意**不**处理（「空调，漏水」不会命中关键词「空调漏水」）——因此字典里的关键词
 * 应当是"一个概念一个词"，而不是靠标点拼出来的短语。
 */
public final class AssistantRuleEngine {

    public static final int WEIGHT_LEAF_NAME = 10;
    public static final int WEIGHT_KEYWORD = 5;
    public static final int WEIGHT_PARENT_NAME = 3;

    /** 长度小于 2 的词一律忽略：挡住「水」「灯」「门」这类会命中一大片的噪声词。 */
    public static final int MIN_KEYWORD_LENGTH = 2;

    /** 关键词分隔符：半角/全角逗号、顿号、半角/全角分号、任意空白 */
    private static final String KEYWORD_SEPARATORS = "[,，、;；\\s]+";

    private AssistantRuleEngine() {
    }

    /** 一道叶子分类的助手知识（由调用方从 `repair_category` 组装，引擎不碰数据库）。 */
    public record LeafKnowledge(
            Long id,
            String name,
            Long parentId,
            String parentName,
            String skillCode,
            Integer slaHours,
            Integer sortOrder,
            String keywords) {
    }

    /** 一条已打分的候选。 */
    public record Scored(LeafKnowledge leaf, int score, Confidence confidence, List<String> matchedKeywords) {
    }

    /** 置信度分档。刻意不给百分比——规则引擎的分数不是概率。 */
    public enum Confidence {
        HIGH, MEDIUM, LOW;

        public static Confidence of(int score) {
            if (score >= WEIGHT_LEAF_NAME) {
                return HIGH;
            }
            if (score >= WEIGHT_KEYWORD) {
                return MEDIUM;
            }
            return LOW;
        }
    }

    /**
     * 排序：分数降序 → {@code sortOrder} 升序 → {@code id} 升序。
     * 后两级是**确定性保证**：同分时不能让结果取决于数据库返回顺序。
     */
    private static final Comparator<Scored> ORDER = Comparator
            .comparingInt(Scored::score).reversed()
            .thenComparingInt(s -> s.leaf().sortOrder() == null ? Integer.MAX_VALUE : s.leaf().sortOrder())
            .thenComparingLong(s -> s.leaf().id() == null ? Long.MAX_VALUE : s.leaf().id());

    /**
     * 排序并截断。
     *
     * @return 分数 &gt; 0 的候选，最多 {@code limit} 条；一条都没命中时返回空列表
     *         （由调用方决定怎么回退，引擎不硬凑候选）
     */
    public static List<Scored> rank(String description, List<LeafKnowledge> leaves, int limit) {
        List<Scored> scored = new ArrayList<>();
        if (limit <= 0 || leaves == null || leaves.isEmpty()) {
            return scored;
        }
        String text = normalize(description);
        if (text.isEmpty()) {
            return scored;
        }

        for (LeafKnowledge leaf : leaves) {
            List<String> hits = new ArrayList<>();
            int score = 0;

            if (contains(text, leaf.name())) {
                score += WEIGHT_LEAF_NAME;
                hits.add(leaf.name());
            }
            for (String keyword : splitKeywords(leaf.keywords())) {
                // 关键词里若重复写了叶子名，不重复计分
                if (keyword.equals(normalize(leaf.name()))) {
                    continue;
                }
                if (text.contains(keyword)) {
                    score += WEIGHT_KEYWORD;
                    hits.add(keyword);
                }
            }
            if (contains(text, leaf.parentName())) {
                score += WEIGHT_PARENT_NAME;
            }

            if (score > 0) {
                scored.add(new Scored(leaf, score, Confidence.of(score), List.copyOf(hits)));
            }
        }

        scored.sort(ORDER);
        return scored.size() > limit ? new ArrayList<>(scored.subList(0, limit)) : scored;
    }

    /** 去掉所有空白字符（含全角空格）+ ASCII 转小写。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || c == '\u3000') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 切分关键词串，并按 {@link #MIN_KEYWORD_LENGTH} 过滤掉短词。
     *
     * @return 去重后的关键词（保持书写顺序，便于断言与展示）
     */
    public static List<String> splitKeywords(String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String part : keywords.split(KEYWORD_SEPARATORS)) {
            String keyword = normalize(part);
            if (keyword.length() >= MIN_KEYWORD_LENGTH) {
                unique.add(keyword);
            }
        }
        return new ArrayList<>(unique);
    }

    private static boolean contains(String normalizedText, String word) {
        if (word == null || word.isBlank()) {
            return false;
        }
        return normalizedText.contains(normalize(word));
    }
}
