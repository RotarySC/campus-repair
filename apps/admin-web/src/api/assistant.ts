import { ASSISTANT_DEGRADED_LABELS, type AssistantSuggestion } from '@campus/shared';
import { httpClient } from '../stores/auth';

/**
 * 智能助手的**唯一**请求出口。
 *
 * <p>抽屉（`AssistantWidget`）与提单页内联推荐（`StudentSubmitRepairView`）是同一件事的两个入口：
 * 都是「把一段描述发给 `/assistant/suggest`，拿回候选或一句原因」。此前两边各写了一遍
 * POST + `code === 200 && data` 判定 + 降级文案映射——于是"什么算成功""降级文案取哪张表"
 * 这类口径有两个实现，改一处不会改另一处。这里收成一份，两个入口只负责各自的界面表现。
 *
 * <p>**不抛异常**：助手永远不能把业务页面拖住（见设计文档 §3.6 与仓库 `AGENTS.md` 的助手条），
 * 网络/超时/服务端拒绝一律归一成 `ok: false`，由调用方决定是提示还是静默。
 */

/** 服务端明确拒绝（HTTP 200 + `code != 200`）还是压根没连上——两者给用户的话不一样 */
export type AssistantSuggestFailure = 'rejected' | 'unavailable';

export type AssistantSuggestOutcome =
  | { ok: true; suggestion: AssistantSuggestion }
  | { ok: false; reason: AssistantSuggestFailure; message: string };

/**
 * 请助手判断故障细类。
 *
 * @param description 用户对故障现象的描述（调用方负责"至少 2 字"这类前置校验）
 * @param spaceId     报修空间 id，**只回显、不参与打分**（v1 纯文本匹配）
 */
export async function requestAssistantSuggestion(
  description: string,
  spaceId?: number
): Promise<AssistantSuggestOutcome> {
  try {
    const res = await httpClient.post<AssistantSuggestion>('/assistant/suggest', { description, spaceId });
    if (res.code === 200 && res.data) {
      return { ok: true, suggestion: res.data };
    }
    return { ok: false, reason: 'rejected', message: res.message || '' };
  } catch (_) {
    return { ok: false, reason: 'unavailable', message: '' };
  }
}

/** 降级原因 → 一句人话（没降级时返回 `''`，调用方据此决定要不要渲染那一行） */
export function assistantDegradedHint(suggestion: AssistantSuggestion | null | undefined): string {
  const reason = suggestion?.degradedReason;
  return reason ? ASSISTANT_DEGRADED_LABELS[reason] : '';
}
