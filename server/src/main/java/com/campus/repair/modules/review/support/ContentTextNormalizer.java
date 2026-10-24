package com.campus.repair.modules.review.support;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 内容初筛的文本归一化（纯函数、无状态、可单测）。
 *
 * <p>归一化四步与审核口径逐条对应：
 * <ol>
 *   <li>{@link Normalizer.Form#NFKC}：全角转半角（{@code ＱＱ → qq}、{@code １３８… → 138…}）；</li>
 *   <li>小写：英文/全角字母大小写不敏感（词表统一小写存内存）；</li>
 *   <li>去零宽字符：{@code \u200B-\u200F / \u2060 / \uFEFF / \u00AD} 常被用来把敏感词拆开绕过筛查；</li>
 *   <li>折叠空白：连续空白压成一个半角空格并 trim，避免「加 微 信」这类插入空白的变形。</li>
 * </ol>
 *
 * <p>{@link #compact(String)} 在归一化基础上**去掉全部空白**，只给「长度/纯数字/纯标点/重复字符」
 * 这类确定性规则用——把「空 白」也算进长度会让超短文本漏判。
 */
public final class ContentTextNormalizer {

    private ContentTextNormalizer() {
    }

    /** 归一化：NFKC + 小写 + 去零宽 + 折叠空白。null 视为空串。 */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); ) {
            int codePoint = folded.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isZeroWidth(codePoint)) {
                continue;
            }
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                sb.append(' ');
                continue;
            }
            sb.appendCodePoint(Character.toLowerCase(codePoint));
        }
        // 折叠连续空白并去掉首尾（上面已把各类空白统一成半角空格）
        String collapsed = sb.toString().replaceAll(" +", " ");
        return collapsed.trim();
    }

    /** 归一化后去掉全部空白，供长度/字符构成类规则使用。 */
    public static String compact(String text) {
        return normalize(text).replace(" ", "");
    }

    /** 小写 + 去零宽 + 折叠空白后的词表词形（不含「去空白」，词内空格保留为单个空格）。 */
    public static String normalizeWord(String word) {
        return normalize(word).toLowerCase(Locale.ROOT);
    }

    private static boolean isZeroWidth(int codePoint) {
        return codePoint == 0x00AD   // SOFT HYPHEN
                || codePoint == 0x180E // MONGOLIAN VOWEL SEPARATOR
                || codePoint == 0x200B // ZERO WIDTH SPACE
                || codePoint == 0x200C // ZERO WIDTH NON-JOINER
                || codePoint == 0x200D // ZERO WIDTH JOINER
                || codePoint == 0x200E // LEFT-TO-RIGHT MARK
                || codePoint == 0x200F // RIGHT-TO-LEFT MARK
                || codePoint == 0x2060 // WORD JOINER
                || codePoint == 0xFEFF; // BOM / ZERO WIDTH NO-BREAK SPACE
    }
}
