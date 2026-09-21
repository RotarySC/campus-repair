package com.campus.repair.assistant;

import com.campus.repair.modules.assistant.service.AssistantRuleEngine;
import com.campus.repair.modules.assistant.service.AssistantRuleEngine.Confidence;
import com.campus.repair.modules.assistant.service.AssistantRuleEngine.LeafKnowledge;
import com.campus.repair.modules.assistant.service.AssistantRuleEngine.Scored;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则引擎的纯单元测试：<b>不起 Spring、不连库、不联网</b>。
 *
 * <p>锁死的是"确定性"——同样的描述必须给出同样的候选与同样的顺序，
 * 以及"宁可不猜"（一条都没命中就返回空，由上层回退给大类，而不是硬凑一个候选）。
 */
class AssistantRuleEngineTest {

    private static LeafKnowledge leaf(long id, String name, String parent, int sortOrder, String keywords) {
        return new LeafKnowledge(id, name, 1L, parent, "PLUMBER", 24, sortOrder, keywords);
    }

    @Test
    @DisplayName("叶子名命中(+10) 压过单个关键词命中(+5)")
    void leafNameBeatsSingleKeyword() {
        LeafKnowledge byName = leaf(301, "水龙头漏水", "水暖管道", 1, null);
        LeafKnowledge byKeyword = leaf(302, "下水道堵塞", "水暖管道", 2, "水龙头,漏水");
        // 描述里既出现「水龙头漏水」的完整名，也命中 302 的两个关键词
        // 301: 名字 +10 = 10；302: 两个词 +5+5 = 10 → 同分，按 sortOrder 决定
        List<Scored> tie = AssistantRuleEngine.rank("水龙头漏水", List.of(byName, byKeyword), 3);
        assertThat(tie).hasSize(2);
        assertThat(tie.get(0).score()).isEqualTo(10);
        assertThat(tie.get(1).score()).isEqualTo(10);
        assertThat(tie.get(0).leaf().id()).isEqualTo(301L);
        assertThat(tie.get(0).confidence()).isEqualTo(Confidence.HIGH);

        // 只命中一个关键词时，名字命中必须赢
        List<Scored> clear = AssistantRuleEngine.rank("灯具损坏，不亮", List.of(
                leaf(311, "灯具损坏", "电气照明", 1, null),
                leaf(312, "插座跳闸", "电气照明", 2, "不亮")
        ), 3);
        assertThat(clear).extracting(s -> s.leaf().id()).containsExactly(311L, 312L);
        assertThat(clear.get(0).score()).isEqualTo(10);
        assertThat(clear.get(1).score()).isEqualTo(5);
        assertThat(clear.get(1).confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    @DisplayName("同分时按 sortOrder 升序、再按 id 升序——结果与输入顺序无关")
    void tieBreakIsDeterministic() {
        LeafKnowledge first = leaf(302, "下水道堵塞", "水暖管道", 2, "漏水");
        LeafKnowledge second = leaf(301, "水龙头漏水", "水暖管道", 1, "漏水");
        // 两个叶子都只命中同一个关键词「漏水」⇒ 同分，sortOrder 1 的那个在前
        List<Scored> ranked = AssistantRuleEngine.rank("漏水", List.of(first, second), 3);
        assertThat(ranked).extracting(s -> s.leaf().id()).containsExactly(301L, 302L);

        // 倒过来传参，结果必须一致（不许依赖入参顺序 / DB 返回顺序）
        List<Scored> reversed = AssistantRuleEngine.rank("漏水", List.of(second, first), 3);
        assertThat(reversed).extracting(s -> s.leaf().id()).containsExactly(301L, 302L);

        // sortOrder 也相同时，按 id 升序
        List<Scored> sameOrder = AssistantRuleEngine.rank("漏水", List.of(
                leaf(900, "乙类", "大类", 5, "漏水"),
                leaf(800, "甲类", "大类", 5, "漏水")
        ), 3);
        assertThat(sameOrder).extracting(s -> s.leaf().id()).containsExactly(800L, 900L);
    }

    @Test
    @DisplayName("长度小于 2 的关键词被忽略：不会因为「水」「灯」把整片分类命中")
    void singleCharKeywordsAreIgnored() {
        List<LeafKnowledge> leaves = List.of(
                leaf(301, "水龙头漏水", "水暖管道", 1, "水,灯,门"),
                leaf(311, "灯具损坏", "电气照明", 2, "水,灯,门")
        );
        assertThat(AssistantRuleEngine.rank("水灯门", leaves, 3)).isEmpty();
        // 大写/全角也不该例外
        assertThat(AssistantRuleEngine.splitKeywords("水,灯,门,水管")).containsExactly("水管");
    }

    @Test
    @DisplayName("同一个关键词重复出现只计一次分（词表里写重复不会虚高）")
    void duplicateKeywordCountsOnce() {
        List<Scored> ranked = AssistantRuleEngine.rank("漏水",
                List.of(leaf(301, "水龙头漏水", "水暖管道", 1, "漏水,漏水,漏水")), 3);
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).score()).isEqualTo(5);
        assertThat(ranked.get(0).matchedKeywords()).containsExactly("漏水");

        // 描述里重复出现同一个词，同样只算一次
        List<Scored> repeated = AssistantRuleEngine.rank("漏水漏水漏水",
                List.of(leaf(301, "水龙头漏水", "水暖管道", 1, "漏水")), 3);
        assertThat(repeated.get(0).score()).isEqualTo(5);
    }

