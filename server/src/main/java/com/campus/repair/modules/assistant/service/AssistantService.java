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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** 升级阈值：规则最高分低于它才把判断交给大模型（见 AssistantRuleEngine.shouldEscalate） */
    static final String CFG_LLM_ESCALATE_BELOW = "assistant_llm_escalate_below";

    private static final int DEFAULT_ENABLED = 1;
    private static final int DEFAULT_LLM_ENABLED = 0;
    private static final int DEFAULT_MAX_CANDIDATES = 3;
    /** 默认 10 = 非 HIGH 就升级；设 0 即"每次都调模型"（升级制之前的行为） */
    private static final int DEFAULT_LLM_ESCALATE_BELOW = 10;

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
     * <p>流程：本地规则先算 → **规则没把握时**才升级给大模型**填空位**（追加规则漏掉的细类；
     * 候选顺序仍由规则口径决定，模型返回顺序不参与）→ 组装 VO。
     * 关掉总开关时返回空结果（前端本来就不该调，这里只是防御）。
     *
     * <p>"有没有把握"由 {@code sys_config.assistant_llm_escalate_below} 决定（默认 10 = 非 HIGH 就升级）：
     * 规则已经给出 HIGH 命中时**一次模型调用都不发**——那条路径瞬时、免费、且完全确定。
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
        // 升级制：规则有把握（最高分 ≥ 阈值）时**根本不调模型**。
        // 注意"没调模型"不是降级——degradedReason 保持 null、engine 保持 RULE，语义正确。
        if (isLlmEnabled() && AssistantRuleEngine.shouldEscalate(ranked, escalateBelow())) {
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
     * <p><b>三条不变式</b>（2026-09-26 修订；此前是"模型序在前、规则命中补足"）：
     * <ol>
     *   <li><b>规则命中一条都不能少</b>——大模型只能**填空位**。此前实现把模型挑的排前面
     *       再截断，模型返回满 {@code limit} 条时规则命中会被整批截掉，等于"开了大模型反而更差"。</li>
     *   <li><b>顺序完全由规则口径决定</b>——合并后一律走 {@link AssistantRuleEngine#sort}
     *       （分数降序 → {@code sortOrder} → {@code id}），**模型返回的先后顺序不参与排序**。
     *       这是"排序不确定"的根治：思考模型的 {@code temperature} 被服务端强制成 1.0
     *       （设计文档 §8），让它的顺序当主键就等于把随机性直接引进 UI 首候选。</li>
     *   <li><b>模型仍能追加</b>——规则没命中的叶子由 {@link #promote} 补进候选，
     *       占用规则命中之外的空位。这正是升级路径的全部增益所在。</li>
     * </ol>
     */
    private List<Scored> merge(List<Long> llmIds,
                               List<Scored> ruleRanked,
                               Map<Long, RepairCategoryVO> byId,
                               int limit) {
        // ① 规则命中全数保留（ruleRanked 已按 ORDER 排好、且已截到 limit）
        List<Scored> selected = new ArrayList<>(ruleRanked);
        Set<Long> takenIds = new HashSet<>();
        for (Scored scored : ruleRanked) {
            takenIds.add(scored.leaf().id());
        }

        // ② 模型只能填剩下的空位；规则已命中的 id 无须追加（它已经在列表里）
        for (Long id : llmIds) {
            if (selected.size() >= limit) {
                break;
            }
            if (id == null || takenIds.contains(id)) {
                continue;
            }
            Scored promoted = promote(id, byId);
            if (promoted != null) {
                selected.add(promoted);
                takenIds.add(id);
            }
        }

        // ③ 顺序一律按规则口径重排 —— 模型返回顺序到此为止，不进 UI
        return AssistantRuleEngine.sort(selected, limit);
    }

    /**
     * 把"规则没命中、但模型选了"的叶子补成一条候选。
     *
     * <p>分数给关键词档（+5 = MEDIUM）：它确实是一条有效建议，但不该比用户自己说出的叶子名
     * （+10 = HIGH）更可信。{@code matchedKeywords} 刻意留空——本来就没有任何词被命中，
     * 前端因此不渲染「命中：…」那一行，这就是它与规则命中在界面上唯一的区别。
     */
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

    /**
     * 升级阈值：规则最高分低于它才把判断交给大模型。
     *
     * <p>刻意**不**把非正值夹到默认值上——{@code 0} 有明确语义（每次都调，向后兼容），
     * 夹掉它就等于关不掉升级制了。
     */
    private int escalateBelow() {
        return configService.getIntValue(CFG_LLM_ESCALATE_BELOW, DEFAULT_LLM_ESCALATE_BELOW);
    }
}
