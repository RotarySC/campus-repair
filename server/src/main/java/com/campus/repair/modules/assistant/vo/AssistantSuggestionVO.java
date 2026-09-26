package com.campus.repair.modules.assistant.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 助手推荐结果。 */
@Data
public class AssistantSuggestionVO implements Serializable {

    /** 实际使用的引擎：{@code RULE} 或 {@code RULE+LLM} */
    private String engine;

    /**
     * 原样回显请求里的报修空间 id（可为 {@code null}）。
     *
     * <p>v1 是纯文本匹配，**空间不参与打分**（设计文档 §3.3/§7）——但既然请求体收了这个字段，
     * 就必须回显，否则它是个"收了却静默丢掉"的字段，调用方无从判断服务端到底看没看到它。
     */
    private Long spaceId;

    /**
     * 降级原因（仅在"本可以用大模型但没成功"时非空）：
     * {@code LLM_TIMEOUT} / {@code LLM_ERROR} / {@code LLM_INVALID_RESPONSE}。
     * 大模型**本就没开启**时为 {@code null}——那是正常模式，不是降级。
     */
    private String degradedReason;

    /** 一条都没命中：此时前端应展示 {@link #fallbackCategories} 而不是硬猜 */
    private boolean noMatch;

    private List<AssistantCandidateVO> candidates = new ArrayList<>();

    /** 首候选自带的自助排查步骤（可能为空） */
    private List<String> selfHelpTips = new ArrayList<>();

    /** {@code noMatch=true} 时回退给出的前几个大类，让用户自己去挑 */
    private List<FallbackCategory> fallbackCategories = new ArrayList<>();

    @Data
    public static class FallbackCategory implements Serializable {
        private Long id;
        private String name;
    }
}
