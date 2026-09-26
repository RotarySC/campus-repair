package com.campus.repair.modules.assistant.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.modules.assistant.llm.AssistantLlmClient;
import com.campus.repair.modules.assistant.service.AssistantRuleEngine.LeafKnowledge;
import com.campus.repair.modules.assistant.service.AssistantRuleEngine.Scored;
import com.campus.repair.modules.assistant.vo.AssistantCandidateVO;
import com.campus.repair.modules.assistant.vo.AssistantFaqItemVO;
import com.campus.repair.modules.assistant.vo.AssistantOrderProgressVO;
import com.campus.repair.modules.assistant.vo.AssistantStatusVO;
import com.campus.repair.modules.assistant.vo.AssistantSuggestionVO;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SysConfigService;
import com.campus.repair.modules.spatial.vo.RepairCategoryVO;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import com.campus.repair.modules.workorder.vo.DispatchOrderItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能助手：为报修人判断故障类别、给快捷入口所需的上下文、回答制度问题、速查本人工单进度。
 *
 * <p><b>模块边界</b>：只经 Service 触达别的模块（故障字典 / 工单 / 系统参数），
 * 不注入任何跨模块 Mapper——遵守"严禁跨模块穿透持久层 Mapper"。
 *
 * <p><b>铁律</b>：助手只出建议。它不提交工单、不改表单，一切要用户点「采用」。
 */
@Service
@RequiredArgsConstructor
public class AssistantService {

    public static final String ENGINE_RULE = "RULE";
    public static final String ENGINE_RULE_LLM = "RULE+LLM";

    static final String CFG_ENABLED = "assistant_enabled";
    static final String CFG_LLM_ENABLED = "assistant_llm_enabled";
    static final String CFG_MAX_CANDIDATES = "assistant_max_candidates";

    private static final int DEFAULT_ENABLED = 1;
    private static final int DEFAULT_LLM_ENABLED = 0;
    private static final int DEFAULT_MAX_CANDIDATES = 3;

    /** 大类回退最多给几个 */
    private static final int FALLBACK_CATEGORY_LIMIT = 5;
    /** 路径分隔符：与提单页的 findPathName 逐字符一致 */
    private static final String PATH_SEPARATOR = " / ";
    /** 自助排查提示的步骤分隔符 */
    private static final String TIP_SEPARATOR = "|";

    private final RepairCategoryService categoryService;
    private final WorkOrderService workOrderService;
    private final SysConfigService configService;
    private final AssistantFaqProvider faqProvider;
    private final AssistantLlmClient llmClient;

    /** 状态识别不了、或某个状态漏配了「下一步」时的兜底文案 */
    private static final String FALLBACK_NEXT_STEP = "请到「我的工单」查看详情";

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public AssistantStatusVO status() {
        boolean enabled = isAssistantEnabled();
        boolean llmReady = enabled && isLlmEnabled();
        return new AssistantStatusVO(enabled, llmReady, llmReady ? ENGINE_RULE_LLM : ENGINE_RULE);
    }

    /**
     * 判断故障细类。
     *
     * <p>流程：本地规则先算 → 若大模型可用则**追加/重排**（不淘汰规则命中）→ 组装 VO。
     * 关掉总开关时返回空结果（前端本来就不该调，这里只是防御）。
     */
    public AssistantSuggestionVO suggest(String description) {
        AssistantSuggestionVO result = new AssistantSuggestionVO();
        result.setEngine(ENGINE_RULE);

        if (!isAssistantEnabled()) {
            result.setNoMatch(true);
            return result;
        }

        Dictionary dict = loadDictionary();
        if (dict.leaves().isEmpty()) {
            // 字典里一道叶子都没有：如实说"判断不了"，不要硬凑
            result.setNoMatch(true);
            result.setFallbackCategories(dict.fallback());
            return result;
        }

        int limit = maxCandidates();
        List<Scored> ranked = AssistantRuleEngine.rank(description, dict.leaves(), limit);

        String degradedReason = null;
        if (isLlmEnabled()) {
            AssistantLlmClient.Outcome outcome = llmClient.pickCategoryIds(description, dict.leaves(), limit);
            degradedReason = outcome.degradedReason();
            if (!outcome.categoryIds().isEmpty()) {
                ranked = merge(outcome.categoryIds(), ranked, dict.byId(), limit);
                result.setEngine(ENGINE_RULE_LLM);
            }
        }
        result.setDegradedReason(degradedReason);

        if (ranked.isEmpty()) {
            result.setNoMatch(true);
            result.setFallbackCategories(dict.fallback());
            return result;
        }

        List<AssistantCandidateVO> candidates = new ArrayList<>();
        for (Scored scored : ranked) {
            candidates.add(toCandidate(scored));
        }
        result.setCandidates(candidates);
        result.setSelfHelpTips(dict.selfHelpTips().getOrDefault(ranked.get(0).leaf().id(), List.of()));
        return result;
    }

