package com.campus.repair.modules.assistant.llm;

/**
 * 大模型调用的传输层接缝（与 `modules/notification/spi/Notifier` 同一套路）。
 *
 * <p>把"怎么发 HTTP"从"怎么问、怎么解析、怎么降级"里切出来，于是
 * {@link AssistantLlmClient} 可以用一个假实现做完整的单元测试——**测试不需要联网、
 * 也不需要 mock 框架**。
 */
public interface AssistantLlmTransport {

    /**
     * POST 一段 JSON 并返回**原始响应体**——也就是 chat/completions 的**完整信封**
     * （{@code {"choices":[{"message":{"content":"…"}}]}}），
     * <b>不是</b>模型说的那段 content。
     *
     * <p>解包是 {@link AssistantLlmClient} 的职责（{@code extractAssistantContent}）。
     * 这条边界必须写清楚：把信封直接当 content 解析会让**每一次真实调用**都被判成
     * 格式错误，而假传输层返回的是 content，因此单元测试照不出来。
     *
     * @throws Exception 超时、连接失败、非 2xx —— 一律由调用方兜住并降级
     */
    String postJson(String url, String apiKey, String jsonBody) throws Exception;
}
