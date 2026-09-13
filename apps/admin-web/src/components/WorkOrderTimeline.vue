<template>
  <div class="audit-timeline">
    <div v-if="loading" class="timeline-loading">
      <el-skeleton :rows="3" animated />
    </div>

    <div v-else-if="!timelineList || timelineList.length === 0" class="timeline-empty">
      <el-empty description="暂无流转审计日志" :image-size="80" />
    </div>

    <el-timeline v-else class="timeline-flow">
      <el-timeline-item
        v-for="log in timelineList"
        :key="log.id"
        :timestamp="log.createdAt"
        placement="top"
        :type="getNodeType(log.operatorRole)"
        :hollow="false"
      >
        <div class="timeline-node-card" :class="`role-border-${log.operatorRole}`">
          <div class="node-header">
            <span class="action-badge" :class="`badge-${log.operatorRole}`">
              {{ ActionTypeLabels[log.actionType] || log.actionType }}
            </span>
            <div class="operator-info">
              <span class="operator-name">{{ log.operatorName }}</span>
              <el-tag size="small" :type="getRoleTagType(log.operatorRole)" effect="plain">
                {{ getRoleLabel(log.operatorRole) }}
              </el-tag>
            </div>
          </div>

          <!-- 状态跳变 -->
          <div class="status-jump" v-if="log.fromStatus !== log.toStatus">
            <span class="status-tag from">{{ getStatusLabel(log.fromStatus) }}</span>
            <span class="jump-arrow">➔</span>
            <span class="status-tag to">{{ getStatusLabel(log.toStatus) }}</span>
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
import { ref, onMounted, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { httpClient } from '../stores/auth';
import {
  OrderTimelineLogVO,
  ActionTypeLabels,
  OrderStatusLabels,
  OrderStatusEnum,
  OperatorRoleLabels,
  parseImageUrls
} from '@campus/shared';

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

type NodeType = 'primary' | 'success' | 'warning' | 'info' | 'danger';
type TagType = '' | 'success' | 'warning' | 'info' | 'danger';

/**
 * `operator_role` 词表 → 时间轴配色。**一张表同时驱动节点类型与标签类型**：
 * 原先 `getNodeType` 与 `getRoleTagType` 是两份逐字重复的 switch，新增一种码要改两处，
 * 漏改一处就会出现「节点一种颜色、标签另一种颜色」。
 *
 * 表里同时收录**历史词表**（改名前的角色码，只出现在旧数据里）与**现行条线码**
 * （运行时按操作人的 sys_user.user_type 写入，见 WorkOrderService）：两者都必须在这里有值，
 * 否则新增的派单/改派行会掉进 default 分支、丢掉配色提示。
 */
const ROLE_STYLES: Record<string, { node: NodeType; tag: TagType }> = {
  // 报修侧：学生 / 教工（旧码 TEACHER 与新条线码 FACULTY 同色）
  STUDENT: { node: 'primary', tag: '' },
  TEACHER: { node: 'primary', tag: '' },
  FACULTY: { node: 'primary', tag: '' },
  // 作业侧
  REPAIR_WORKER: { node: 'success', tag: 'success' },
  WORKER: { node: 'success', tag: 'success' },
  // 管理/后勤侧
  LOGISTICS_ADMIN: { node: 'warning', tag: 'warning' },
  SUPER_ADMIN: { node: 'warning', tag: 'warning' },
  ADMIN: { node: 'warning', tag: 'warning' },
  LOGISTICS: { node: 'warning', tag: 'warning' },
  // 系统自动流转
  SYSTEM: { node: 'info', tag: 'info' }
};
const DEFAULT_ROLE_STYLE: { node: NodeType; tag: TagType } = { node: 'info', tag: 'info' };

function roleStyle(role: string) {
  return ROLE_STYLES[role] ?? DEFAULT_ROLE_STYLE;
}

function getNodeType(role: string): NodeType {
  return roleStyle(role).node;
}

function getRoleTagType(role: string): TagType {
  return roleStyle(role).tag;
}

function getRoleLabel(role: string): string {
  return OperatorRoleLabels[role] || role;
}

function getStatusLabel(status: string): string {
  return OrderStatusLabels[status as OrderStatusEnum] || status;
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
.timeline-node-card {
  background: #ffffff;
  border-radius: 8px;
  padding: 12px 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  border: 1px solid #E5E7EB;
  border-left-width: 4px;
}
.role-border-STUDENT, .role-border-TEACHER, .role-border-FACULTY {
  border-left-color: #1677FF;
}
.role-border-REPAIR_WORKER, .role-border-WORKER {
  border-left-color: #52C41A;
}
.role-border-LOGISTICS_ADMIN, .role-border-SUPER_ADMIN, .role-border-ADMIN, .role-border-LOGISTICS {
  border-left-color: #FA8C16;
}
.role-border-SYSTEM {
  border-left-color: #8C8C8C;
}

.node-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.action-badge {
  font-size: 13px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
}
.badge-STUDENT, .badge-TEACHER, .badge-FACULTY {
  background: #E6F4FF;
  color: #1677FF;
}
.badge-REPAIR_WORKER, .badge-WORKER {
  background: #F6FFED;
  color: #52C41A;
}
.badge-LOGISTICS_ADMIN, .badge-SUPER_ADMIN, .badge-ADMIN, .badge-LOGISTICS {
  background: #FFF7E6;
  color: #FA8C16;
}
.badge-SYSTEM {
  background: #F5F5F5;
  color: #595959;
}

.operator-info {
  display: flex;
  align-items: center;
  gap: 8px;
}
.operator-name {
  font-size: 13px;
  color: #374151;
  font-weight: 500;
}

.status-jump {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 12px;
}
.status-tag {
  padding: 2px 6px;
  border-radius: 4px;
}
.status-tag.from {
  background: #F3F4F6;
  color: #6B7280;
}
.status-tag.to {
  background: #EFF6FF;
  color: #2563EB;
  font-weight: 500;
}
.jump-arrow {
  color: #9CA3AF;
  font-size: 11px;
}

.node-remark {
  font-size: 13px;
  color: #4B5563;
  line-height: 1.5;
  background: #F9FAFB;
  padding: 6px 10px;
  border-radius: 4px;
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
  border-radius: 4px;
  border: 1px solid #E5E7EB;
}
</style>