    @Test
    @DisplayName("关键词里重复写了叶子名时，不重复计分")
    void keywordEqualToLeafNameIsNotDoubleCounted() {
        List<Scored> ranked = AssistantRuleEngine.rank("水龙头漏水",
                List.of(leaf(301, "水龙头漏水", "水暖管道", 1, "水龙头漏水")), 3);
        assertThat(ranked.get(0).score()).isEqualTo(10);
        assertThat(ranked.get(0).matchedKeywords()).containsExactly("水龙头漏水");
    }

    @Test
    @DisplayName("一条都没命中时返回空列表（宁可不猜，由上层回退给大类）")
    void noMatchReturnsEmpty() {
        List<LeafKnowledge> leaves = List.of(
                leaf(301, "水龙头漏水", "水暖管道", 1, "水龙头,滴水"),
                leaf(341, "网络不通", "弱电网络", 5, "断网,网线")
        );
        assertThat(AssistantRuleEngine.rank("今天天气不错，想去打球", leaves, 3)).isEmpty();
        assertThat(AssistantRuleEngine.rank("", leaves, 3)).isEmpty();
        assertThat(AssistantRuleEngine.rank(null, leaves, 3)).isEmpty();
        assertThat(AssistantRuleEngine.rank("漏水", List.of(), 3)).isEmpty();
        assertThat(AssistantRuleEngine.rank("漏水", leaves, 0)).isEmpty();
    }

