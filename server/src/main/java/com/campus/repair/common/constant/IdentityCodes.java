package com.campus.repair.common.constant;

import java.util.Set;

/**
 * 身份语汇的唯一常量来源（口径见仓库根 `CONTEXT.md`）。
 *
 * <p>本系统把「一个人是谁」与「一个人能干什么」分成两层：
 * <ul>
 *   <li><b>组织条线</b>（{@code sys_user.user_type}，单值）——同时就是单位字典的
 *       {@code org_node.audience} 取值与剪枝键，<b>同一个值、不做二次映射</b>；</li>
 *   <li><b>职能</b>（{@code sys_role.role_code} / {@code sys_user_role}，多值）——后端
 *       {@code @PreAuthorize} 与前端路由守卫的唯一判据。</li>
 * </ul>
 * 「身份」（学生 / 教职工）是条线的派生视图，不单独存储。
 *
 * <p><b>为什么注解里仍写字面量</b>：Spring 的 {@code @PreAuthorize} 需要编译期常量表达式，
 * 拼接常量会牺牲可读性。因此这里只给服务层代码用，注解侧由
 * {@code NoLegacyRoleCodeTest} 守住"不得再出现旧角色码"。
 */
public final class IdentityCodes {

    private IdentityCodes() {
    }

    // ------------------------------------------------------------------
    // 组织条线（= 单位字典 audience）
    // ------------------------------------------------------------------

    /** 学生（年级 → 学院） */
    public static final String BRANCH_STUDENT = "STUDENT";
    /** 教职工·教学科研 */
    public static final String BRANCH_FACULTY = "FACULTY";
    /** 教职工·后勤保障 */
    public static final String BRANCH_LOGISTICS = "LOGISTICS";
    /** 教职工·行政信息化 */
    public static final String BRANCH_ADMIN = "ADMIN";

    public static final Set<String> ALL_BRANCHES =
            Set.of(BRANCH_STUDENT, BRANCH_FACULTY, BRANCH_LOGISTICS, BRANCH_ADMIN);

    /** 允许自助注册的条线：学生 / 教学科研 / 后勤保障。行政信息化与所有管理职能一律由后台建档。 */
    public static final Set<String> SELF_REGISTRABLE_BRANCHES =
            Set.of(BRANCH_STUDENT, BRANCH_FACULTY, BRANCH_LOGISTICS);

    // ------------------------------------------------------------------
    // 职能
    // ------------------------------------------------------------------

    /** 报修人（师生自助注册的默认职能） */
    public static final String FUNC_REPORTER = "REPORTER";
    /** 维修作业（持有者必须有师傅档案 `worker_profile`） */
    public static final String FUNC_WORKER = "WORKER";
    /** 后勤调度 */
    public static final String FUNC_DISPATCHER = "DISPATCHER";
    /** 平台管理 */
    public static final String FUNC_PLATFORM_ADMIN = "PLATFORM_ADMIN";

    public static final Set<String> ALL_FUNCTIONS =
            Set.of(FUNC_REPORTER, FUNC_WORKER, FUNC_DISPATCHER, FUNC_PLATFORM_ADMIN);

    // ------------------------------------------------------------------
    // 审计词表（repair_order_log.operator_role）
    // ------------------------------------------------------------------

    /**
     * 审计日志里「系统自动流转」的操作者码（**不是一个职能**，没有任何人持有它）。
     *
     * <p>自动派单引擎、72 小时超时自动核销这类动作由系统写入，操作者 id 为 0。
     * 它刻意不叫 {@code ADMIN}：后者现在是「行政信息化」条线码，写成它会让自动流转读起来
     * 像某个具体的人做的。
     */
    public static final String AUDIT_SYSTEM = "SYSTEM";

    /**
     * 允许出现在 {@code repair_order_log.operator_role} 里的**全部**取值。
     *
     * <p>语义：这一列记录的是**该动作是以哪种职能身份执行的**（而不是操作人持有的全部职能、
     * 也不是他的组织条线）——所以它与 `@PreAuthorize` 一一对应：提单/评价写 {@code REPORTER}，
     * 接单/挂起/转派/完工写 {@code WORKER}，人工派单/改派/作废/仲裁写 {@code DISPATCHER}，
     * 系统自动流转写 {@code SYSTEM}。操作人本人由 {@code operator_id}/{@code operator_name} 标识。
     *
     * <p>历史数据不受此约束（改名前后是两套词表），展示层按
     * {@code packages/shared/src/utils/format.ts} 的兼容映射翻译。
     */
    public static final Set<String> ALL_AUDIT_ROLES = Set.of(
            FUNC_REPORTER, FUNC_WORKER, FUNC_DISPATCHER, FUNC_PLATFORM_ADMIN, AUDIT_SYSTEM);
}
