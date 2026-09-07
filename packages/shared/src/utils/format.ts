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
 * 角色枚举对应的语义文案
 */
export const OperatorRoleLabels: Record<string, string> = {
  STUDENT: '报修学生',
  TEACHER: '报修教工',
  REPAIR_WORKER: '维修师傅',
  WORKER: '维修师傅',
  LOGISTICS_ADMIN: '后勤主管',
  SUPER_ADMIN: '系统管理员',
  ADMIN: '后勤调度',
  SYSTEM: '系统调度器'
};
