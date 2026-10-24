import { describe, it, expect } from 'vitest';
import { OrderStatusEnum } from '../src/constants/status';
import { canReporterCancel } from '../src/utils/orderActions';

/**
 * 「师傅接单前可取消」窗口的**同一张验收表**。
 *
 * 后端 `modules/workorder/service/ReporterCancelPolicy` 是判定真源，前端这
 * 份纯函数只在 VO 缺 `canCancel` 时兜底。两边的用例名与组合**逐条对应**，
 * 任何一边改了窗口（比如把 `ASSIGNED` 收窄掉），这里就会红。
 *
 * 十种状态 × acceptedTime 组合：
 *  ①`PENDING_DISPATCH` ②`MANUAL_DISPATCH` ③`ASSIGNED`+未接单 ④`ASSIGNED`+已接单（畸形）
 *  ⑤`IN_PROGRESS` ⑥`SUSPENDED` ⑦`PENDING_ACCEPTANCE` ⑧`REWORK_PENDING`
 *  ⑨`COMPLETED` ⑩`CANCELLED`；另加草稿 `DRAFT` 防御。
 */
describe('canReporterCancel · 可取消窗口', () => {
  it('① PENDING_DISPATCH（待派单）→ 可取消：还没人接', () => {
    expect(canReporterCancel(OrderStatusEnum.PENDING_DISPATCH)).toBe(true);
    expect(canReporterCancel(OrderStatusEnum.PENDING_DISPATCH, null)).toBe(true);
  });

  it('② MANUAL_DISPATCH（待人工调度）→ 可取消：同样没人接', () => {
    expect(canReporterCancel(OrderStatusEnum.MANUAL_DISPATCH)).toBe(true);
    expect(canReporterCancel(OrderStatusEnum.MANUAL_DISPATCH, undefined)).toBe(true);
  });

  it('③ ASSIGNED（待承接）且 acceptedTime 为空 → 可取消：已指派但师傅未接单', () => {
    expect(canReporterCancel(OrderStatusEnum.ASSIGNED, null)).toBe(true);
    expect(canReporterCancel(OrderStatusEnum.ASSIGNED, undefined)).toBe(true);
    expect(canReporterCancel(OrderStatusEnum.ASSIGNED, '')).toBe(true);
    expect(canReporterCancel(OrderStatusEnum.ASSIGNED, '   ')).toBe(true);
  });
});

describe('canReporterCancel · 不可取消', () => {
  it('④ ASSIGNED 但 acceptedTime 已写 → 拒绝（畸形数据防御：接单动作应同时推进状态）', () => {
    expect(canReporterCancel(OrderStatusEnum.ASSIGNED, '2026-10-24T09:30:00')).toBe(false);
  });

  it('⑤ IN_PROGRESS（维修中）→ 拒绝：已上门作业，只能走调度作废', () => {
    expect(canReporterCancel(OrderStatusEnum.IN_PROGRESS, '2026-10-24T09:30:00')).toBe(false);
  });

  it('⑥ SUSPENDED（已挂起）→ 拒绝：施工已开始', () => {
    expect(canReporterCancel(OrderStatusEnum.SUSPENDED, '2026-10-24T09:30:00')).toBe(false);
  });

  it('⑦ PENDING_ACCEPTANCE（待验收）→ 拒绝：已完工上报', () => {
    expect(canReporterCancel(OrderStatusEnum.PENDING_ACCEPTANCE, '2026-10-24T09:30:00')).toBe(false);
  });

  it('⑧ REWORK_PENDING（返工待派）→ 拒绝：返工流程中', () => {
    expect(canReporterCancel(OrderStatusEnum.REWORK_PENDING)).toBe(false);
  });

  it('⑨ COMPLETED（已结单）→ 拒绝：终态', () => {
    expect(canReporterCancel(OrderStatusEnum.COMPLETED)).toBe(false);
  });

  it('⑩ CANCELLED（已作废）→ 拒绝：终态，重复取消无意义', () => {
    expect(canReporterCancel(OrderStatusEnum.CANCELLED)).toBe(false);
  });

  it('草稿 DRAFT / 未知状态 → 拒绝：不产生工单流转，或不在白名单里', () => {
    expect(canReporterCancel('DRAFT')).toBe(false);
    expect(canReporterCancel('SOME_FUTURE_STATUS')).toBe(false);
    expect(canReporterCancel(null)).toBe(false);
    expect(canReporterCancel(undefined)).toBe(false);
  });
});

describe('canReporterCancel · 窗口覆盖完整性', () => {
  it('工单枚举的每个状态都有明确结论（新增状态时必须先想清窗口，再改这里）', () => {
    const expected: Record<OrderStatusEnum, boolean> = {
      [OrderStatusEnum.PENDING_DISPATCH]: true,
      [OrderStatusEnum.MANUAL_DISPATCH]: true,
      [OrderStatusEnum.ASSIGNED]: true,
      [OrderStatusEnum.IN_PROGRESS]: false,
      [OrderStatusEnum.SUSPENDED]: false,
      [OrderStatusEnum.PENDING_ACCEPTANCE]: false,
      [OrderStatusEnum.REWORK_PENDING]: false,
      [OrderStatusEnum.COMPLETED]: false,
      [OrderStatusEnum.CANCELLED]: false
    };
    for (const status of Object.values(OrderStatusEnum)) {
      expect(canReporterCancel(status)).toBe(expected[status]);
    }
  });

  it('acceptedTime 只影响 ASSIGNED，不影响其它状态的结论', () => {
    for (const status of Object.values(OrderStatusEnum)) {
      if (status === OrderStatusEnum.ASSIGNED) continue;
      expect(canReporterCancel(status, '2026-10-24T09:30:00')).toBe(canReporterCancel(status, null));
    }
  });
});
