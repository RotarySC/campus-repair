package com.campus.repair.modules.assistant.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 大模型增强的配置（`app.assistant.llm.*`）。
 *
 * <p><b>双层开关的第一层</b>：这一层表示"能力是否就绪"（含密钥是否注入）；
 * 第二层是 `sys_config.assistant_llm_enabled`，运维可在不重启的前提下关掉。
 * 两层**都**满足才会真的走大模型——缺任一都退回纯规则。
 *
 * <p>密钥只从环境变量注入（`ASSISTANT_LLM_API_KEY`），**不落库、不进日志**。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.assistant.llm")
public class AssistantLlmProperties {

    /** 是否允许使用大模型（默认关） */
    private boolean enabled = false;

    /** OpenAI 兼容网关根地址，不带结尾斜杠，例如 https://api.example.com/v1 */
    private String baseUrl;

    private String apiKey;

    private String model;

    /**
     * 连接与读取超时（毫秒）：超时即降级，绝不阻塞提单页。
     *
     * <p><b>默认值必须与 `application.yml` 一致</b>：早期这里是 `3000`，实测思考模型的真实延迟
     * 1.8~4.9s，3000ms 会打掉近半请求报 `LLM_TIMEOUT`。字段默认值只在"没加载到那段 yml"的上下文
     * （新 profile、切片测试）里生效——那正是最不容易被发现的时候，所以不能让它留着旧值。
     */
    private int timeoutMs = 8000;

    /** 同上：思考模型的推理 token 也占 `max-tokens`，64 会把 `content` 挤空（实测 7 条错 3 条）。 */
    private int maxTokens = 512;

    /**
     * 能力是否就绪：开关打开 + 三项配置齐全。
     * 注意它**不**包含运行期开关（那是 {@code sys_config} 的事）。
     */
    public boolean isConfigured() {
        return enabled
                && StringUtils.hasText(baseUrl)
                && StringUtils.hasText(apiKey)
                && StringUtils.hasText(model);
    }

    /** 拼出 chat/completions 的完整地址（容忍 base-url 误带结尾斜杠）。 */
    public String chatCompletionsUrl() {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }
}
