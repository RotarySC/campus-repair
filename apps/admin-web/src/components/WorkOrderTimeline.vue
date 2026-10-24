<template>
  <div class="audit-timeline">
    <div v-if="loading" class="timeline-loading">
      <el-skeleton :rows="3" animated />
    </div>

    <div v-else-if="!timelineList || timelineList.length === 0" class="timeline-empty">
      <EmptyState description="暂无流转审计日志" :size="80" />
    </div>

    <el-timeline v-else class="timeline-flow">
      <el-timeline-item
        v-for="log in timelineList"
        :key="log.id"
        :timestamp="formatDateTime(log.createdAt)"
        placement="top"
        :hollow="false"
      >
        <!--
          原先这里挂着 `:class="role-border-${log.operatorRole}"`——一条 **4px 彩色左边条**，
          按操作人职能取蓝/绿/橙/灰。同一种「职能」信息在本卡片里有**四层颜色表达**：
          左边条 + 时间轴节点色（`:type="getNodeType(...)"`）+ 动作徽标底色 + 职能标签的 `el-tag`。
          D 的规矩是「色相只活在 9px 圆环的 2px 描边上」，四层塌成零层——
          职能是**文字信息**，它本来就有中文标签，不需要颜色复述。
        -->
        <div class="timeline-node-card">
          <div class="node-header">
            <span class="action-badge">
              {{ ActionTypeLabels[log.actionType] || log.actionType }}
            </span>
            <div class="operator-info">
              <span class="operator-name">{{ log.operatorName }}</span>
              <span class="role-chip">{{ getRoleLabel(log.operatorRole) }}</span>
            </div>
          </div>

          <!--
            状态跳变——**这才是圆环该出现的地方**（§5 的 V7）：
            空心圆环＝分类，正好是「变成了哪一类」的语义，色相由 `ORDER_STATUS_HUE` 推导，
            与表格状态列**同一个来源、同一份色值**（V9：两份投影、一处来源）。
            `fromStatus` 用中性字：它可能是 `DRAFT` 这种生命周期之外的码（见 script 里的词表），
            拿不到色相，也**不该**拿一个回落灰去冒充分类。
          -->
          <div class="status-jump" v-if="log.fromStatus !== log.toStatus">
            <template v-if="getStatusLabel(log.fromStatus)">
              <span class="status-from">{{ getStatusLabel(log.fromStatus) }}</span>
              <span class="jump-arrow">➔</span>
            </template>
            <StatusRing :status="log.toStatus" />
          </div>

          <!-- 详细说明与原因 -->
          <div v-if="log.reasonOrRemark" class="node-remark">
            {{ log.reasonOrRemark }}
          </div>

          <!-- 现场凭证照片 -->
          <div v-if="parseImages(log.evidenceImages).length > 0" class="evidence-images">
            <el-image
              v-for="(img, idx) in parseImages(log.evidenceImages)"
              :key="idx"
              :src="img"
              :preview-src-list="parseImages(log.evidenceImages)"
              :initial-index="idx"
              preview-teleported
              fit="cover"
              class="evidence-thumb"
            />
          </div>
        </div>
      </el-timeline-item>
    </el-timeline>
  </div>
</template>

