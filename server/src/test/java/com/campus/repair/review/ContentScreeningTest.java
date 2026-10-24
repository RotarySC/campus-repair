package com.campus.repair.review;

import com.campus.repair.modules.review.dto.SensitiveWordRule;
import com.campus.repair.modules.review.entity.SysSensitiveWord;
import com.campus.repair.modules.review.service.ContentScreenService;
import com.campus.repair.modules.review.service.SensitiveWordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容初筛纯函数单测（不启动 Spring、不连数据库）。
 *
 * <p>{@link ContentScreenService} 的七参重载把「词表 / 总开关 / 抽检比例」全部显式传入，
 * 所以这里直接 {@code new ContentScreenService(null, null)} 就能覆盖四类规则、
 * 归一化、本人手机号豁免与抽检确定性——这是本阶段最该被锁死的业务逻辑。
 */
class ContentScreeningTest {

    /** 归一化后的词表快照（小写、去零宽）；分类与 ContentReviewCodes 一致。 */
    private static final List<SensitiveWordRule> RULES = List.of(
            new SensitiveWordRule("代做", "AD"),
            new SensitiveWordRule("qq", "AD"),
            new SensitiveWordRule("加微信", "AD"),
            new SensitiveWordRule("辱骂示例", "ABUSE"),
            new SensitiveWordRule("隐私示例", "PRIVACY"),
            new SensitiveWordRule("灌水示例", "IRRELEVANT")
    );

    private final ContentScreenService service = new ContentScreenService(null, null);

    private ContentScreenService.ScreenResult screen(String text, String attachments, String phone,
                                                     boolean enabled, int imageSampleRate) {
        return service.screen(text, attachments, phone, null, RULES, enabled, imageSampleRate);
    }

    // ------------------------------------------------------------------
    // 四类词表命中
    // ------------------------------------------------------------------

    @Test
    @DisplayName("① 广告导流词命中：只回分类名，不回传词表原文")
    void adWordHit() {
        ContentScreenService.ScreenResult result = screen("求代做课程设计", "[]", "13800000003", true, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.hitRules()).contains("AD:广告导流");
        assertThat(result.hitCategories()).contains("AD");
        // 命中的是「代做」这个词，但 hitRules 里不允许出现词表原文
        assertThat(result.hitRules()).doesNotContain("代做");
    }

    @Test
    @DisplayName("② 辱骂攻击词命中")
    void abuseWordHit() {
        ContentScreenService.ScreenResult result = screen("这就是辱骂示例，别理他", "[]", "13800000003", true, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.hitRules()).contains("ABUSE:辱骂攻击");
    }

    @Test
    @DisplayName("③ 他人手机号判 PRIVACY（11 位且 ≠ 本人注册号）")
    void otherPhoneHit() {
        ContentScreenService.ScreenResult result =
                screen("宿舍灯坏了，请联系 13800000009 上门", "[]", "13800000003", true, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.hitRules()).contains("PRIVACY:他人手机号");
    }

    @Test
    @DisplayName("④ 本人手机号豁免：报修人写自己的电话是正常业务")
    void ownPhoneExempt() {
        ContentScreenService.ScreenResult result =
                screen("宿舍灯坏了，请联系 13800000003 上门", "[]", "13800000003", true, 0);

        assertThat(result.passed()).isTrue();
        assertThat(result.hitRules()).isEmpty();
    }

    @Test
    @DisplayName("⑤ 18 位身份证号判 PRIVACY")
    void idCardHit() {
        ContentScreenService.ScreenResult result =
                screen("补办材料需要身份证 110101199003071234", "[]", "13800000003", true, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.hitRules()).contains("PRIVACY:身份证号");
    }

    @Test
    @DisplayName("⑥ 16~19 位银行卡号判 PRIVACY")
    void bankCardHit() {
        ContentScreenService.ScreenResult result =
                screen("维修款打到银行卡 6222020200001234567", "[]", "13800000003", true, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.hitRules()).contains("PRIVACY:银行卡号");
    }

    // ------------------------------------------------------------------
    // 与维修无关（确定性规则，不依赖词表）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑦ 去空白后长度 < 4 判 IRRELEVANT")
    void tooShortHit() {
        ContentScreenService.ScreenResult result = screen("好的", "[]", "13800000003", true, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.hitRules()).contains("IRRELEVANT:内容过短");
    }

    @Test
    @DisplayName("⑧ 纯数字判 IRRELEVANT")
    void pureNumberHit() {
        ContentScreenService.ScreenResult result = screen("1234567890", "[]", "13800000003", true, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.hitRules()).contains("IRRELEVANT:纯数字");
    }

