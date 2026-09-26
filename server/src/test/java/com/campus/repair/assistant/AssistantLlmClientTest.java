package com.campus.repair.assistant;

import com.campus.repair.modules.assistant.llm.AssistantLlmClient;
import com.campus.repair.modules.assistant.llm.AssistantLlmProperties;
import com.campus.repair.modules.assistant.llm.AssistantLlmTransport;
import com.campus.repair.modules.assistant.service.AssistantRuleEngine.LeafKnowledge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 大模型增强的单元测试：<b>用一个假传输层，不联网、不起 Spring</b>。
 *
 * <p>要守住的核心是"助手永远不会因为大模型出问题而变成错误页"：
 * 超时/报错/格式不对都必须降级成可识别的 reason，并把结果交回纯规则。
 */
class AssistantLlmClientTest {

    /** 假传输层：记录调用次数，按需返回响应或抛异常 */
    private static final class FakeTransport implements AssistantLlmTransport {
        private final AtomicInteger calls = new AtomicInteger();
        private final String response;
        private final Exception failure;
        private String lastUrl;
        private String lastApiKey;
        private String lastBody;

        private FakeTransport(String response, Exception failure) {
            this.response = response;
            this.failure = failure;
        }

        static FakeTransport returning(String response) {
            return new FakeTransport(response, null);
        }

        static FakeTransport failing(Exception failure) {
            return new FakeTransport(null, failure);
        }

