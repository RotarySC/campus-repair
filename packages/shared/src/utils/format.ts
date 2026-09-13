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
 * 这一列是**历史词表与现行条线码混装**的：改名前的行存旧角色码（SUPER_ADMIN/LOGISTICS_ADMIN/
 * REPAIR_WORKER/USER_STUDENT…），改名后运行时写的是操作人的**组织条线**（见 WorkOrderService）。
 * 因此两张码表的值都必须在这里有文案，否则时间轴会直接显示裸码（`OperatorRoleLabels[role] || role`）。
 */
export const OperatorRoleLabels: Record<string, string> = {
  STUDENT: '报修学生',
  TEACHER: '报修教工',
  REPAIR_WORKER: '维修师傅',
  WORKER: '维修师傅',
  LOGISTICS_ADMIN: '后勤主管',
  SUPER_ADMIN: '系统管理员',
  ADMIN: '行政信息化',
  LOGISTICS: '后勤保障',
  FACULTY: '教学科研',
  SYSTEM: '系统调度器'
};
