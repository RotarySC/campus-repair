package com.campus.repair.modules.assistant.service;

import com.campus.repair.modules.assistant.vo.AssistantFaqItemVO;
import com.campus.repair.modules.spatial.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 制度问答的答案来源。
 *
 * <p>所有时效/阈值都**从系统参数取值再插值**，不硬编码：`sys_config` 一改，
 * 助手口径与定时任务、大屏、调度中心立刻一致。此前系统里已经出现过"同一口径在两个地方
 * 各写一份"的缺陷（审计词表），这里刻意只留一个来源。
 */
@Service
@RequiredArgsConstructor
public class AssistantFaqProvider {

    /** 与 `data-demo.sql` 的种子、以及 AGENTS.md §3 的状态机约定保持一致 */
    private static final int DEFAULT_AUTO_ACCEPTANCE_HOURS = 72;
    private static final int DEFAULT_REWORK_RATING_THRESHOLD = 2;
    private static final int DEFAULT_SLA_WARNING_AHEAD_HOURS = 24;

    private final SysConfigService configService;

    @Value("${app.file.max-size-mb:10}")
    private int maxUploadSizeMb;

    public List<AssistantFaqItemVO> listFaq() {
        int autoAcceptHours = configService.getIntValue(
                "auto_acceptance_timeout_hours", DEFAULT_AUTO_ACCEPTANCE_HOURS);
        int reworkThreshold = configService.getIntValue(
                "rework_rating_threshold", DEFAULT_REWORK_RATING_THRESHOLD);
        int slaWarningHours = configService.getIntValue(
                "sla_warning_ahead_hours", DEFAULT_SLA_WARNING_AHEAD_HOURS);

        return List.of(
                new AssistantFaqItemVO("报修后多久会有人上门？",
                        "上门时限由故障细类决定（8~48 小时不等），提交前助手的候选卡片上会直接标出该细类的承诺时效。"
                                + "系统会在 SLA 截止前 " + slaWarningHours + " 小时开始预警催办；"
                                + "加急单会优先调度。"),
                new AssistantFaqItemVO("我可以指定师傅上门的时间吗？",
                        "提单时可以选择期望时段：上午 08:30-11:30、下午 14:00-17:30、晚间 18:30-21:00、周末全天。"
                                + "师傅接单后会按您留的联系电话与您协商具体时间。"),
                new AssistantFaqItemVO("什么情况应该点「加急」？",
                        "漏水（尤其可能浸水到楼下）、跳闸断电、冒烟或有焦糊味、大范围停水停网这类"
                                + "影响安全或整片区域使用的情况。加急单会走更短的 SLA 并被优先调度，请如实选择。"),
                new AssistantFaqItemVO("为什么提示「同一位置已存在未结单同类故障单」？",
                        "这是防重单校验：同一空间节点 + 同一故障细类若已有未结单的工单，"
                                + "系统会提示您补充到原单而不是重复提交——重复提单会让两位师傅跑同一趟。"),
                new AssistantFaqItemVO("提交后我一直不验收会怎样？",
                        "师傅完工后 " + autoAcceptHours + " 小时内若未验收，系统会自动核销结单。"
                                + "自动核销**不会**替您打好评——评分保持「未评价」，不会被计入满意度。"),
                new AssistantFaqItemVO("评价打几分会被要求返工？",
                        "评分 ≤ " + reworkThreshold + " 星会强制触发差评返工督办，由调度重新指派师傅处理；"
                                + "如果只是小问题，也可以先联系师傅或调度中心协商。"),
                new AssistantFaqItemVO("我在哪里查看我的工单？",
                        "左侧「我的工单」可以看全部单据与进度；直接问助手「我的工单进度」也能拿到最近一张单的"
                                + "当前状态和下一步该做什么。"),
                new AssistantFaqItemVO("照片传不上去怎么办？",
                        "支持 JPG/PNG，最多 3 张，单张不超过 " + maxUploadSizeMb + " MB；"
                                + "上传前客户端会自动压缩，弱网下请稍候重试。传不上去也不影响先提交文字描述。"));
    }
}