    @Test
    @DisplayName("只命中大类名时是最弱信号：+3、置信度 LOW")
    void parentNameHitIsWeakest() {
        List<Scored> ranked = AssistantRuleEngine.rank("水暖管道那边有点问题",
                List.of(leaf(301, "水龙头漏水", "水暖管道", 1, "水龙头,滴水")), 3);
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).score()).isEqualTo(3);
        assertThat(ranked.get(0).confidence()).isEqualTo(Confidence.LOW);
        assertThat(ranked.get(0).matchedKeywords()).isEmpty();
        // 父名为空时不应崩、也不该命中
        assertThat(AssistantRuleEngine.rank("随便什么",
                List.of(new LeafKnowledge(301L, "水龙头漏水", 1L, null, "PLUMBER", 24, 1, "水龙头")), 3))
                .isEmpty();
    }

    @Test
    @DisplayName("空白与大小写归一：多空格、全角空格、大小写都不影响命中")
    void normalizationOfWhitespaceAndCase() {
        List<LeafKnowledge> leaves = List.of(leaf(341, "网络不通", "弱电网络", 1, "WIFI,断网"));
        assertThat(AssistantRuleEngine.rank("WiFi   连不上", leaves, 3)).hasSize(1);
        assertThat(AssistantRuleEngine.rank("房间里\u3000断网了", leaves, 3)).hasSize(1);
        assertThat(AssistantRuleEngine.normalize(" A b\tC\u3000D ")).isEqualTo("abcd");
    }

    @Test
    @DisplayName("关键词支持逗号/顿号/分号分隔，并保持书写顺序")
    void keywordSeparators() {
        assertThat(AssistantRuleEngine.splitKeywords("水龙头,龙头、滴水；渗水 关不紧"))
                .containsExactly("水龙头", "龙头", "滴水", "渗水", "关不紧");
        assertThat(AssistantRuleEngine.splitKeywords(null)).isEmpty();
        assertThat(AssistantRuleEngine.splitKeywords("   ")).isEmpty();
    }

    @Test
    @DisplayName("候选截断到 limit；工种与 SLA 原样透传给调用方")
    void limitAndSkillPassthrough() {
        List<LeafKnowledge> leaves = List.of(
                leaf(301, "甲", "大类", 1, "漏水"),
                leaf(302, "乙", "大类", 2, "漏水"),
                leaf(303, "丙", "大类", 3, "漏水")
        );
        List<Scored> one = AssistantRuleEngine.rank("漏水", leaves, 1);
        assertThat(one).hasSize(1);
        assertThat(one.get(0).leaf().id()).isEqualTo(301L);

        LeafKnowledge enriched = new LeafKnowledge(
                312L, "插座跳闸", 310L, "电气照明", "ELECTRICIAN", 8, 2, "跳闸");
        List<Scored> ranked = AssistantRuleEngine.rank("跳闸", List.of(enriched), 3);
        assertThat(ranked.get(0).leaf().skillCode()).isEqualTo("ELECTRICIAN");
        assertThat(ranked.get(0).leaf().slaHours()).isEqualTo(8);
    }

    @Test
    @DisplayName("置信度分档边界：≥10 HIGH、5~9 MEDIUM、1~4 LOW")
    void confidenceBoundaries() {
        assertThat(Confidence.of(10)).isEqualTo(Confidence.HIGH);
        assertThat(Confidence.of(18)).isEqualTo(Confidence.HIGH);
        assertThat(Confidence.of(9)).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.of(5)).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.of(4)).isEqualTo(Confidence.LOW);
        assertThat(Confidence.of(1)).isEqualTo(Confidence.LOW);
    }

    @Test
    @DisplayName("真实演示词表：三句典型描述的判定结果")
    void realWorldSeedVocabulary() {
        List<LeafKnowledge> leaves = List.of(
                leaf(301, "水龙头漏水", "水暖管道", 1, "水龙头,龙头,漏水,滴水,渗水,关不紧,一直流"),
                leaf(312, "插座跳闸", "电气照明", 2, "插座,跳闸,断电,没电,空开,冒烟,发烫"),
                leaf(321, "空调不制冷", "暖通空调", 1, "空调,不制冷,不凉,外机,异响"),
                leaf(341, "网络不通", "弱电网络", 1, "断网,没网,网线,无线,上不了网")
        );

        List<Scored> water = AssistantRuleEngine.rank("卫生间水龙头一直滴水，关不紧", leaves, 3);
        assertThat(water.get(0).leaf().id()).isEqualTo(301L);
        assertThat(water.get(0).confidence()).isEqualTo(Confidence.HIGH);

        List<Scored> power = AssistantRuleEngine.rank("插座冒烟，还闻到焦味", leaves, 3);
        assertThat(power.get(0).leaf().id()).isEqualTo(312L);
        assertThat(power.get(0).matchedKeywords()).contains("插座", "冒烟");

        List<Scored> net = AssistantRuleEngine.rank("宿舍网线插着但是上不了网", leaves, 3);
        assertThat(net.get(0).leaf().id()).isEqualTo(341L);
    }
}
