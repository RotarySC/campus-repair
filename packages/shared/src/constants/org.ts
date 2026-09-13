/**
 * 组织条线（口径见仓库根 `CONTEXT.md`）。
 *
 * **一个值、三处共用**：它同时是 `sys_user.user_type` 的取值、`org_node.audience` 的取值、
 * 以及单位字典的剪枝键——**不做任何二次映射**（前端直接把用户的 `userType` 当 `audience` 传）。
 */
export type OrgBranch = 'STUDENT' | 'FACULTY' | 'LOGISTICS' | 'ADMIN';

/**
 * 开放自助注册的条线，与后端 `IdentityCodes.SELF_REGISTRABLE_BRANCHES` 一致：
 * 行政信息化（ADMIN）不开放自助注册，维修作业/调度/平台管理账号一律由管理员建档。
 */
export const SELF_REGISTRABLE_BRANCHES: OrgBranch[] = ['STUDENT', 'FACULTY', 'LOGISTICS'];

/**
 * 各条线在「所属单位」选择器里的占位文案。
 * （行政信息化条线不渲染该词条，故不在此表内。）
 */
export const BranchOrgPlaceholders: Record<string, string> = {
  STUDENT: '选择年级 / 学院',
  FACULTY: '选择所属学院 / 系',
  LOGISTICS: '选择所属处室 / 班组'
};
