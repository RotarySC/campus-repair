export enum OrderStatusEnum {
  PENDING_DISPATCH = 'PENDING_DISPATCH',
  MANUAL_DISPATCH = 'MANUAL_DISPATCH',
  ASSIGNED = 'ASSIGNED',
  IN_PROGRESS = 'IN_PROGRESS',
  SUSPENDED = 'SUSPENDED',
  PENDING_ACCEPTANCE = 'PENDING_ACCEPTANCE',
  REWORK_PENDING = 'REWORK_PENDING',
  COMPLETED = 'COMPLETED',
  CANCELLED = 'CANCELLED'
}

export const OrderStatusLabels: Record<OrderStatusEnum, string> = {
  [OrderStatusEnum.PENDING_DISPATCH]: '待派单',
  [OrderStatusEnum.MANUAL_DISPATCH]: '待人工调度',
  [OrderStatusEnum.ASSIGNED]: '待承接',
  [OrderStatusEnum.IN_PROGRESS]: '维修中',
  [OrderStatusEnum.SUSPENDED]: '已挂起',
  [OrderStatusEnum.PENDING_ACCEPTANCE]: '待验收',
  [OrderStatusEnum.REWORK_PENDING]: '返工待派',
  [OrderStatusEnum.COMPLETED]: '已结单',
  [OrderStatusEnum.CANCELLED]: '已作废'
};

/** el-tag 的语义色档；与 `ResponsiveTable` 的 `CardTagType` 是同一集合 */
export type OrderStatusTagType = 'success' | 'primary' | 'danger' | 'warning' | 'info';

/**
 * 状态色相令牌名。取值与 `apps/admin-web/src/styles/tokens.css` 的 `--h1`~`--h8` 一一对应。
 *
 * 编号即 `docs/20261012-1330-…-visual-language-d-design.md` §3.2 那张两列表的**列优先**读序
 * （左列 h1~h4、右列 h5~h8），也就是工单生命周期顺序。
 */
export type StatusHueToken = '--h1' | '--h2' | '--h3' | '--h4' | '--h5' | '--h6' | '--h7' | '--h8';

/**
 * 色相令牌 → 色值。**与 `apps/admin-web/src/styles/tokens.css` 是同一份色值的两处声明**
 * （CSS 无法 import TS）。冗余是刻意的，一致性由回归断言保证：
 * `test/status-hue.spec.ts` 会解析 tokens.css 并逐值比对，任一处的色值被改动都会变红。
 *
 * 为什么色相不降饱和：色相的面积只剩圆环那 2px 描边（约 100px²），
 * **饱和度是那点色相唯一还能被认出来的东西**（规范 §3.2）。
 */
export const STATUS_HUE: Record<StatusHueToken, string> = {
  '--h1': '#FA8C16', // 待派单
  '--h2': '#13C2C2', // 待承接
  '--h3': '#1677FF', // 维修中
  '--h4': '#6B7C93', // 已挂起（由 #FAAD14 改：停滞语义配冷色，琥珀只剩「待派单」一个主人）
  '--h5': '#722ED1', // 待验收
  '--h6': '#F5222D', // 返工待派
  '--h7': '#52C41A', // 已结单
  '--h8': '#B3B3B3' // 已作废（由 #8C8C8C 改：作废即褪色）
};

/** 未知 / 空状态的圆环色相令牌：中性弱化，不占用 8 个语义色相中的任何一个 */
export const STATUS_HUE_FALLBACK: StatusHueToken = '--h8';

/**
 * 8 个色相键的**有序**清单（顺序 = §3.2 那张表的列优先读序，也就是工单生命周期顺序）。
 * 给「必须复核全部 28 对」的可辨识性核算用——它需要一份**确定的**键序，而不是 `Object.keys` 的顺序。
 */
export const ORDER_STATUS_HUE_KEYS: readonly StatusHueToken[] = [
  '--h1',
  '--h2',
  '--h3',
  '--h4',
  '--h5',
  '--h6',
  '--h7',
  '--h8'
];

/**
 * 工单状态的两份视觉投影，**一处来源**（规范 §5 的 V9 修订：`ORDER_STATUS_VISUAL` 是唯一来源）。
 *
 * - `hue`：9px 圆环的 2px 描边色相（`StatusRing`，规范 §3.2/§3.3）
 * - `tag`：`el-tag` 的语义档（**仍保留**给非状态场景与尚未迁移的页面，规范 §5「`el-tag` 仅保留给非状态类标签」）
 *
 * 新增一个工单状态 = 在这里加**一条**，两个投影同时到位。
 */
interface StatusVisual {
  hue: StatusHueToken;
  tag: OrderStatusTagType;
}

