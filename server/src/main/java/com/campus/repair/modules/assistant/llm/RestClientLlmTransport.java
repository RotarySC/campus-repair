package com.campus.repair.modules.assistant.llm;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link AssistantLlmTransport} 的真实实现（Spring 6.1 的 {@code RestClient}）。
 *
 * <p>客户端在**构造时**建一次并复用；超时取
 * {@code app.assistant.llm.timeout-ms}（默认 3000ms）——**助手永远不能把提单页拖住**。
 * 不配任何重试：超时即降级，重试只会让用户多等。
 *
 * <p>建在构造时而不是 {@code @PostConstruct}：这样传输层可以**脱离 Spring 上下文**
 * 直接实例化，于是真实 HTTP 路径（地址、鉴权头、编码、真 socket 超时）能被
 * 一个本地回环服务端端到端验证——见 {@code RestClientLlmTransportTest}。
 * {@code AssistantLlmProperties} 是 {@code @ConfigurationProperties} bean，
 * 注入进来时已完成绑定，构造期读取超时值是安全的。
 */
@Component
public class RestClientLlmTransport implements AssistantLlmTransport {

    private final RestClient restClient;

    public RestClientLlmTransport(AssistantLlmProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getTimeoutMs());
        factory.setReadTimeout(properties.getTimeoutMs());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String postJson(String url, String apiKey, String jsonBody) {
        return restClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .body(String.class);
    }
}
