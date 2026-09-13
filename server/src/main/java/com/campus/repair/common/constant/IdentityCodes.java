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
}
