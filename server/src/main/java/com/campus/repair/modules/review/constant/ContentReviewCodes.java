package com.campus.repair.modules.review.constant;

import java.util.Map;

/**
 * 内容审核（初筛 → 复核 → 轨迹）与公告先审后发的取值词表。
 *
 * <p>这些字符串会直接落库（{@code sys_content_review.biz_type/outcome/screen_result}、
 * {@code sys_announcement.status}、{@code sys_sensitive_word.category}），
 * 改字面量等于数据迁移，不是重命名。常量集中在这里，避免魔法字符串散落。
 *
 * <p>{@code hit_rules} 的格式是 {@code 分类码:分类中文名}，多个命中用 {@code |} 分隔，
 * 例：{@code AD:广告导流|PRIVACY:他人手机号}。**只回传分类名，不回传词表原文**——
 * 词表原文落库会随审核队列/接口外泄，且种子里的占位词没有解释价值。
 */
public final class ContentReviewCodes {

    private ContentReviewCodes() {
    }

    // ---- sys_content_review.biz_type：被审核的业务对象 ----
    public static final String BIZ_ORDER = "ORDER";
    public static final String BIZ_ANNOUNCEMENT = "ANNOUNCEMENT";

    // ---- sys_content_review.outcome：审核结论（一次性决策） ----
    public static final String OUTCOME_PENDING = "PENDING";
    public static final String OUTCOME_APPROVED = "APPROVED";
    public static final String OUTCOME_REJECTED = "REJECTED";
    /** 查询参数用：不过滤结论（前端「审核记录」页签）。 */
    public static final String OUTCOME_ALL = "ALL";

    // ---- sys_content_review.screen_result：本表只存进过队列的，恒为 SUSPECTED ----
    public static final String SCREEN_SUSPECTED = "SUSPECTED";

    // ---- sys_sensitive_word.category / hit_rules 的分类码 ----
    public static final String CATEGORY_AD = "AD";
    public static final String CATEGORY_ABUSE = "ABUSE";
    public static final String CATEGORY_PRIVACY = "PRIVACY";
    public static final String CATEGORY_IRRELEVANT = "IRRELEVANT";
    /** 图片抽检不是词表分类，但同样要进队列，因此复用同一套 hit_rules 表达。 */
    public static final String CATEGORY_IMAGE = "IMAGE";

    /** 分类码 → 人话标签（hit_rules 只写这里的中文名，不写词表原文）。 */
    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            CATEGORY_AD, "广告导流",
            CATEGORY_ABUSE, "辱骂攻击",
            CATEGORY_PRIVACY, "他人隐私",
            CATEGORY_IRRELEVANT, "与维修无关",
            CATEGORY_IMAGE, "图片抽检"
    );

    public static String categoryLabel(String category) {
        if (category == null) {
            return "";
        }
        return CATEGORY_LABELS.getOrDefault(category, category);
    }

    /** 词表维护可接受的分类（图片抽检不由词表维护）。 */
    public static final java.util.Set<String> WORD_CATEGORIES =
            java.util.Set.of(CATEGORY_AD, CATEGORY_ABUSE, CATEGORY_PRIVACY, CATEGORY_IRRELEVANT);

    // ---- 确定性规则的命中理由（同样只写分类名/规则名） ----
    public static final String RULE_OTHER_PHONE = "他人手机号";
    public static final String RULE_ID_CARD = "身份证号";
    public static final String RULE_BANK_CARD = "银行卡号";
    public static final String RULE_TOO_SHORT = "内容过短";
    public static final String RULE_PURE_NUMBER = "纯数字";
    public static final String RULE_PURE_PUNCTUATION = "纯标点符号";
    public static final String RULE_REPEATED_CHAR = "单字符重复";

    // ---- sys_announcement.status：公告状态机 ----
    public static final String ANNOUNCEMENT_DRAFT = "DRAFT";
    public static final String ANNOUNCEMENT_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String ANNOUNCEMENT_APPROVED = "APPROVED";
    public static final String ANNOUNCEMENT_REJECTED = "REJECTED";
    public static final String ANNOUNCEMENT_PUBLISHED = "PUBLISHED";

    // ---- sys_config 参数键与代码级默认值（配置缺失也能工作） ----
    public static final String CONFIG_ENABLED = "content_review.enabled";
    public static final String CONFIG_IMAGE_SAMPLE_RATE = "content_review.image_sample_rate";
    public static final int DEFAULT_ENABLED = 1;
    public static final int DEFAULT_IMAGE_SAMPLE_RATE = 30;
    public static final int IMAGE_SAMPLE_RATE_MAX = 100;

    // ---- 广播通知的分类（与既有 NotificationService 保持一致） ----
    public static final String NOTIFICATION_CATEGORY_BROADCAST = "BROADCAST";

    // ---- 字段长度约束（与建表脚本逐字对齐） ----
    public static final int MAX_TITLE_LENGTH = 128;
    public static final int MAX_CONTENT_LENGTH = 2000;
    public static final int MAX_REASON_LENGTH = 500;
    public static final int MAX_WORD_LENGTH = 64;
    /** {@code sys_content_review.biz_no} 只有 32 位：公告用标题快照时要截断。 */
    public static final int MAX_BIZ_NO_LENGTH = 32;
}
