package com.campus.repair.modules.workorder.service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.util.StringUtils;

/**
 * 把初筛命中规则翻译成**给人看的中文摘要**（不回传词表原文）。
 *
 * <p>初筛产出的 `hit_rules` 形如 `AD:广告导流|PRIVACY:他人手机号`（分类:规则名），
 * 它适合做审核员的「为什么进队列」证据，但**不适合直接回传给报修人**——
 * 把敏感词原文/精确规则回传等于教人绕过初筛。因此对外只暴露分类层面的摘要：
 * `内容含广告导流/他人隐私，待人工复核`。
 *
 * <p>分类码与中文标签的映射集中在这里，复核页、工单详情与提单响应共用同一套口径。
 */
public final class ContentReviewReasonFormatter {

    private ContentReviewReasonFormatter() {
    }

    /** 四类命中口径 + 图片抽检（抽检不是违规判定，但同样进队列，需要能解释） */
    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "AD", "广告导流",
            "ABUSE", "辱骂攻击",
            "PRIVACY", "他人隐私",
            "IRRELEVANT", "与维修无关",
            "IMAGE", "图片抽检"
    );

    private static final String UNKNOWN_CATEGORY_LABEL = "敏感内容";

    /**
     * 由 `hit_rules` 生成可读摘要。
     *
     * @param hitRules 形如 `AD:广告导流|PRIVACY:他人手机号`；空值时给通用兜底文案
     * @return 如 `内容含广告导流/他人隐私，待人工复核`
     */
    public static String summary(String hitRules) {
        if (!StringUtils.hasText(hitRules)) {
            return "内容命中初筛规则，待人工复核";
        }
        Set<String> labels = new LinkedHashSet<>();
        for (String rule : hitRules.split("\\|")) {
            if (!StringUtils.hasText(rule)) {
                continue;
            }
            int colon = rule.indexOf(':');
            String category = (colon >= 0 ? rule.substring(0, colon) : rule).trim().toUpperCase(Locale.ROOT);
            labels.add(CATEGORY_LABELS.getOrDefault(category, UNKNOWN_CATEGORY_LABEL));
        }
        if (labels.isEmpty()) {
            return "内容命中初筛规则，待人工复核";
        }
        return "内容含" + String.join("/", labels) + "，待人工复核";
    }
}
