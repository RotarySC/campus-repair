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
 * 时间展示的**唯一来源**（2026-09-27 抽取；此前全仓有 4 份各自实现 + 3 处裸渲染）。
 *
 * ## 输入约定：后端发的是**无时区**的 `LocalDateTime`
 * 后端所有时间字段都是 `LocalDateTime`（如 `RepairOrderLog.createdAt`），Jackson 序列化成
 * `2026-09-21T16:42:30.775846`——**没有时区标记**，字面值就是业务时间本身。
 *
 * ⚠️ **所以这里刻意用字符串切片，不要"顺手"改成 `new Date(...)` + `toLocaleString()`**：
 * 后者会把无时区的字面值按**浏览器所在时区**重新解释，于是同一份数据在不同机器/不同时区设置下
 * 显示成不同的小时数。切片是"原样呈现后端的语义"，**这是有意的，不是偷懒**。
 * `test/format.spec.ts` 里有用例锁死这条（含"不得随宿主时区漂移"）。
 *
 * ## 空值占位符
 * 全仓统一用 `'—'`（em dash）。历史上 `WorkbenchView` / `DispatchCenterView` 用 `'--'`，
 * 2026-09-27 一并归一——**空值文案也是一种投影，两套写法就是下一个"同一个状态两种颜色"**。
 */

/** 空值占位符（全仓唯一；需要别的写法请走参数，不要再各写一份） */
export const EMPTY_PLACEHOLDER = '—';

/** 非空取文本；`null`/`undefined`/空串/纯空白一律视为空 */
function toText(value?: string | null): string {
  if (value === null || value === undefined) return '';
  return String(value).trim();
}

/**
 * `2026-09-21T16:42:30.775846` → `2026-09-21 16:42`
 *
 * 只有日期（`2026-09-21`）时原样返回——切片天然降级，不会造出假时间。
 */
export function formatDateTime(value?: string | null, placeholder: string = EMPTY_PLACEHOLDER): string {
  const text = toText(value);
  return text ? text.replace('T', ' ').slice(0, 16) : placeholder;
}

/** `2026-09-21T16:42:30.775846` → `2026-09-21` */
export function formatDate(value?: string | null, placeholder: string = EMPTY_PLACEHOLDER): string {
  const text = toText(value);
  return text ? text.slice(0, 10) : placeholder;
}

/**
 * **本地时区**的"今天"，`YYYY-MM-DD`。
 *
 * ⚠️ 不要用 `new Date().toISOString().slice(0, 10)`——`toISOString()` 是 **UTC**，
 * 在 UTC+8 的 00:00~08:00 之间会给出**昨天**（入库单的「批次日期」默认值曾这样差一天）。
 */
export function todayIsoDate(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
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