    // ------------------------------------------------------------------
    // 制度问答
    // ------------------------------------------------------------------

    /**
     * 制度问答：按调用者<b>职能</b>裁剪内容（通用 + 报修人 / 维修作业 / 调度·平台管理，多职能取并集）。
     *
     * <p>职能由控制器从登录态取出后传进来，本模块不自己读 SecurityContext——
     * 这样"给谁看什么"是一个纯参数，单测不必伪造认证上下文。
     */
    public List<AssistantFaqItemVO> faq(List<String> functions) {
        return faqProvider.listFaq(functions);
    }

    // ------------------------------------------------------------------
    // 我的工单进度速查
    // ------------------------------------------------------------------

    /**
     * 最近一张单的进度。
     *
     * <p>直接复用 {@code getMyOrders(userId, 1, 1, null)}：它已经按创建时间倒序、且已按
     * {@code student_user_id} 自限，所以**不存在按 id 直读的越权面**。
     *
     * @return 没有任何工单时返回 {@code null}
     */
    public AssistantOrderProgressVO myProgress(Long userId) {
        if (userId == null) {
            return null;
        }
        Page<DispatchOrderItemVO> page = workOrderService.getMyOrders(userId, 1, 1, null);
        if (page == null || page.getRecords() == null || page.getRecords().isEmpty()) {
            return null;
        }
        DispatchOrderItemVO order = page.getRecords().get(0);

        AssistantOrderProgressVO vo = new AssistantOrderProgressVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setStatus(order.getStatus());
        vo.setStatusLabel(OrderStatusNarrative.statusLabel(order.getStatus()));
        String nextStep = OrderStatusNarrative.nextStep(order.getStatus());
        vo.setNextStep(nextStep != null ? nextStep : FALLBACK_NEXT_STEP);
        vo.setSpaceFullPath(order.getSpaceFullPath());
        vo.setCategoryName(order.getCategoryFullName());
        vo.setWorkerName(order.getWorkerName());
        vo.setAppointmentPeriod(order.getAppointmentPeriod());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setSlaDeadline(order.getSlaDeadline());
        vo.setSlaTimeout(order.getIsSlaTimeout());
        return vo;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 字典快照：叶子知识 + 叶子 VO（取自助提示/路径）+ 大类回退项。 */
    private record Dictionary(
            List<LeafKnowledge> leaves,
            Map<Long, RepairCategoryVO> byId,
            Map<Long, List<String>> selfHelpTips,
            List<AssistantSuggestionVO.FallbackCategory> fallback) {
    }

    private Dictionary loadDictionary() {
        List<RepairCategoryVO> tree = categoryService.getCategoryTree(true);
        List<LeafKnowledge> leaves = new ArrayList<>();
        Map<Long, RepairCategoryVO> byId = new LinkedHashMap<>();
        Map<Long, List<String>> tips = new HashMap<>();
        List<AssistantSuggestionVO.FallbackCategory> fallback = new ArrayList<>();

        for (RepairCategoryVO node : tree) {
            if (isLevel1(node)) {
                AssistantSuggestionVO.FallbackCategory item = new AssistantSuggestionVO.FallbackCategory();
                item.setId(node.getId());
                item.setName(node.getName());
                fallback.add(item);
            }
            collectLeaves(node, node.getName(), leaves, byId, tips);
        }
        if (fallback.size() > FALLBACK_CATEGORY_LIMIT) {
            fallback = new ArrayList<>(fallback.subList(0, FALLBACK_CATEGORY_LIMIT));
        }
        return new Dictionary(leaves, byId, tips, fallback);
    }

    /**
     * 递归收集叶子（正常是两级，但字典接口本身是递归建的，这里跟着递归以免漏掉更深的结构）。
     *
     * @param parentName 最近一级父节点的名字（用于兜底命中与路径展示）
     */
    private void collectLeaves(RepairCategoryVO node,
                               String parentName,
                               List<LeafKnowledge> leaves,
                               Map<Long, RepairCategoryVO> byId,
                               Map<Long, List<String>> tips) {
        if (node == null) {
            return;
        }
        List<RepairCategoryVO> children = node.getChildren();
        if (children != null && !children.isEmpty()) {
            for (RepairCategoryVO child : children) {
                collectLeaves(child, node.getName(), leaves, byId, tips);
            }
            return;
        }
        // 没有子节点 = 叶子；只有二级细类才有工种与 SLA，可被推荐
        if (node.getLevel() == null || node.getLevel() != 2) {
            return;
        }
        String parent = StringUtils.hasText(parentName) ? parentName : "";
        leaves.add(new LeafKnowledge(
                node.getId(),
                node.getName(),
                node.getParentId(),
                parent,
                node.getWorkerSkillCode(),
                node.getSlaHours(),
                node.getSortOrder(),
                node.getKeywords()));
        byId.put(node.getId(), node);
        tips.put(node.getId(), splitTips(node.getSelfHelpTip()));
    }

    private static boolean isLevel1(RepairCategoryVO node) {
        return node != null && node.getLevel() != null && node.getLevel() == 1;
    }

    private static List<String> splitTips(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        List<String> steps = new ArrayList<>();
        for (String step : raw.split("\\" + TIP_SEPARATOR)) {
            String trimmed = step.trim();
            if (!trimmed.isEmpty()) {
                steps.add(trimmed);
            }
        }
        return steps;
    }

    private AssistantCandidateVO toCandidate(Scored scored) {
        AssistantCandidateVO vo = new AssistantCandidateVO();
        vo.setCategoryId(scored.leaf().id());
        vo.setCategoryName(scored.leaf().name());
        vo.setParentId(scored.leaf().parentId());
        vo.setParentName(scored.leaf().parentName());
        vo.setSkillCode(scored.leaf().skillCode());
        vo.setSlaHours(scored.leaf().slaHours());
        vo.setConfidence(scored.confidence().name());
        vo.setMatchedKeywords(scored.matchedKeywords());
        String parent = scored.leaf().parentName();
        vo.setCategoryPath(StringUtils.hasText(parent)
                ? parent + PATH_SEPARATOR + scored.leaf().name()
                : scored.leaf().name());
        return vo;
    }

    /**
     * 合并大模型结果与规则结果。
     *
     * <p><b>不变式</b>：大模型只能**追加或重排**，不能淘汰规则命中——所以"开了大模型"
     * 不会让结果比纯规则更差。模型挑的排前面（保留模型的相对顺序），规则命中按原序补足。
     */
    private List<Scored> merge(List<Long> llmIds,
                               List<Scored> ruleRanked,
                               Map<Long, RepairCategoryVO> byId,
                               int limit) {
        Map<Long, Scored> ruleById = new LinkedHashMap<>();
        for (Scored scored : ruleRanked) {
            ruleById.put(scored.leaf().id(), scored);
        }

        List<Scored> merged = new ArrayList<>();
        for (Long id : llmIds) {
            Scored existing = ruleById.remove(id);
            if (existing != null) {
                merged.add(existing);
            } else {
                // 规则没命中但模型选了：补一条（分数给关键词档，并说明"由模型推荐"）
                Scored promoted = promote(id, byId);
                if (promoted != null) {
                    merged.add(promoted);
                }
            }
        }
        for (Scored rest : ruleRanked) {
            if (ruleById.containsKey(rest.leaf().id())) {
                merged.add(rest);
            }
        }
        return merged.size() > limit ? new ArrayList<>(merged.subList(0, limit)) : merged;
    }

    private Scored promote(Long id, Map<Long, RepairCategoryVO> byId) {
        RepairCategoryVO node = byId.get(id);
        if (node == null) {
            return null;
        }
        LeafKnowledge leaf = new LeafKnowledge(
                node.getId(), node.getName(), node.getParentId(), null,
                node.getWorkerSkillCode(), node.getSlaHours(), node.getSortOrder(), node.getKeywords());
        return new Scored(leaf, AssistantRuleEngine.WEIGHT_KEYWORD,
                AssistantRuleEngine.Confidence.MEDIUM, List.of());
    }

    private boolean isAssistantEnabled() {
        return configService.getIntValue(CFG_ENABLED, DEFAULT_ENABLED) == 1;
    }

    private boolean isLlmEnabled() {
        return configService.getIntValue(CFG_LLM_ENABLED, DEFAULT_LLM_ENABLED) == 1 && llmClient.isConfigured();
    }

    private int maxCandidates() {
        int configured = configService.getIntValue(CFG_MAX_CANDIDATES, DEFAULT_MAX_CANDIDATES);
        return configured > 0 ? configured : DEFAULT_MAX_CANDIDATES;
    }
}
