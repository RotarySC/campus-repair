package com.campus.repair.modules.assistant.service;

import com.campus.repair.common.constant.IdentityCodes;
import com.campus.repair.modules.assistant.vo.AssistantFaqItemVO;
import com.campus.repair.modules.spatial.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 制度问答的答案来源。
 *
 * <p><b>按职能分档（2026-09-26 修）</b>：同一个问题对不同职能的答案往往不同，甚至完全不适用——
 * 「报修后多久会有人上门」对报修人是承诺时效，对维修师傅则毫无意义（要上门的正是他自己），
 * 对调度/平台管理也不该混在报修人视角里。因此这里<b>不返回一份统一清单</b>，而是按调用者持有的
 * <b>职能取并集</b>：多职能账号（如 {@code WORKER + REPORTER}）看到两套的合并，同一问题去重。
 *
 * <p><b>分组与顺序固定</b>：通用 → 报修人 → 维修作业 → 调度/平台管理。顺序确定才能被测试与探针断言。
 * 多职能<b>取并集而不是二选一</b>，与仓库既有口径一致（一个账号可以既是师傅又是报修人）。
 *
 * <p><b>与 {@code @PreAuthorize} 无关</b>：本类只裁剪<b>内容</b>。访问控制仍是「登录即可」——
 * 非报修职能打开抽屉同样能问到与自己有关的制度，只是问不到报修人专属的那几条。
 *
 * <p>所有时效/阈值都<b>从系统参数取值再插值</b>，不硬编码：{@code sys_config} 一改，
 * 助手口径与定时任务、大屏、调度中心立刻一致。此前系统里已经出现过"同一口径在两个地方
 * 各写一份"的缺陷（审计词表），这里刻意只留一个来源。
 */
@Service
@RequiredArgsConstructor
public class AssistantFaqProvider {

    /** 与 `data-demo.sql` 的种子、以及 `OrderStatusEnum` 的状态机约定保持一致 */
    private static final int DEFAULT_AUTO_ACCEPTANCE_HOURS = 72;
    private static final int DEFAULT_REWORK_RATING_THRESHOLD = 2;
    private static final int DEFAULT_SLA_WARNING_AHEAD_HOURS = 24;

    private static final String KEY_AUTO_ACCEPTANCE_TIMEOUT_HOURS = "auto_acceptance_timeout_hours";
    private static final String KEY_REWORK_RATING_THRESHOLD = "rework_rating_threshold";
    private static final String KEY_SLA_WARNING_AHEAD_HOURS = "sla_warning_ahead_hours";

    private final SysConfigService configService;

    @Value("${app.file.max-size-mb:10}")
    private int maxUploadSizeMb;

