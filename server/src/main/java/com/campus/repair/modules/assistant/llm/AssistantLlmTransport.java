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
     * POST 一段 JSON 并返回响应体。
     *
     * @throws Exception 超时、连接失败、非 2xx —— 一律由调用方兜住并降级
     */
    String postJson(String url, String apiKey, String jsonBody) throws Exception;
}
