package com.campus.repair.modules.workorder.service;

import com.campus.repair.framework.util.SensitiveMasker;
import com.campus.repair.modules.spatial.service.SysConfigService;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.vo.DispatchOrderItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 联系方式按「查看者与工单的关系」计算展示值的统一策略。
 *
 * <p>放行集合本来就比开题更窄（只有报修人本人 / 当前指派师傅 / 调度管理能读整单），
 * 所以字段级脱敏真正有意义的只有三类场景：
 * <ol>
 *   <li><b>报修人看自己的单</b>：自己的号码完整；**师傅号码默认掩码**
 *       （`138****0002`），管理员把 `contact.reporter_see_worker_phone` 置 1 才放开；</li>
 *   <li><b>师傅看指派给自己的单</b>：报修人号码**完整**——不完整就没法联系上门；</li>
 *   <li><b>调度 / 平台管理看任意单</b>：两者都完整。</li>
 * </ol>
 *
 * <p>实现方式刻意是「**加展示值字段，不改原字段**」：`applicantPhone` / `workerPhone`
 * 保留原样，新字段 `applicantPhoneDisplay` / `workerPhoneDisplay` 承载按关系裁剪后的值。
 * 旧前端读旧字段不会崩，新前端统一读新字段；掩码判定与是否生成 `tel:` 链接由
 * {@link SensitiveMasker#isMasked(String)} 同一判据决定，避免两端各自猜。
 *
 * <p>「住址」不在此列：本系统的住址是 `spatial_node.full_path_name`（校园公共空间），
 * 不是个人隐私地址，对处理人完整展示。
 */
@Service
@RequiredArgsConstructor
public class ContactVisibilityPolicy {

    /** 配置键：报修人是否可见师傅完整手机号（0=掩码，默认；1=完整） */
    public static final String CONFIG_REPORTER_SEE_WORKER_PHONE = "contact.reporter_see_worker_phone";

    private final SysConfigService sysConfigService;

    /** 查看者与工单的关系（与前端 `viewerRelation` 字段同词） */
    public enum ViewerRelation {
        /** 报修人本人 */
        OWNER,
        /** 当前指派师傅 */
        ASSIGNEE,
        /** 调度 / 平台管理 */
        DISPATCH
    }

    /**
     * 解析查看者关系。
     *
     * @param dispatchSide 调用方是否持有调度/平台管理职能（控制器已按角色收口）
     * @throws org.springframework.security.access.AccessDeniedException 既非属主也非指派师傅
     *         且不是管理侧——正常路径上 {@code assertReadable} 已先拦一次，这里是兜底，
     *         避免「策略被新调用点漏用」时反而泄露完整号码
     */
    public ViewerRelation resolve(RepairOrder order, Long viewerUserId, boolean dispatchSide) {
        if (dispatchSide) {
            return ViewerRelation.DISPATCH;
        }
        if (viewerUserId != null && viewerUserId.equals(order.getStudentUserId())) {
            return ViewerRelation.OWNER;
        }
        if (viewerUserId != null && viewerUserId.equals(order.getWorkerId())) {
            return ViewerRelation.ASSIGNEE;
        }
        throw new org.springframework.security.access.AccessDeniedException(
                "越权拦截：仅报修人本人、当前指派师傅与调度管理可查看该工单的联系方式");
    }

    /** 报修人号码展示值：三种关系都需要完整号码（本人自己的 / 上门必需 / 管理侧全量） */
    public String applicantPhoneDisplay(RepairOrder order, ViewerRelation relation) {
        return order.getApplicantPhone();
    }

    /** 师傅号码展示值：报修人默认掩码，指派师傅与调度管理完整 */
    public String workerPhoneDisplay(RepairOrder order, ViewerRelation relation) {
        if (relation == ViewerRelation.OWNER) {
            return reporterCanSeeWorkerPhone()
                    ? order.getWorkerPhone()
                    : SensitiveMasker.maskPhone(order.getWorkerPhone());
        }
        return order.getWorkerPhone();
    }

    /**
     * 配置开关是否放开（每次实时读库，不缓存）。
     *
     * <p>不缓存是刻意的：管理员在「系统参数」里改完开关，下一次请求就应生效，
     * 否则答辩现场会出现「改了配置但页面还是掩码」的解释成本。读库一次的开销
     * 远小于一次工单列表查询，值得。
     */
    public boolean reporterCanSeeWorkerPhone() {
        try {
            return sysConfigService.getIntValue(CONFIG_REPORTER_SEE_WORKER_PHONE, 0) == 1;
        } catch (Exception e) {
            // 配置表异常时回退到最保守口径（掩码），绝不因为读配置失败而放开隐私字段
            return false;
        }
    }

    /**
     * 把关系与展示值写进工单列表/详情 VO，并顺带算出 {@code canCancel}。
     *
     * <p>`canCancel` 只对报修人本人有意义（调度侧有作废端点、师傅没有取消权），
     * 且必须复用 {@link ReporterCancelPolicy} 的判定——前端不许自己复制那套规则。
     */
    public void apply(DispatchOrderItemVO vo, RepairOrder order, ViewerRelation relation) {
        vo.setViewerRelation(relation.name());
        vo.setApplicantPhoneDisplay(applicantPhoneDisplay(order, relation));
        vo.setWorkerPhoneDisplay(workerPhoneDisplay(order, relation));
        vo.setCanCancel(relation == ViewerRelation.OWNER && ReporterCancelPolicy.canCancel(order));
    }
}