    @Test
    @DisplayName("⑨ 纯标点符号判 IRRELEVANT")
    void purePunctuationHit() {
        ContentScreenService.ScreenResult result = screen("！！！！！！！", "[]", "13800000003", true, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.hitRules()).contains("IRRELEVANT:纯标点符号");
    }

    @Test
    @DisplayName("⑩ 单字符连续重复 ≥ 8 次判 IRRELEVANT")
    void repeatedCharHit() {
        ContentScreenService.ScreenResult result = screen("aaaaaaaa", "[]", "13800000003", true, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.hitRules()).contains("IRRELEVANT:单字符重复");
    }

    // ------------------------------------------------------------------
    // 归一化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑪ 全角 / 大小写 / 零宽字符归一化后仍命中")
    void normalizationStillHits() {
        // ＱＱ = 全角大写 → NFKC 转半角再小写；加\u200b微信 = 词中间插入零宽字符
        ContentScreenService.ScreenResult result =
                screen("有事ＱＱ联系，也可以加\u200b微信", "[]", "13800000003", true, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.hitRules()).contains("AD:广告导流");
    }

    // ------------------------------------------------------------------
    // 词表启停 / 总开关
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑫ 停用词不参与命中（buildActiveRules 过滤 enabled=0）")
    void disabledWordNotHit() {
        SysSensitiveWord enabledWord = new SysSensitiveWord();
        enabledWord.setWord("代做");
        enabledWord.setCategory("AD");
        enabledWord.setEnabled(1);

        SysSensitiveWord disabledWord = new SysSensitiveWord();
        disabledWord.setWord("刷单");
        disabledWord.setCategory("AD");
        disabledWord.setEnabled(0);

        List<SensitiveWordRule> activeRules =
                SensitiveWordService.buildActiveRules(List.of(enabledWord, disabledWord));

        assertThat(service.screen("可以帮忙刷单吗", "[]", "13800000003", null, activeRules, true, 0).passed())
                .as("停用词不应命中").isTrue();
        assertThat(service.screen("可以帮忙代做吗", "[]", "13800000003", null, activeRules, true, 0).passed())
                .as("启用词应命中").isFalse();
    }

    @Test
    @DisplayName("⑬ content_review.enabled=0 时全部直接通过")
    void enabledOffPassesEverything() {
        ContentScreenService.ScreenResult result =
                screen("求代做课程设计 13800000009", "[\"/uploads/a.jpg\"]", "13800000003", false, 100);

        assertThat(result.passed()).isTrue();
        assertThat(result.hitRules()).isEmpty();
        assertThat(result.hitCategories()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 图片抽检
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑭ 图片抽检比例=0 时永不命中，比例=100 时必命中且结果确定")
    void imageSamplingIsDeterministic() {
        String text = "宿舍灯不亮";
        String attachments = "[\"/uploads/demo/fault-01.jpg\"]";

        ContentScreenService.ScreenResult never = screen(text, attachments, "13800000003", true, 0);
        assertThat(never.passed()).isTrue();

        ContentScreenService.ScreenResult alwaysFirst = screen(text, attachments, "13800000003", true, 100);
        ContentScreenService.ScreenResult alwaysSecond = screen(text, attachments, "13800000003", true, 100);
        assertThat(alwaysFirst.passed()).isFalse();
        assertThat(alwaysFirst.hitRules()).contains("IMAGE:图片抽检");
        assertThat(alwaysSecond).isEqualTo(alwaysFirst);
    }

    @Test
    @DisplayName("⑮ 无图片时抽检比例=100 也不命中")
    void noImageNoSampling() {
        ContentScreenService.ScreenResult result = screen("宿舍灯不亮", "[]", "13800000003", true, 100);

        assertThat(result.passed()).isTrue();
    }

    // ------------------------------------------------------------------
    // 正常内容 / 多规则叠加
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑯ 正常报修文本通过（无命中、hitRules 为空串）")
    void normalTextPasses() {
        ContentScreenService.ScreenResult result =
                screen("宿舍吸顶灯闪烁后完全不亮，已确认不是灯泡问题", "[]", "13800000003", true, 30);

        assertThat(result.passed()).isTrue();
        assertThat(result.hitRules()).isEmpty();
    }

    @Test
    @DisplayName("⑰ 多规则叠加：广告词 + 他人手机号同时命中，命中顺序稳定")
    void multipleHits() {
        ContentScreenService.ScreenResult result =
                screen("灯坏了加微信 13800000009 详聊", "[]", "13800000003", true, 0);

        assertThat(result.passed()).isFalse();
        assertThat(result.hitRules()).isEqualTo("AD:广告导流|PRIVACY:他人手机号");
        assertThat(result.hitCategories()).isEqualTo("AD|PRIVACY");
    }
}