<script setup lang="ts">
import EmptyState from './EmptyState.vue';
import { ref, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { httpClient } from '../stores/auth';
import {
  OrderTimelineLogVO,
  ActionTypeLabels,
  OrderStatusLabels,
  OrderStatusEnum,
  OperatorRoleLabels,
  parseImageUrls,
  formatDateTime
} from '@campus/shared';
import StatusRing from './StatusRing.vue';

const props = defineProps<{
  orderId: number;
}>();

const loading = ref(false);
const timelineList = ref<OrderTimelineLogVO[]>([]);

watch(() => props.orderId, (newVal) => {
  if (newVal) {
    fetchTimeline();
  }
}, { immediate: true });

async function fetchTimeline() {
  if (!props.orderId) return;
  loading.value = true;
  try {
    const res = await httpClient.get<OrderTimelineLogVO[]>(`/work-orders/${props.orderId}/timeline`);
    if (res.code === 200 && res.data) {
      timelineList.value = res.data;
    }
  } catch (err: any) {
    ElMessage.error(err.message || '获取工单流转审计日志失败');
  } finally {
    loading.value = false;
  }
}

/**
 * ⚠️ 原先这里有两个类型别名（`NodeType` / `TagType`）、一张 `ROLE_STYLES` 表与
 * `getNodeType` / `getRoleTagType` 两个函数，把 `operator_role` 映射成时间轴节点色与标签色。
 * 第二期按 §5 的 V7 把「职能」的颜色全部撤掉（职能是文字信息，本来就有中文标签），
 * 这些东西**没有剩下的用途，已整体删除**。
 *
 * 那张表当年还承担另一件事——**收录两代词表，免得历史日志掉进 default 分支**——
 * 但那份职责在 `packages/shared` 的 `OperatorRoleLabels` 里
 * （本仓 `NoLegacyRoleCodeTest` 唯二豁免的展示层文件之一）。
 * 本文件此后**不再出现任何旧角色码**；实测当期日志取值只有
 * `REPORTER` / `WORKER` / `SYSTEM` / `DISPATCHER`（77 条日志全覆盖）。
 */

/**
 * 只用于**展示**的补充状态词表。
 *
 * 审计日志里会出现工单生命周期**之外**的状态码：提交那一刻的 `fromStatus` 是 `DRAFT`
 * （2026-10-12 实测 77 条日志里 **20 条**如此）。而 `OrderStatusLabels` 里没有这个键，
 * 于是 `|| status` 的兜底会把**裸码 `DRAFT` 直接渲染给用户**——
 * 正是本仓库约定禁止的那类（「不许把 `PLUMBER` 这种字典码端给用户」）。
 *
 * **刻意不并进 `OrderStatusLabels`**：那张表同时驱动 §3.2 的 8 个色相，
 * 「9 个业务状态 ↔ 8 个色相」的映射是锁定的（`status-hue.spec.ts` 逐值比对），
 * 塞一个没有色相的码进去会打破它。所以补丁打在这里，作用域仅限本组件。
 */
const EXTRA_STATUS_LABELS: Record<string, string> = { DRAFT: '草稿' };

function getRoleLabel(role: string): string {
  return OperatorRoleLabels[role] || role;
}

function getStatusLabel(status?: string | null): string {
  if (!status) return '';
  return OrderStatusLabels[status as OrderStatusEnum] || EXTRA_STATUS_LABELS[status] || status;
}

function parseImages(imagesJson?: string): string[] {
  return parseImageUrls(imagesJson);
}
</script>

<style scoped>
.audit-timeline {
  padding: 8px 0;
}
.timeline-flow {
  padding-left: 8px;
}
/* 时间轴的节点圆点：EP 默认取 `--el-color-primary`（蓝）。
   D 的规矩是「色相只服务状态语义」，而这个点是**每一步的占位符**，不承载状态——
   一律中性；**色相改由右侧跳变里的状态圆环承担**（V7）。
   同理，`el-timeline-item` 的 `:type="getNodeType(...)"` 那个按职能上色的入参已删。 */
.timeline-flow :deep(.el-timeline-item__node) {
  background-color: var(--faint);
}
.timeline-flow :deep(.el-timeline-item__tail) {
  border-left-color: var(--line);
}
.timeline-node-card {
  background: var(--surface);
  border-radius: var(--radius-card);
  padding: 12px 14px;
  /* §3.5 / V15：禁止自造阴影，层级用发丝线边框建立（原先有一条 0 1px 3px 的投影） */
  border: var(--hairline) solid var(--line);
}

.node-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
/* 动作徽标：**中性发丝线小胶囊**。原先六组「浅底 + 同色文字」按操作人职能上色，
   而职能**不是** §3.2 锁定的 8 个状态色相之一——给它颜色等于新增色相，
   正是 D 明确不做的事（色相只有 8 个，且只服务状态语义）。 */
.action-badge {
  font-size: var(--fs-base);
  font-weight: var(--fw-normal);
  color: var(--muted);
  padding: 1px 6px;
  border: var(--hairline) solid var(--line);
  border-radius: 0;
}

.operator-info {
  display: flex;
  align-items: center;
  gap: 8px;
}
.operator-name {
  font-size: 13px;
  color: var(--ink);
  font-weight: var(--fw-medium);
}
/* 职能标签：原先是个按职能上色的 `el-tag`。职能是文字信息，本来就有中文标签。 */
.role-chip {
  font-size: var(--fs-base);
  color: var(--muted);
  padding: 1px 6px;
  border: var(--hairline) solid var(--line);
}

.status-jump {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: var(--fs-base);
}
.status-from {
  color: var(--faint);
}
.jump-arrow {
  color: var(--disabled);
  font-size: 11px;
}

.node-remark {
  font-size: 13px;
  color: var(--muted);
  line-height: 1.5;
  background: var(--canvas);
  padding: 6px 10px;
  border-radius: 0;
  margin-top: 6px;
}

.evidence-images {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.evidence-thumb {
  width: 60px;
  height: 60px;
  border-radius: 0;
  border: var(--hairline) solid var(--line);
}
</style>
