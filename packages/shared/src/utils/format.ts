/**
 * 解析图片 JSON 或逗号分隔字符串为 URL 数组
 */
export function parseImageUrls(images?: string | null): string[] {
  if (!images) return [];
  try {
    const parsed = JSON.parse(images);
    if (Array.isArray(parsed)) return parsed.map(s => String(s).trim()).filter(Boolean);
    return [String(images).trim()];
  } catch (e) {
    if (images.includes('http') || images.includes('/')) {
      return images.split(',').map(s => s.trim()).filter(Boolean);
    }
    return [];
  }
}

/**
 * `repair_order_log.operator_role` 的展示文案。
 *
 * **现行词表（批次 4 起）**：这一列只写**职能码**——`REPORTER` / `WORKER` / `DISPATCHER` /
 * `PLATFORM_ADMIN`，外加系统自动流转的 `SYSTEM`（见后端 `IdentityCodes.ALL_AUDIT_ROLES`）。
 * 语义是「该动作是以哪种职能身份执行的」，与后端 `@PreAuthorize` 一一对应。
 *
 * **历史词表（必须保留）**：改名之前的行存的是旧角色码（`SUPER_ADMIN`/`LOGISTICS_ADMIN`/
 * `REPAIR_WORKER`/`USER_STUDENT`/`USER_TEACHER`）与中间一代的组织条线码
 * （`STUDENT`/`TEACHER`/`FACULTY`/`LOGISTICS`/`ADMIN`）。审计表不可篡改、**不重写历史**，
 * 所以旧键全部留在这里做兼容翻译——删掉任何一个，历史日志就会在时间轴上显示裸码
 * （`OperatorRoleLabels[role] || role`）。
 *
 * 本文件是 `NoLegacyRoleCodeTest` 里唯一允许出现旧码的 shared 文件（另一个是
 * `apps/admin-web/src/components/WorkOrderTimeline.vue` 的配色表），两者都不参与任何身份/权限判定。
 */
export const OperatorRoleLabels: Record<string, string> = {
  // ---- 现行词表：4 个职能码 + 系统 ----
  REPORTER: '报修人',
  WORKER: '维修师傅',
  DISPATCHER: '后勤调度',
  PLATFORM_ADMIN: '平台管理',
  SYSTEM: '系统调度器',
  // ---- 历史词表（只出现在旧数据里，勿删）----
  STUDENT: '报修学生',
  TEACHER: '报修教工',
  REPAIR_WORKER: '维修师傅',
  LOGISTICS_ADMIN: '后勤主管',
  SUPER_ADMIN: '系统管理员',
  ADMIN: '行政信息化',
  LOGISTICS: '后勤保障',
  FACULTY: '教学科研'
};