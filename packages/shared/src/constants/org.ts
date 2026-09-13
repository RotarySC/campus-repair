/**
 * 单位组织字典（`/org/tree`）的**身份范围**。
 *
 * 字典里的顶层分支各自声明归属：年级分支面向学生（`STUDENT`）、教职工分支面向教工与师傅（`STAFF`）。
 * 取值与后端 `OrgNodeService.AUDIENCE_STUDENT / AUDIENCE_STAFF` 一一对应。
 */
export type OrgAudience = 'STUDENT' | 'STAFF';

/**
 * 身份 → 该身份可选的组织分支。
 *
 * 映射刻意只放一处（而不是让各页面各写一份），新增端或调整口径时改这里即可：
 * - 学生 → 年级分支；
 * - 教工 / 维修师傅 → 教职工分支；
 * - 管理员（超管 / 后勤）**没有**「学院/部门」这个词条 → 返回 `null`，个人中心据此不渲染该表单项。
 */
export function orgAudienceOf(userType?: string): OrgAudience | null {
  switch (userType) {
    case 'USER_STUDENT':
      return 'STUDENT';
    case 'USER_TEACHER':
    case 'REPAIR_WORKER':
      return 'STAFF';
    default:
      return null;
  }
}
