/**
 * 字典/参数变更审计相关共享类型（Phase 04）。
 *
 * 与后端 `com.campus.repair.modules.audit` 一一对应：
 * - 查询端点 `GET /api/v1/operation-logs`（分页）与 `/operation-logs/{id}`（详情）
 * - 快照字段为**白名单字段**的对象（后端已把 JSON 反序列化好），前端直接做前后对比
 */
export type OperationModule = 'SPATIAL' | 'CATEGORY' | 'CONFIG';

export type OperationAction = 'CREATE' | 'UPDATE' | 'DELETE' | 'STATUS' | 'IMPORT';

export type OperationTargetType = 'SPATIAL_NODE' | 'REPAIR_CATEGORY' | 'SYS_CONFIG';

/** 审计行展示视图（后端 `OperationLogVO`） */
export interface OperationLogVO {
  id: number;
  module: string;
  action: string;
  targetType: string;
  targetId?: number | null;
  targetName?: string | null;
  /** CREATE 时为 null */
  before?: Record<string, unknown> | null;
  /** DELETE 时为 null */
  after?: Record<string, unknown> | null;
  operatorId: number;
  operatorName: string;
  operatorRole: string;
  remark?: string | null;
  /** yyyy-MM-dd HH:mm:ss */
  createdAt: string;
}

/** 审计分页查询参数（后端 `OperationLogQueryRequest`） */
export interface OperationLogQuery {
  module?: OperationModule | 'ALL' | string;
  targetType?: OperationTargetType | string;
  targetId?: number;
  /** 支持 yyyy-MM-dd / yyyy-MM-dd HH:mm:ss */
  start?: string;
  end?: string;
  page?: number;
  size?: number;
}

/** 模块筛选下拉：中文标签与后端词表一一对应 */
export const OperationModuleLabels: Record<OperationModule, string> = {
  SPATIAL: '空间网格',
  CATEGORY: '故障分类',
  CONFIG: '系统参数'
};

/** 动作标签：详情/列表里只展示中文，不回显 CREATE/UPDATE 裸码 */
export const OperationActionLabels: Record<OperationAction, string> = {
  CREATE: '新增',
  UPDATE: '修改',
  DELETE: '删除',
  STATUS: '启停',
  IMPORT: '导入'
};

/** 兼容映射：后端历史/异常值原样展示时的兜底 */
export function operationModuleLabel(code?: string | null): string {
  if (!code) return '—';
  return OperationModuleLabels[code as OperationModule] || code;
}

export function operationActionLabel(code?: string | null): string {
  if (!code) return '—';
  return OperationActionLabels[code as OperationAction] || code;
}