const ORDER_STATUS_VISUAL: Record<string, StatusVisual> = {
  [OrderStatusEnum.PENDING_DISPATCH]: { hue: '--h1', tag: 'info' },
  // 「待人工调度」是「自动派单未成功、等人工介入」，与「待派单」同属**尚未指派**，
  // 因此共用琥珀 `--h1` 而不是新增第 9 个色相——§3.2 锁定的就是 8 个，
  // 而色相可辨识性核算断言的是 8 个色相的 28 对最小 ΔE（规范 §7.2）。
  [OrderStatusEnum.MANUAL_DISPATCH]: { hue: '--h1', tag: 'info' },
  [OrderStatusEnum.ASSIGNED]: { hue: '--h2', tag: 'warning' },
  [OrderStatusEnum.IN_PROGRESS]: { hue: '--h3', tag: 'warning' },
  [OrderStatusEnum.SUSPENDED]: { hue: '--h4', tag: 'info' },
  [OrderStatusEnum.PENDING_ACCEPTANCE]: { hue: '--h5', tag: 'primary' },
  [OrderStatusEnum.REWORK_PENDING]: { hue: '--h6', tag: 'danger' },
  [OrderStatusEnum.COMPLETED]: { hue: '--h7', tag: 'success' },
  [OrderStatusEnum.CANCELLED]: { hue: '--h8', tag: 'info' }
};

const DEFAULT_VISUAL: StatusVisual = { hue: STATUS_HUE_FALLBACK, tag: 'info' };

function visualOf(status?: string | null): StatusVisual {
  return (status && ORDER_STATUS_VISUAL[status]) || DEFAULT_VISUAL;
}

/**
 * 工单状态 → `el-tag` 语义色。**全仓唯一一份**。
 *
 * 此前 `StudentOrderListView.vue` 与 `PersonalCenterView.vue` 各抄了一遍逐字节相同的实现，
 * 师傅工作台要用第三份时才发现——那正是「同一个状态在不同页显示成不同颜色」的起点。
 * 新增任何展示工单状态的页面，一律 import 这里，不要再写本地副本。
 *
 * ⚠️ 新增工单状态时**不要**在这里加 `case`——改 `ORDER_STATUS_VISUAL` 那一条即可。
 */
export function orderStatusTagType(status?: string | null): OrderStatusTagType {
  return visualOf(status).tag;
}

/** 工单状态 → 圆环色相令牌名（如 `'--h3'`）。`StatusRing` 用它取 `var(--hN)`。 */
export function orderStatusHueToken(status?: string | null): StatusHueToken {
  return visualOf(status).hue;
}

/**
 * 工单状态 → 色值（hex）。给**非 CSS** 的消费者用（ECharts、导出、复核脚本）。
 * 在组件里画圆环请用 `orderStatusHueToken()` + `var(--hN)`，让色值只活在 tokens.css 里。
 */
export function orderStatusHue(status?: string | null): string {
  return STATUS_HUE[visualOf(status).hue];
}

export const WorkerSkillLabels: Record<string, string> = {
  ELECTRICIAN: '电工电气',
  PLUMBER: '水暖管道',
  HVAC: '暖通空调',
  LOCKSMITH: '门窗锁具',
  NETWORK: '弱电网络',
  GENERAL: '综合维修'
};

/**
 * 职能中文名（口径见仓库根 CONTEXT.md）。
 * **职能**回答「能干什么」，可多值，存在 `sys_role` / `sys_user_role`，是授权的唯一判据。
 */
export const FunctionLabels: Record<string, string> = {
  REPORTER: '报修人',
  WORKER: '维修作业',
  DISPATCHER: '后勤调度',
  PLATFORM_ADMIN: '平台管理'
};

/**
 * 组织条线中文名。**条线**回答「你是谁、归哪条组织线」，单值，
 * 同时就是单位字典 `org_node.audience` 的取值（同一个值，不做二次映射）。
 */
export const BranchLabels: Record<string, string> = {
  STUDENT: '学生',
  FACULTY: '教学科研',
  LOGISTICS: '后勤保障',
  ADMIN: '行政信息化'
};

/** 取职能中文名；取不到时回退原值，再兜底为「未分配职能」 */
export function functionLabel(code?: string | null): string {
  if (!code) return '未分配职能';
  return FunctionLabels[code] || code;
}

/** 取组织条线中文名；取不到时回退原值 */
export function branchLabel(code?: string | null): string {
  if (!code) return '未分配';
  return BranchLabels[code] || code;
}

/** 身份中文名：身份是条线的**派生视图**，只有「学生」与「教职工」两类 */
export function identityLabelOf(branch?: string | null): string {
  if (!branch) return '未分配';
  return branch === 'STUDENT' ? '学生' : '教职工';
}