        @Override
        public String postJson(String url, String apiKey, String jsonBody) throws Exception {
            calls.incrementAndGet();
            this.lastUrl = url;
            this.lastApiKey = apiKey;
            this.lastBody = jsonBody;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    private static AssistantLlmProperties configured() {
        AssistantLlmProperties props = new AssistantLlmProperties();
        props.setEnabled(true);
        props.setBaseUrl("https://llm.example.com/v1");
        props.setApiKey("sk-test-secret-value");
        props.setModel("test-model");
        props.setTimeoutMs(1500);
        return props;
    }

    private static List<LeafKnowledge> candidates() {
        return List.of(
                new LeafKnowledge(301L, "水龙头漏水", 300L, "水暖管道", "PLUMBER", 24, 1, "水龙头,滴水"),
                new LeafKnowledge(312L, "插座跳闸", 310L, "电气照明", "ELECTRICIAN", 8, 2, "插座,跳闸"));
    }

    // ------------------------------------------------------------------
    // filterAllowedIds：纯函数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("解析正常响应，只保留封闭集里的 id")
    void filterKeepsAllowedIdsOnly() {
        Set<Long> allowed = Set.of(301L, 312L);
        assertThat(AssistantLlmClient.filterAllowedIds("{\"categoryIds\":[301,312]}", allowed, 3))
                .containsExactly(301L, 312L);
        // 幻觉 id 与注入 id 都被丢弃
        assertThat(AssistantLlmClient.filterAllowedIds("{\"categoryIds\":[999,301,404]}", allowed, 3))
                .containsExactly(301L);
        // 去重
        assertThat(AssistantLlmClient.filterAllowedIds("{\"categoryIds\":[301,301,312]}", allowed, 3))
                .containsExactly(301L, 312L);
        // 截断
        assertThat(AssistantLlmClient.filterAllowedIds("{\"categoryIds\":[301,312]}", allowed, 1))
                .containsExactly(301L);
        // 字符串形式的 id 被宽容接受（有些网关会把数字序列化成 "301"），null 与垃圾字符串跳过
        assertThat(AssistantLlmClient.filterAllowedIds("{\"categoryIds\":[\"301\",null,312,\"abc\"]}", allowed, 3))
                .containsExactly(301L, 312L);
    }

    @Test
    @DisplayName("容忍 Markdown 代码块围栏与前后废话")
    void filterToleratesFenceAndProse() {
        Set<Long> allowed = Set.of(301L, 312L);
        assertThat(AssistantLlmClient.filterAllowedIds("```json\n{\"categoryIds\":[312]}\n```", allowed, 3))
                .containsExactly(312L);
        assertThat(AssistantLlmClient.filterAllowedIds(
                "好的，我认为是：{\"categoryIds\":[312]} 以上。", allowed, 3))
                .containsExactly(312L);
    }

    @Test
    @DisplayName("不是约定形状时返回 null（与「模型没把握」的空列表区分开）")
    void filterReturnsNullForInvalidShape() {
        Set<Long> allowed = Set.of(301L);
        assertThat(AssistantLlmClient.filterAllowedIds("我觉得是水龙头坏了", allowed, 3)).isNull();
        assertThat(AssistantLlmClient.filterAllowedIds("{not json at all", allowed, 3)).isNull();
        assertThat(AssistantLlmClient.filterAllowedIds("{\"ids\":[301]}", allowed, 3)).isNull();
        assertThat(AssistantLlmClient.filterAllowedIds("{\"categoryIds\":301}", allowed, 3)).isNull();
        // 空响应体也是"形状不对"
        assertThat(AssistantLlmClient.filterAllowedIds("   ", allowed, 3)).isNull();
        assertThat(AssistantLlmClient.filterAllowedIds(null, allowed, 3)).isNull();
        // 但合法的空数组是"模型没把握"，不是格式错误
        assertThat(AssistantLlmClient.filterAllowedIds("{\"categoryIds\":[]}", allowed, 3)).isEmpty();
        // 调用方参数本身不合法时给空列表，不必当成模型的问题
        assertThat(AssistantLlmClient.filterAllowedIds("{\"categoryIds\":[301]}", Set.of(), 3)).isEmpty();
        assertThat(AssistantLlmClient.filterAllowedIds("{\"categoryIds\":[301]}", allowed, 0)).isEmpty();
    }

    // ------------------------------------------------------------------
    // extractAssistantContent：把网关信封解成模型说的话
    // ------------------------------------------------------------------

    @Test
    @DisplayName("从 choices[0].message.content 里取出模型输出（真实网关的形状）")
    void extractsContentFromGatewayEnvelope() {
        assertThat(AssistantLlmClient.extractAssistantContent(
                "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,"
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"categoryIds\\\":[301]}\"}}]}"))
                .isEqualTo("{\"categoryIds\":[301]}");
        // 多条 choices 时取第一条
        assertThat(AssistantLlmClient.extractAssistantContent(
                "{\"choices\":[{\"message\":{\"content\":\"first\"}},"
                        + "{\"message\":{\"content\":\"second\"}}]}"))
                .isEqualTo("first");
    }

    @Test
    @DisplayName("拿不到 content 时原样返回响应体（容忍已解包的网关，不静默给错答案）")
    void fallsBackToRawBodyWhenEnvelopeIsAbsent() {
        // 网关已经帮我们解包：顶层就是 categoryIds
        assertThat(AssistantLlmClient.extractAssistantContent("{\"categoryIds\":[301]}"))
                .isEqualTo("{\"categoryIds\":[301]}");
        // 不是 JSON
        assertThat(AssistantLlmClient.extractAssistantContent("我觉得是水龙头坏了"))
                .isEqualTo("我觉得是水龙头坏了");
        // choices 在但 content 不是文本（例如某些网关给数组）：仍回落，最终判为格式错误
        assertThat(AssistantLlmClient.extractAssistantContent(
                "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\"}]}}]}"))
                .contains("choices");
        // 空体原样交回
        assertThat(AssistantLlmClient.extractAssistantContent("   ")).isEqualTo("   ");
        assertThat(AssistantLlmClient.extractAssistantContent(null)).isNull();
    }

    // ------------------------------------------------------------------
    // pickCategoryIds：降级矩阵
    // ------------------------------------------------------------------

    @Test
    @DisplayName("配置齐全时发出请求：地址/鉴权/封闭候选清单都正确，且不含密钥")
    void pickSendsClosedCatalog() {
        FakeTransport transport = FakeTransport.returning("{\"categoryIds\":[312]}");
        AssistantLlmClient client = new AssistantLlmClient(configured(), transport);

        AssistantLlmClient.Outcome outcome = client.pickCategoryIds("插座冒烟", candidates(), 3);

        assertThat(outcome.isDegraded()).isFalse();
        assertThat(outcome.categoryIds()).containsExactly(312L);
        assertThat(transport.calls.get()).isEqualTo(1);
        assertThat(transport.lastUrl).isEqualTo("https://llm.example.com/v1/chat/completions");
        assertThat(transport.lastApiKey).isEqualTo("sk-test-secret-value");
        // 封闭候选清单必须在提示词里（这是"禁止编造"的唯一依据）
        assertThat(transport.lastBody).contains("301|水龙头漏水|水暖管道|PLUMBER");
        assertThat(transport.lastBody).contains("312|插座跳闸|电气照明|ELECTRICIAN");
        assertThat(transport.lastBody).contains("插座冒烟");
        assertThat(transport.lastBody).contains("test-model");
        // 密钥不能出现在请求体里
        assertThat(transport.lastBody).doesNotContain("sk-test-secret-value");
    }

    @Test
    @DisplayName("超时 → LLM_TIMEOUT；其他异常 → LLM_ERROR；都不是致命错误")
    void failureModesDegrade() {
        AssistantLlmClient timeoutClient = new AssistantLlmClient(configured(),
                FakeTransport.failing(new SocketTimeoutException("read timed out")));
        AssistantLlmClient.Outcome timeout = timeoutClient.pickCategoryIds("漏水", candidates(), 3);
        assertThat(timeout.isDegraded()).isTrue();
        assertThat(timeout.degradedReason()).isEqualTo(AssistantLlmClient.REASON_TIMEOUT);
        assertThat(timeout.categoryIds()).isEmpty();

        AssistantLlmClient errorClient = new AssistantLlmClient(configured(),
                FakeTransport.failing(new IllegalStateException("500 Internal Server Error")));
        AssistantLlmClient.Outcome error = errorClient.pickCategoryIds("漏水", candidates(), 3);
        assertThat(error.degradedReason()).isEqualTo(AssistantLlmClient.REASON_ERROR);

        // 被包装过的超时（RestClient 会把 SocketTimeoutException 包一层）也要认出来
        AssistantLlmClient wrappedClient = new AssistantLlmClient(configured(),
                FakeTransport.failing(new RuntimeException("io error", new SocketTimeoutException("timeout"))));
        assertThat(wrappedClient.pickCategoryIds("漏水", candidates(), 3).degradedReason())
                .isEqualTo(AssistantLlmClient.REASON_TIMEOUT);
    }

    @Test
    @DisplayName("响应格式不对 → LLM_INVALID_RESPONSE；空数组是合法的「没把握」")
    void invalidResponseDegrades() {
        AssistantLlmClient invalidClient = new AssistantLlmClient(configured(),
                FakeTransport.returning("我觉得应该是水龙头坏了，你看着办"));
        AssistantLlmClient.Outcome invalid = invalidClient.pickCategoryIds("漏水", candidates(), 3);
        assertThat(invalid.degradedReason()).isEqualTo(AssistantLlmClient.REASON_INVALID);

        AssistantLlmClient emptyClient = new AssistantLlmClient(configured(),
                FakeTransport.returning("{\"categoryIds\":[]}"));
        AssistantLlmClient.Outcome empty = emptyClient.pickCategoryIds("漏水", candidates(), 3);
        assertThat(empty.isDegraded()).isFalse();
        assertThat(empty.categoryIds()).isEmpty();
    }

    @Test
    @DisplayName("配置不就绪时不发请求（连传输层都不碰）")
    void notConfiguredSkipsTransport() {
        AssistantLlmProperties props = new AssistantLlmProperties();
        props.setEnabled(true);
        props.setBaseUrl("https://llm.example.com/v1");
        props.setModel("test-model");
        // 少了 api-key
        FakeTransport transport = FakeTransport.returning("{\"categoryIds\":[301]}");
        AssistantLlmClient client = new AssistantLlmClient(props, transport);

        assertThat(client.isConfigured()).isFalse();
        AssistantLlmClient.Outcome outcome = client.pickCategoryIds("漏水", candidates(), 3);
        assertThat(outcome.isDegraded()).isFalse();
        assertThat(outcome.categoryIds()).isEmpty();
        assertThat(transport.calls.get()).isZero();

        // enabled=false 同理
        props.setEnabled(false);
        props.setApiKey("sk-x");
        assertThat(new AssistantLlmClient(props, transport).isConfigured()).isFalse();
        assertThat(transport.calls.get()).isZero();
    }

    @Test
    @DisplayName("base-url 结尾多一个斜杠也能拼对地址（配置手误不该导致降级）")
    void baseUrlTrailingSlash() {
        AssistantLlmProperties props = configured();
        props.setBaseUrl("https://llm.example.com/v1/");
        assertThat(props.chatCompletionsUrl()).isEqualTo("https://llm.example.com/v1/chat/completions");
        props.setBaseUrl("https://llm.example.com/v1///");
        assertThat(props.chatCompletionsUrl()).isEqualTo("https://llm.example.com/v1/chat/completions");
    }
}
