package com.campus.repair.modules.assistant.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 助手运行状态：让前端决定要不要渲染入口，也给运维留一个**不重启即可回滚**的开关。
 *
 * <p>{@code enabled=false} 时前端整块不渲染；{@code llmReady} 只表示"大模型这条路可用"。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssistantStatusVO implements Serializable {

    private boolean enabled;
    private boolean llmReady;
    /** 当前生效的引擎：{@code RULE} 或 {@code RULE+LLM} */
    private String engine;
}