    /**
     * 按职能取并集，返回该调用者应看到的制度问答。
     *
     * @param functions 调用者持有的职能码（{@code LoginUserPrincipal.getRoles()}）；为空时只返回通用组
     */
    public List<AssistantFaqItemVO> listFaq(List<String> functions) {
        int autoAcceptHours = configService.getIntValue(
                KEY_AUTO_ACCEPTANCE_TIMEOUT_HOURS, DEFAULT_AUTO_ACCEPTANCE_HOURS);
        int reworkThreshold = configService.getIntValue(
                KEY_REWORK_RATING_THRESHOLD, DEFAULT_REWORK_RATING_THRESHOLD);
        int slaWarningHours = configService.getIntValue(
                KEY_SLA_WARNING_AHEAD_HOURS, DEFAULT_SLA_WARNING_AHEAD_HOURS);

        // LinkedHashMap 按问题去重并保序：多职能账号两套都命中时不会重复出现同一条
        Map<String, String> merged = new LinkedHashMap<>();
        commonFaq().forEach(item -> merged.put(item.getQuestion(), item.getAnswer()));
        if (hasFunction(functions, IdentityCodes.FUNC_REPORTER)) {
            reporterFaq(slaWarningHours, autoAcceptHours, reworkThreshold)
                    .forEach(item -> merged.put(item.getQuestion(), item.getAnswer()));
        }
        if (hasFunction(functions, IdentityCodes.FUNC_WORKER)) {
            workerFaq(slaWarningHours, reworkThreshold)
                    .forEach(item -> merged.put(item.getQuestion(), item.getAnswer()));
        }
        if (hasFunction(functions, IdentityCodes.FUNC_DISPATCHER)
                || hasFunction(functions, IdentityCodes.FUNC_PLATFORM_ADMIN)) {
            dispatchSideFaq(slaWarningHours, autoAcceptHours, reworkThreshold)
                    .forEach(item -> merged.put(item.getQuestion(), item.getAnswer()));
        }
        return merged.entrySet().stream()
                .map(entry -> new AssistantFaqItemVO(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static boolean hasFunction(List<String> functions, String code) {
        return functions != null && functions.contains(code);
    }

    /** 与职能无关的两条：助手自身边界、账号安全。所有人都能看到。 */
    private List<AssistantFaqItemVO> commonFaq() {
        return List.of(
                new AssistantFaqItemVO("助手给的判断能直接提单吗？",
                        "不能。助手只出建议：它不会替您提单，也不会改写您已经填好的表单。"
                                + "要点「采用并提单」才会把选中的故障细类带到提单页，其余字段仍需您自己确认。"
                                + "如果对判断结果没把握，请手动选择故障细类。"),
                new AssistantFaqItemVO("为什么登录后被要求先改密码？",
                        "首次登录（或管理员重置过密码）的账号必须先修改密码才能使用其它功能。"
                                + "改密成功后系统会重新签发登录凭证，不需要再登录一次。"
                                + "新密码要求 8~32 位，且同时包含字母与数字。"));
    }

    /** 报修人视角：从提交、上门、验收一路到评价。 */
    private List<AssistantFaqItemVO> reporterFaq(int slaWarningHours, int autoAcceptHours, int reworkThreshold) {
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
                                + "自动核销不会替您打好评——评分保持「未评价」，不会被计入满意度。"),
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

    /** 维修作业视角：接单、挂起、转派、完工与耗材核销。 */
    private List<AssistantFaqItemVO> workerFaq(int slaWarningHours, int reworkThreshold) {
        return List.of(
                new AssistantFaqItemVO("收到派单后我该做什么？",
                        "在「我的工作台」的【待承接】里找到工单并点「接单出发」，工单随即进入【维修中】；"
                                + "现场作业完成后提交完工。接单与完工都会写入审计记录，报修人也会收到站内信通知。"),
                new AssistantFaqItemVO("现场暂时处理不了怎么办？",
                        "在工作台点「挂起」并写明原因（如缺件、需协调停电）。挂起期间承诺时效（SLA）冻结，"
                                + "不会计入您的超时；问题解决后点「解除挂起」恢复计时，承诺时效相应顺延。"),
                new AssistantFaqItemVO("申请转派之后这单归谁？",
                        "申请转派后工单会从您名下摘除（师傅字段清空并标记为转派），回到调度池由调度重新指派。"
                                + "转派成功后您不再对该单负责，占用的在修负荷也随之释放。"),
                new AssistantFaqItemVO("完工要提交哪些内容？",
                        "需要填写维修措施与故障根因，并上传 1~3 张完工照片作为现场处理佐证。"
                                + "提交后工单转为「待验收」，等待报修人验收或超时自动核销。"),
                new AssistantFaqItemVO("用了备件怎么记账？",
                        "在完工填报时一并核销所用耗材与数量，系统按物料台账的指导单价扣减库存并留档。"
                                + "若库存不足会被拦下并给出提示，请先联系库管入库，或按实际用量调整数量。"),
                new AssistantFaqItemVO("我同时能接多少单？",
                        "上限写在您师傅档案的「最大在修数」里。达到上限后自动派单不会再派给您（这是防超派的硬约束）。"
                                + "完工、作废或转派都会释放负荷，之后即可继续接单。"),
                new AssistantFaqItemVO("什么情况会触发差评返工？",
                        "报修人评分 ≤ " + reworkThreshold + " 星会强制返工督办，工单转回返工流程由调度重新指派——"
                                + "不一定回到您名下。返工与相关操作都会记入工单的审计时间轴，并计入作业统计。"),
                new AssistantFaqItemVO("快超时了会不会有人提醒我？",
                        "会。承诺时效截止前 " + slaWarningHours + " 小时开始预警催办，站内信会通知您，调度侧同时可见。"
                                + "挂起中的工单不计入计时，解除挂起后才继续。"));
    }

    /** 调度 / 平台管理视角：派单失败、指派改派、作废、SLA 与系统参数。 */
    private List<AssistantFaqItemVO> dispatchSideFaq(int slaWarningHours, int autoAcceptHours, int reworkThreshold) {
        return List.of(
                new AssistantFaqItemVO("自动派单什么时候会失败？",
                        "三种情况会降级为待人工调度：① 该故障细类对应的工种没有在岗师傅；② 候选师傅全部达到最大在修数；"
                                + "③ 该区域没有匹配师傅。降级单会落进调度池的「派单失败」筛选，人工指派即可。"),
                new AssistantFaqItemVO("人工指派和改派有什么区别？",
                        "「指派」用于还没有师傅的工单（待派单或自动派单失败）；「改派」用于把已指派／维修中的工单换给另一名师傅。"
                                + "改派会释放原师傅的负荷、为新师傅占用负荷，并留下审计记录。"),
                new AssistantFaqItemVO("作废工单要注意什么？",
                        "作废不可逆：必须填写作废原因（进审计时间轴），工单转为「已作废」并立即释放师傅负荷。"
                                + "请先确认确属误报或重复单，且原单确实无法继续处理。"),
                new AssistantFaqItemVO("师傅申请转派了怎么办？",
                        "申请转派的工单，师傅字段已被清空并标记为转派，会出现在调度池的「转派申请」筛选里。"
                                + "重新指派一名师傅即可完成转派；等待期间该单不计在原师傅的负荷上。"),
                new AssistantFaqItemVO("承诺时效（SLA）怎么算、在哪里看？",
                        "每张单按故障细类的 SLA 小时数从提交起计时，截止前 " + slaWarningHours
                                + " 小时开始预警催办；挂起期间冻结、解除后顺延。超时单可在「工单检索报表」按状态与时间筛出来。"),
                new AssistantFaqItemVO("超时自动核销的规则是什么？",
                        "师傅完工后工单转「待验收」，若报修人 " + autoAcceptHours
                                + " 小时内既未验收也未提返工，定时任务会自动核销结单。"
                                + "核销不写评分——该单在满意度口径里按「未评价」过滤，不会虚增好评。"),
                new AssistantFaqItemVO("差评返工与仲裁怎么走？",
                        "报修人评分 ≤ " + reworkThreshold + " 星即触发返工督办，工单转回返工流程并回到调度池重新指派。"
                                + "返工与仲裁动作都会写入工单审计时间轴（操作人以「调度」职能记录），便于追溯。"),
                new AssistantFaqItemVO("系统参数改了会影响哪里？",
                        "改动即刻生效、无需重启：超时核销小时数被定时核销任务读取，返工评分阈值被评价返工判定读取，"
                                + "SLA 预警小时数被预警任务读取；故障细类的助手同义词与自助排查提示则被智能助手的规则引擎读取。"));
    }
}
