package com.campus.repair.modules.review.service;

import com.campus.repair.modules.review.constant.ContentReviewCodes;
import com.campus.repair.modules.review.dto.SensitiveWordRule;
import com.campus.repair.modules.review.support.ContentTextNormalizer;
import com.campus.repair.modules.spatial.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容初筛的纯逻辑：词表 + 确定性业务规则 + 图片抽检比例。
 *
 * <p><b>纯函数约定</b>：{@code screen(...)} 内部不查库、不读时钟、不随请求变化。
 * 三个「不确定性来源」全部显式传入最终重载：
 * <ul>
 *   <li>启用的词表快照（{@code SensitiveWordService} 维护，写操作后刷新）；</li>
 *   <li>总开关 {@code content_review.enabled}（缺失/异常回退代码默认值 {@code 1}）；</li>
 *   <li>图片抽检比例 {@code content_review.image_sample_rate}（缺失/异常回退 {@code 30}）。</li>
 * </ul>
 * 生产入口自己从这两个协作者取当前值；单测直接 {@code new ContentScreenService(null, null)}
 * 并调用七参重载，无需启动 Spring 或数据库。
 *
 * <p>命中原因写 {@code hit_rules}，格式 {@code 分类码:分类中文名|...}。
 * **只回传分类名，不回传词表原文**——命中词原文会经审核队列/接口外泄，
 * 而且种子里的占位词对复核人没有解释价值。
 *
 * <p>图片抽检是**确定性**的：{@code hash(业务号) % 100 < rate}，同一单号永远同一结论，
 * 便于测试复现与答辩解释；拿不到业务号时用归一化正文的 hash 兜底。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContentScreenService {

    /** 11 位手机号（前后不能紧邻数字，避免从 18 位身份证/银行卡号里截出「手机号」）。 */
    private static final Pattern MOBILE_PATTERN = Pattern.compile("(?<![0-9])(1[3-9][0-9]{9})(?![0-9])");
    /** 18 位身份证号（末位可能是 X，归一化后已是小写 x）。 */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<![0-9x])([0-9]{17}[0-9x])(?![0-9x])");
    /** 16~19 位连续数字的银行卡号模式。 */
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile("(?<![0-9])([0-9]{16,19})(?![0-9])");

    /** 单字符连续重复达到该长度判为凑字数（「好好好好好好好好」）。 */
    private static final int REPEATED_CHAR_RUN = 8;
    /** 去空白后短于该长度判为与维修无关（「好的」「1」）。 */
    private static final int MIN_MEANINGFUL_LENGTH = 4;

    private final SensitiveWordService sensitiveWordService;

    private final SysConfigService sysConfigService;

    // ------------------------------------------------------------------
    // 生产入口
    // ------------------------------------------------------------------

    /** 三参入口：图片抽检种子用归一化正文的 hash 兜底（提单时工单号可能还不存在）。 */
    public ScreenResult screen(String text, String attachmentsJson, String applicantPhone) {
        return screen(text, attachmentsJson, applicantPhone, null);
    }

    /** 四参入口：优先用业务号（工单号/公告 id）做抽检种子，保证同一单号结论稳定。 */
    public ScreenResult screen(String text, String attachmentsJson, String applicantPhone, String bizNo) {
        List<SensitiveWordRule> rules = sensitiveWordService == null
                ? List.of()
                : sensitiveWordService.activeRules();
        boolean enabled = readInt(ContentReviewCodes.CONFIG_ENABLED,
                ContentReviewCodes.DEFAULT_ENABLED) != 0;
        int imageSampleRate = readInt(ContentReviewCodes.CONFIG_IMAGE_SAMPLE_RATE,
                ContentReviewCodes.DEFAULT_IMAGE_SAMPLE_RATE);
        return screen(text, attachmentsJson, applicantPhone, bizNo, rules, enabled, imageSampleRate);
    }

    // ------------------------------------------------------------------
    // 纯函数主体（七参重载：单测直接调它）
    // ------------------------------------------------------------------

    /**
     * 纯初筛。
     *
     * @param activeRules     已归一化（小写、去零宽、折叠空白）的启用词表快照
     * @param enabled         {@code false} 时直接通过（总开关 content_review.enabled=0）
     * @param imageSampleRate 图片抽检百分比，内部会夹到 0~100
     */
    public ScreenResult screen(String text,
                               String attachmentsJson,
                               String applicantPhone,
                               String bizNo,
                               Collection<SensitiveWordRule> activeRules,
                               boolean enabled,
                               int imageSampleRate) {
        if (!enabled) {
            return ScreenResult.passThrough();
        }

        String normalized = ContentTextNormalizer.normalize(text);
        String compact = ContentTextNormalizer.compact(text);

        LinkedHashSet<String> hits = new LinkedHashSet<>();
        LinkedHashSet<String> categories = new LinkedHashSet<>();

        // 1) 词表命中：AD / ABUSE / PRIVACY / IRRELEVANT 四类都由词表提供数据源
        if (activeRules != null) {
            for (SensitiveWordRule rule : activeRules) {
                if (rule == null || rule.word() == null || rule.word().isEmpty()) {
                    continue;
                }
                if (normalized.contains(rule.word())) {
                    addHit(hits, categories, rule.category(), ContentReviewCodes.categoryLabel(rule.category()));
                }
            }
        }

        // 2) 确定性隐私规则：手机号（排除本人）/ 身份证 / 银行卡；在去空白串上匹配，
        //    避免「138 0000 0000」这类插入空格的写法绕过。
        addPrivacyHits(compact, applicantPhone, hits, categories);

        // 3) 确定性「与维修无关」规则
        addIrrelevantHits(compact, hits, categories);

        // 4) 图片人工抽检（文字规则未命中也可能抽到；抽检只增加进队列的理由，不改变其他结论）
        int rate = Math.max(0, Math.min(ContentReviewCodes.IMAGE_SAMPLE_RATE_MAX, imageSampleRate));
        if (rate > 0 && hasImages(attachmentsJson)) {
            String seed = bizNo != null && !bizNo.isBlank()
                    ? ContentTextNormalizer.normalize(bizNo)
                    : normalized;
            if (Math.floorMod(seed.hashCode(), 100) < rate) {
                addHit(hits, categories, ContentReviewCodes.CATEGORY_IMAGE,
                        ContentReviewCodes.categoryLabel(ContentReviewCodes.CATEGORY_IMAGE));
            }
        }

        if (hits.isEmpty()) {
            return ScreenResult.passThrough();
        }
        return new ScreenResult(false, String.join("|", hits), String.join("|", categories));
    }

    // ------------------------------------------------------------------
    // 规则实现
    // ------------------------------------------------------------------

    private void addPrivacyHits(String compact, String applicantPhone,
                                LinkedHashSet<String> hits, LinkedHashSet<String> categories) {
        String applicantDigits = digitsOnly(applicantPhone);

        Matcher mobile = MOBILE_PATTERN.matcher(compact);
        while (mobile.find()) {
            String phone = mobile.group(1);
            // 报修人写自己的电话是正常业务，只有**不等于本人注册号**的号码才判隐私
            if (!phone.equals(applicantDigits)) {
                addHit(hits, categories, ContentReviewCodes.CATEGORY_PRIVACY,
                        ContentReviewCodes.RULE_OTHER_PHONE);
            }
        }

        String withoutIdCards = compact;
        Matcher idCard = ID_CARD_PATTERN.matcher(compact);
        if (idCard.find()) {
            addHit(hits, categories, ContentReviewCodes.CATEGORY_PRIVACY,
                    ContentReviewCodes.RULE_ID_CARD);
            // 18 位身份证同时符合「16~19 位连续数字」，先抹掉再判银行卡避免重复命中
            withoutIdCards = idCard.replaceAll(" ");
        }

        if (BANK_CARD_PATTERN.matcher(withoutIdCards).find()) {
            addHit(hits, categories, ContentReviewCodes.CATEGORY_PRIVACY,
                    ContentReviewCodes.RULE_BANK_CARD);
        }
    }

    private void addIrrelevantHits(String compact,
                                   LinkedHashSet<String> hits, LinkedHashSet<String> categories) {
        if (compact.length() < MIN_MEANINGFUL_LENGTH) {
            addHit(hits, categories, ContentReviewCodes.CATEGORY_IRRELEVANT,
                    ContentReviewCodes.RULE_TOO_SHORT);
        }
        if (!compact.isEmpty() && isAllDigits(compact)) {
            addHit(hits, categories, ContentReviewCodes.CATEGORY_IRRELEVANT,
                    ContentReviewCodes.RULE_PURE_NUMBER);
        }
        if (!compact.isEmpty() && isPurePunctuation(compact)) {
            addHit(hits, categories, ContentReviewCodes.CATEGORY_IRRELEVANT,
                    ContentReviewCodes.RULE_PURE_PUNCTUATION);
        }
        if (maxRepeatRun(compact) >= REPEATED_CHAR_RUN) {
            addHit(hits, categories, ContentReviewCodes.CATEGORY_IRRELEVANT,
                    ContentReviewCodes.RULE_REPEATED_CHAR);
        }
    }

    private static void addHit(LinkedHashSet<String> hits, LinkedHashSet<String> categories,
                               String category, String reason) {
        if (category == null || category.isEmpty()) {
            return;
        }
        hits.add(category + ":" + reason);
        categories.add(category);
    }

    private static String digitsOnly(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isAllDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isPurePunctuation(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                return false;
            }
        }
        return true;
    }

    private static int maxRepeatRun(String text) {
        int max = 0;
        int run = 0;
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == previous) {
                run++;
            } else {
                run = 1;
                previous = cp;
            }
            max = Math.max(max, run);
        }
        return max;
    }

    /** 附件 JSON 里是否有图片：null / 空串 / "[]" / "null" 都视为没有。 */
    private static boolean hasImages(String attachmentsJson) {
        if (attachmentsJson == null) {
            return false;
        }
        String trimmed = attachmentsJson.trim();
        if (trimmed.isEmpty() || "[]".equals(trimmed) || "null".equalsIgnoreCase(trimmed)) {
            return false;
        }
        return trimmed.startsWith("[") && trimmed.endsWith("]");
    }

    private int readInt(String key, int defaultValue) {
        if (sysConfigService == null) {
            return defaultValue;
        }
        try {
            return sysConfigService.getIntValue(key, defaultValue);
        } catch (Exception e) {
            log.warn("[ContentScreenService] 读取配置 {} 失败，回退默认值 {}: {}",
                    key, defaultValue, e.getMessage());
            return defaultValue;
        }
    }

    /**
     * 初筛结论。
     *
     * @param passed        是否通过（通过=不进复核队列）
     * @param hitRules      命中规则串，形如 {@code AD:广告导流|PRIVACY:他人手机号}；无命中为空串
     * @param hitCategories 命中的分类码串，形如 {@code AD|PRIVACY}；无命中为空串
     */
    public record ScreenResult(boolean passed, String hitRules, String hitCategories) {

        /**
         * 「直接通过」的共享实例。
         *
         * <p>刻意不叫 {@code passed()}：record 自动生成的 accessor 已经是 {@code boolean passed()}，
         * 同名静态工厂会因返回类型不同而编译失败（Java 不允许仅返回类型不同的重载）。
         */
        public static ScreenResult passThrough() {
            return new ScreenResult(true, "", "");
        }
    }
}
