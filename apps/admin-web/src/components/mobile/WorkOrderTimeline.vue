<template>
  <div class="mobile-audit-timeline">
    <div v-if="loading" style="padding: 16px;">
      <van-skeleton title :row="4" />
    </div>

    <div v-else-if="!timelineList || timelineList.length === 0" class="empty-wrap">
      <van-empty image="search" description="暂无工单流转记录" />
    </div>

    <div v-else class="timeline-step-list">
      <div
        v-for="(log, idx) in timelineList"
        :key="log.id"
        class="step-item"
        :class="{ 'is-last': idx === timelineList.length - 1 }"
      >
        <!-- 左侧指示器 -->
        <div class="step-indicator">
          <div class="step-dot" :class="`dot-${log.operatorRole}`">
            <span class="dot-inner"></span>
          </div>
          <div v-if="idx !== timelineList.length - 1" class="step-line"></div>
        </div>

        <!-- 右侧内容卡片 -->
        <div class="step-content">
          <div class="step-header">
            <span class="action-title" :class="`color-${log.operatorRole}`">
              {{ ActionTypeLabels[log.actionType] || log.actionType }}
            </span>
            <span class="step-time">{{ log.createdAt }}</span>
          </div>

          <div class="operator-row">
            <span class="operator-name">{{ log.operatorName }}</span>
            <span class="operator-badge" :class="`badge-${log.operatorRole}`">
              {{ getRoleLabel(log.operatorRole) }}
            </span>
          </div>

          <!-- 状态跳变 -->
          <div class="status-change-row" v-if="log.fromStatus !== log.toStatus">
            <span class="badge-status">{{ getStatusLabel(log.fromStatus) }}</span>
            <span class="arrow-icon">➔</span>
            <span class="badge-status to">{{ getStatusLabel(log.toStatus) }}</span>
          </div>

          <!-- 说明与原因 -->
          <div v-if="log.reasonOrRemark" class="step-remark">
            {{ log.reasonOrRemark }}
          </div>

          <!-- 照片展示 -->
          <div v-if="parseImages(log.evidenceImages).length > 0" class="step-images">
            <van-image
              v-for="(img, imgIdx) in parseImages(log.evidenceImages)"
              :key="imgIdx"
              :src="img"
              width="54"
              height="54"
              fit="cover"
              radius="4"
              class="thumb-img"
              @click="previewImg(parseImages(log.evidenceImages), imgIdx)"
            />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { showToast, showImagePreview } from 'vant';
import { httpClient } from '../../stores/auth';
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
    const res = await httpClient.get<OrderTimelineLogVO[]>(`/api/v1/work-orders/${props.orderId}/timeline`);
    if (res.code === 200 && res.data) {
      timelineList.value = res.data;
    }
  } catch (e: any) {
    showToast('获取流转时间轴失败');
  } finally {
    loading.value = false;
  }
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

function previewImg(images: string[], startPosition: number) {
  showImagePreview({
    images,
    startPosition,
    closeable: true
  });
}
</script>

<style scoped>
.mobile-audit-timeline {
  padding: 12px 16px;
}
.empty-wrap {
  padding: 24px 0;
}
.timeline-step-list {
  display: flex;
  flex-direction: column;
}
.step-item {
  display: flex;
  position: relative;
  min-height: 70px;
}
.step-indicator {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 28px;
  flex-shrink: 0;
}
.step-dot {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2;
  margin-top: 2px;
}
.dot-inner {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #fff;
}
.dot-STUDENT, .dot-TEACHER {
  background-color: #1989fa;
}
.dot-REPAIR_WORKER, .dot-WORKER {
  background-color: #07c160;
}
.dot-LOGISTICS_ADMIN, .dot-SUPER_ADMIN, .dot-ADMIN {
  background-color: #ff976a;
}
.dot-SYSTEM {
  background-color: #969799;
}

.step-line {
  width: 2px;
  flex-grow: 1;
  background-color: #ebedf0;
  margin: 4px 0;
}

.step-content {
  flex-grow: 1;
  padding-left: 10px;
  padding-bottom: 18px;
}

.step-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 4px;
}
.action-title {
  font-size: 14px;
  font-weight: 600;
}
.color-STUDENT, .color-TEACHER {
  color: #1989fa;
}
.color-REPAIR_WORKER, .color-WORKER {
  color: #07c160;
}
.color-LOGISTICS_ADMIN, .color-SUPER_ADMIN, .color-ADMIN {
  color: #ff976a;
}
.color-SYSTEM {
  color: #646566;
}

.step-time {
  font-size: 11px;
  color: #969799;
}

.operator-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.operator-name {
  font-size: 12px;
  color: #323233;
  font-weight: 500;
}
.operator-badge {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
}
.badge-STUDENT, .badge-TEACHER {
  background: #e8f4ff;
  color: #1989fa;
}
.badge-REPAIR_WORKER, .badge-WORKER {
  background: #eafaf1;
  color: #07c160;
}
.badge-LOGISTICS_ADMIN, .badge-SUPER_ADMIN, .badge-ADMIN {
  background: #fff5ed;
  color: #ed6a0c;
}
.badge-SYSTEM {
  background: #f2f3f5;
  color: #7d7e80;
}

.status-change-row {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  margin-bottom: 6px;
}
.badge-status {
  background: #f2f3f5;
  color: #646566;
  padding: 1px 6px;
  border-radius: 3px;
}
.badge-status.to {
  background: #e8f4ff;
  color: #1989fa;
  font-weight: 500;
}
.arrow-icon {
  color: #c8c9cc;
  font-size: 10px;
}

.step-remark {
  font-size: 12px;
  color: #646566;
  background: #f7f8fa;
  padding: 6px 10px;
  border-radius: 4px;
  line-height: 1.4;
  margin-bottom: 6px;
}

.step-images {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.thumb-img {
  border: 1px solid #ebedf0;
}
</style>
