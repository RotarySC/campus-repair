import { OrderStatusEnum } from '../constants/status';

/**
 * 报修人自助取消窗口。
 *
 * **与后端 `modules/workorder/service/ReporterCancelPolicy` 是同一张验收表**
 * （`test/order-actions.spec.ts` 与后端单测的用例名一一对应）：两边各自实现，
 * 但不允许规则漂移。页面上的入口仍然以 VO 的 `canCancel` 为准——
 * 本函数只做**兜底**（VO 缺字段时仍能给出正确可见性）与**可测的纯规则**，
 * 不要在页面里用 `status` 再手写一遍。
 *
 * 规则（开题「师傅接单前可取消」的落地口径）：
 *
 * | 状态 | 可取消 | 理由 |
 * | --- | --- | --- |
 * | `PENDING_DISPATCH` / `MANUAL_DISPATCH` | 是 | 还没人接 |
 * | `ASSIGNED` 且 `acceptedTime` 为空 | 是 | 已指派但师傅未接单 |
 * | `ASSIGNED` 且 `acceptedTime` 已写 | 否 | 畸形数据防御：接单动作本应同时推进状态 |
 * | `IN_PROGRESS` / `SUSPENDED` | 否 | 已上门作业，只能走调度作废 |
 * | `PENDING_ACCEPTANCE` / `REWORK_PENDING` | 否 | 完工/返工流程中 |
 * | `COMPLETED` / `CANCELLED` | 否 | 终态 |
 * | 草稿 / 未知状态 | 否 | 不产生工单流转，或状态不在白名单 |
 *
 * `status` 放宽到 `string` 是刻意的：草稿（`DRAFT`）不在工单状态枚举里，
 * 而它与「未知状态」都必须安全地判为不可取消；调用方传后端原值不会被类型卡住。
 */
export function canReporterCancel(
  status: OrderStatusEnum | string | null | undefined,
  acceptedTime?: string | null
): boolean {
  if (!status) return false;
  // 空白字符串同样视为「未接单」——后端判空用的是 `accepted_time IS NULL`，
  // 前端把「空串 / 纯空白」也当未接单，避免旧数据里 `''` 让它变成不可取消。
  const accepted = typeof acceptedTime === 'string' && acceptedTime.trim() !== '';
  switch (status) {
    case OrderStatusEnum.PENDING_DISPATCH:
    case OrderStatusEnum.MANUAL_DISPATCH:
      return true;
    case OrderStatusEnum.ASSIGNED:
      return !accepted;
    default:
      return false;
  }
}
