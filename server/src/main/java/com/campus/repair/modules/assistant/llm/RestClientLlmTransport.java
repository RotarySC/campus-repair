package com.campus.repair.modules.assistant.llm;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link AssistantLlmTransport} 的真实实现（Spring 6.1 的 {@code RestClient}）。
 *
 * <p>客户端在 {@link PostConstruct} 建一次并复用；超时取
 * {@code app.assistant.llm.timeout-ms}（默认 3000ms）——**助手永远不能把提单页拖住**。
 * 不配任何重试：超时即降级，重试只会让用户多等。
 */
@Component
@RequiredArgsConstructor
public class RestClientLlmTransport implements AssistantLlmTransport {

    private final AssistantLlmProperties properties;

    private RestClient restClient;

    @PostConstruct
    void init() {
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
