package com.campus.repair.assistant;

import com.campus.repair.modules.assistant.llm.AssistantLlmClient;
import com.campus.repair.modules.assistant.llm.AssistantLlmProperties;
import com.campus.repair.modules.assistant.llm.RestClientLlmTransport;
import com.campus.repair.modules.assistant.service.AssistantRuleEngine.LeafKnowledge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RestClientLlmTransport} 的**真实 HTTP 路径**：起一个本地回环服务端，
 * 走真的 {@code RestClient}、真的 socket、真的 JSON 编码——不 mock 请求工厂。
 *
 * <p><b>为什么必须补这一层</b>：{@code AssistantLlmClientTest} 全部用手写的假传输层，
 * 而假传输层直接返回「模型的 content」。真实网关返回的却是 chat/completions 的
 * **信封**（content 是嵌在 {@code choices[0].message} 里的字符串）。
 * 两者形状不同，于是「信封没被解包」这类错误在纯单元测试里**结构上照不出来**——
 * 本类就是那面镜子。
 *
 * <p>它同时是唯一能验证**真超时**的地方：{@code MockRestServiceServer} 会替换掉
 * 请求工厂，socket 超时根本不会发生；这里的服务端接受连接后故意不响应，
 * 才能把「助手永远不拖住提单页」这条不变式钉死。
 */
class RestClientLlmTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_KEY = "sk-test-secret-value";

    private HttpServer server;
    private String baseUrl;

    /** 服务端收到的请求：计数 + 最后一次的报文细节 */
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String lastMethod;
    private volatile String lastPath;
    private volatile String lastAuth;
    private volatile String lastContentType;
    private volatile String lastBody;
    /** 下一次响应的状态码与响应体（默认 200 + 空体） */
    private volatile int statusCode = 200;
    private volatile String responseBody = "";
    /** 响应前先睡多久——用来造真实读超时 */
    private volatile long delayMillis = 0L;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fake-llm-gateway");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        lastMethod = exchange.getRequestMethod();
        lastPath = exchange.getRequestURI().getPath();
        lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
        lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(statusCode, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } catch (IOException ignored) {
            // 客户端已按超时放弃，回写必然失败——这是超时用例的预期路径
        }
    }

    // ------------------------------------------------------------------
    // 报文契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("真实 HTTP：方法/路径/Bearer/Content-Type/请求体都按 OpenAI 兼容约定发出")
    void sendsOpenAiCompatibleRequest() throws Exception {
        responseBody = envelope("{\"categoryIds\":[312]}");
        AssistantLlmProperties props = configured(2000);

        String body = new RestClientLlmTransport(props)
                .postJson(props.chatCompletionsUrl(), props.getApiKey(), "{\"ping\":1}");

        assertThat(body).isEqualTo(responseBody);
        assertThat(requests.get()).isEqualTo(1);
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/v1/chat/completions");
        assertThat(lastAuth).isEqualTo("Bearer " + API_KEY);
        assertThat(lastContentType).contains("application/json");
        assertThat(lastBody).isEqualTo("{\"ping\":1}");
    }

    @Test
    @DisplayName("base-url 结尾多几个斜杠也打在同一条路径上（配置手误不该变成 404）")
    void trailingSlashBaseUrlHitsSamePath() throws Exception {
        responseBody = envelope("{\"categoryIds\":[301]}");
        AssistantLlmProperties props = configured(2000);
        props.setBaseUrl(baseUrl + "///");

        assertThat(props.chatCompletionsUrl()).isEqualTo(baseUrl + "/chat/completions");
        new RestClientLlmTransport(props).postJson(props.chatCompletionsUrl(), API_KEY, "{}");

        assertThat(lastPath).isEqualTo("/v1/chat/completions");
    }

    // ------------------------------------------------------------------
    // 端到端：真信封 → 真解析
    // ------------------------------------------------------------------

    @Test
    @DisplayName("端到端：真实网关信封（content 嵌在 choices[0].message 里）也能取出分类 id")
    void readsCategoryIdsFromRealGatewayEnvelope() {
        responseBody = envelope("{\"categoryIds\":[312]}");
        AssistantLlmProperties props = configured(2000);
        AssistantLlmClient client = new AssistantLlmClient(props, new RestClientLlmTransport(props));

        AssistantLlmClient.Outcome outcome = client.pickCategoryIds("插座冒烟", candidates(), 3);

        assertThat(outcome.degradedReason())
                .as("真实网关返回的是 chat/completions 信封，不是裸的 categoryIds")
                .isNull();
        assertThat(outcome.categoryIds()).containsExactly(312L);
        // 中文（候选清单与用户描述）必须原样过线，不能被编码成问号
        assertThat(lastBody).contains("水龙头漏水").contains("插座冒烟");
    }

    @Test
    @DisplayName("端到端：封闭集之外的 id 被丢弃、重复被去重")
    void filtersIdsOutsideClosedSetOverRealHttp() {
        responseBody = envelope("{\"categoryIds\":[999,301,301]}");
        AssistantLlmProperties props = configured(2000);
        AssistantLlmClient client = new AssistantLlmClient(props, new RestClientLlmTransport(props));

        AssistantLlmClient.Outcome outcome = client.pickCategoryIds("漏水", candidates(), 3);

        assertThat(outcome.isDegraded()).isFalse();
        assertThat(outcome.categoryIds()).containsExactly(301L);
    }

    @Test
    @DisplayName("端到端：content 带 ```json 围栏也能解析")
    void fencedContentIsParsed() {
        responseBody = envelope("```json\n{\"categoryIds\":[301]}\n```");
        AssistantLlmProperties props = configured(2000);
        AssistantLlmClient client = new AssistantLlmClient(props, new RestClientLlmTransport(props));

        AssistantLlmClient.Outcome outcome = client.pickCategoryIds("漏水", candidates(), 3);

        assertThat(outcome.isDegraded()).isFalse();
        assertThat(outcome.categoryIds()).containsExactly(301L);
    }

    // ------------------------------------------------------------------
    // 真实故障：非 2xx / 真超时 / 非法 content
    // ------------------------------------------------------------------

    @Test
    @DisplayName("非 2xx → LLM_ERROR，且不抛异常")
    void serverErrorDegradesToError() {
        statusCode = 500;
        responseBody = "{\"error\":{\"message\":\"boom\"}}";
        AssistantLlmProperties props = configured(2000);
        AssistantLlmClient client = new AssistantLlmClient(props, new RestClientLlmTransport(props));

        AssistantLlmClient.Outcome outcome = client.pickCategoryIds("漏水", candidates(), 3);

        assertThat(outcome.degradedReason()).isEqualTo(AssistantLlmClient.REASON_ERROR);
        assertThat(outcome.categoryIds()).isEmpty();
    }

    @Test
    @DisplayName("真实读超时 → LLM_TIMEOUT（服务端接受连接后不响应，不是 mock 出来的超时）")
    void readTimeoutDegradesToTimeout() {
        delayMillis = 1500L;
        responseBody = envelope("{\"categoryIds\":[301]}");
        AssistantLlmProperties props = configured(200);
        AssistantLlmClient client = new AssistantLlmClient(props, new RestClientLlmTransport(props));

        AssistantLlmClient.Outcome outcome = client.pickCategoryIds("漏水", candidates(), 3);

        assertThat(outcome.degradedReason()).isEqualTo(AssistantLlmClient.REASON_TIMEOUT);
        assertThat(outcome.categoryIds()).isEmpty();
    }

    @Test
    @DisplayName("信封里是自然语言 → LLM_INVALID_RESPONSE（空数组才是合法的「没把握」）")
    void nonJsonContentDegradesToInvalid() {
        responseBody = envelope("我觉得应该是水龙头坏了，你看着办");
        AssistantLlmProperties props = configured(2000);
        AssistantLlmClient client = new AssistantLlmClient(props, new RestClientLlmTransport(props));

        AssistantLlmClient.Outcome outcome = client.pickCategoryIds("漏水", candidates(), 3);

        assertThat(outcome.degradedReason()).isEqualTo(AssistantLlmClient.REASON_INVALID);
    }

    // ------------------------------------------------------------------
    // 不发请求的情形
    // ------------------------------------------------------------------

    @Test
    @DisplayName("配置不就绪时一个 HTTP 请求都不发")
    void notConfiguredMakesNoHttpCall() {
        AssistantLlmProperties props = configured(2000);
        props.setEnabled(false);
        AssistantLlmClient client = new AssistantLlmClient(props, new RestClientLlmTransport(props));

        AssistantLlmClient.Outcome outcome = client.pickCategoryIds("漏水", candidates(), 3);

        assertThat(outcome.categoryIds()).isEmpty();
        assertThat(outcome.isDegraded()).isFalse();
        assertThat(requests.get()).isZero();
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private AssistantLlmProperties configured(int timeoutMs) {
        AssistantLlmProperties props = new AssistantLlmProperties();
        props.setEnabled(true);
        props.setBaseUrl(baseUrl);
        props.setApiKey(API_KEY);
        props.setModel("test-model");
        props.setTimeoutMs(timeoutMs);
        return props;
    }

    /** 造一个**真实形状**的 chat/completions 响应：模型输出是字符串形式的 content。 */
    private static String envelope(String content) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "chatcmpl-test");
        root.put("object", "chat.completion");
        root.put("model", "test-model");
        ArrayNode choices = root.putArray("choices");
        ObjectNode message = choices.addObject().putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        return root.toString();
    }

    private static List<LeafKnowledge> candidates() {
        return List.of(
                new LeafKnowledge(301L, "水龙头漏水", 300L, "水暖管道", "PLUMBER", 24, 1, "水龙头,滴水"),
                new LeafKnowledge(312L, "插座跳闸", 310L, "电气照明", "ELECTRICIAN", 8, 2, "插座,跳闸"));
    }
}
