<template>
  <div class="student-order-detail">
    <div class="page-head">
      <el-page-header content="工单详情与质检验收" @back="router.back()" />
    </div>

    <div v-loading="loading">
      <!-- 1. 状态条 -->
      <el-alert :title="statusTitle" :description="statusTip" :type="statusAlertType" show-icon :closable="false" class="status-banner" />

      <!-- 2. 基本信息 -->
      <el-card shadow="never" class="block">
        <template #header><span class="block-title">报修基本信息</span></template>
        <el-descriptions :column="isNarrowPhone ? 1 : 2" border>
          <el-descriptions-item label="工单流水号">{{ order.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="提单时间">{{ formatDateTime(order.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="报修空间" :span="2">{{ order.spaceFullPath }}</el-descriptions-item>
          <el-descriptions-item label="报修故障">{{ order.categoryFullName }}</el-descriptions-item>
          <el-descriptions-item label="期望预约">{{ order.appointmentPeriod }}</el-descriptions-item>
          <el-descriptions-item label="维修师傅">
            {{ order.workerName ? `${order.workerName}（${order.workerPhone || '暂无电话'}）` : '待派单' }}
          </el-descriptions-item>
          <el-descriptions-item label="紧急度">
            <el-tag v-if="order.urgencyLevel === 2" type="danger" effect="plain">加急</el-tag>
            <span v-else>普通</span>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 3. 前后实况对比 (Visual Diff) -->
      <el-card shadow="never" class="block">
        <template #header><span class="block-title">现场维修实况对比（Visual Diff）</span></template>
        <div class="diff-grid">
          <div class="diff-pane">
            <el-tag type="info" effect="plain" class="diff-badge">报修初始实况</el-tag>
            <p class="diff-desc">{{ evaluation.description || order.description || '—' }}</p>
            <div v-if="evaluation.faultImages && evaluation.faultImages.length" class="image-row">
              <el-image
                v-for="(img, idx) in evaluation.faultImages"
                :key="img"
                :src="img"
                :preview-src-list="evaluation.faultImages"
                :initial-index="idx"
                fit="cover"
                preview-teleported
                class="diff-thumb"
              />
            </div>
            <p v-else class="no-image">未上传照片</p>
          </div>

          <div class="diff-divider"><el-icon :size="20" class="diff-arrow"><Right /></el-icon></div>

          <div class="diff-pane">
            <el-tag type="primary" effect="plain" class="diff-badge">完工防伪实况</el-tag>
            <p class="diff-desc">{{ evaluation.repairSummary || '师傅现场处置中或尚未上报' }}</p>
            <div v-if="evaluation.repairImages && evaluation.repairImages.length" class="image-row">
              <el-image
                v-for="(img, idx) in evaluation.repairImages"
                :key="img"
                :src="img"
                :preview-src-list="evaluation.repairImages"
                :initial-index="idx"
                fit="cover"
                preview-teleported
                class="diff-thumb"
              />
            </div>
            <p v-else class="no-image">暂无完工照</p>
            <p v-if="evaluation.materialCost !== undefined && evaluation.materialCost !== null" class="diff-cost">
              耗材直接成本：¥{{ Number(evaluation.materialCost).toFixed(2) }}
            </p>
          </div>
        </div>
      </el-card>

      <!-- 4. 已有评价展示 -->
      <el-card
        v-if="order.status === 'COMPLETED' || order.status === 'REWORK_PENDING'"
        shadow="never"
        class="block"
      >
        <template #header><span class="block-title">服务质检评价记录</span></template>
        <el-descriptions :column="isNarrowPhone ? 1 : 2" border>
          <el-descriptions-item label="综合评价星级">
            <el-rate :model-value="evaluation.ratingStar || 0" disabled />
          </el-descriptions-item>
          <el-descriptions-item label="确认方式">
            {{ evaluation.isAutoConfirmed === 1 ? '72h 超时自动闭环结单' : '师生主动验收评价' }}
          </el-descriptions-item>
          <el-descriptions-item label="满意标签">
            <template v-if="evaluation.ratingTags && evaluation.ratingTags.length">
              <el-tag v-for="tag in evaluation.ratingTags" :key="tag" class="tag-gap">{{ tag }}</el-tag>
            </template>
            <span v-else class="muted">—</span>
          </el-descriptions-item>
          <el-descriptions-item label="返工督办">
            {{ evaluation.reworkFlag === 1 ? '已触发差评返工流程' : '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="用户评语" :span="2">{{ evaluation.evaluationComment || '未填写评语' }}</el-descriptions-item>
          <el-descriptions-item v-if="evaluation.arbitrationNote" label="主管仲裁说明" :span="2">
            {{ evaluation.arbitrationNote }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 5. 全流程审计时间轴（复用管理端时间轴组件） -->
      <el-card shadow="never" class="block">
        <template #header><span class="block-title">工单全流程流转审计时间轴</span></template>
        <WorkOrderTimeline v-if="orderId" :order-id="orderId" />
      </el-card>

      <!-- 6. 待验收评价面板 -->
      <el-card v-if="order.status === 'PENDING_ACCEPTANCE'" shadow="never" class="block evaluate-panel">
        <template #header><span class="block-title">师傅已完工，请核验现场实况并确认验收</span></template>

        <div class="rate-row">
          <span class="rate-title">服务满意度打分：</span>
          <el-rate v-model="ratingStar" :texts="rateTexts" show-text text-color="var(--muted)" />
        </div>

        <template v-if="ratingStar >= 3">
          <div class="tag-title">选择满意标签（可选）：</div>
          <div class="tag-list">
            <span
              v-for="tag in commonTags"
              :key="tag"
              class="tag-pill"
              :class="{ active: selectedTags.includes(tag) }"
              @click="toggleTag(tag)"
            >
              {{ tag }}
            </span>
          </div>
        </template>

        <el-alert
          v-if="ratingStar > 0 && ratingStar <= 2"
          type="error"
          show-icon
          :closable="false"
          title="打分 ≤ 2 星将立即触发【差评返工督办】流程！工单将被置顶由后勤主管调度二次上门整改，请详细说明未解决问题。"
          class="rework-alert"
        />

        <el-input
          v-model="comment"
          type="textarea"
          :rows="3"
          maxlength="300"
          show-word-limit
          class="comment-input"
          :placeholder="ratingStar > 0 && ratingStar <= 2
            ? '请详细指出尚未彻底修好的问题（必填，便于安排返工）'
            : '师傅辛苦了，写几句鼓励或反馈吧（选填）'"
        />

        <div class="panel-actions">
          <el-button :type="ratingStar <= 2 ? 'danger' : 'primary'" size="large" :loading="evaluating" @click="onSubmitEvaluation">
            {{ ratingStar <= 2 ? '提交差评并申请返工' : '确认验收结单' }}
          </el-button>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Right } from '@element-plus/icons-vue';
import { httpClient } from '../../stores/auth';
import { useViewport } from '../../composables/useViewport';
import WorkOrderTimeline from '../../components/WorkOrderTimeline.vue';

const { isNarrowPhone } = useViewport();
import {
  DispatchOrderItemVO,
  WorkOrderEvaluationVO,
  WorkOrderEvaluateRequest,
  OrderStatusLabels,
  formatDateTime
} from '@campus/shared';

const route = useRoute();
const router = useRouter();
const orderId = Number(route.params.id);

const loading = ref(true);
const evaluating = ref(false);

const order = ref<Partial<DispatchOrderItemVO>>({});
const evaluation = ref<Partial<WorkOrderEvaluationVO>>({});

// 评价输入
const ratingStar = ref(5);
const selectedTags = ref<string[]>([]);
const comment = ref('');

const commonTags = ['准时到达', '技术过硬', '着装整洁', '清理彻底', '礼貌热情'];
const rateTexts = ['', '非常差 (严重返工)', '不满意 (返工)', '一般', '满意', '非常满意'];

const statusTitle = computed(() =>
  order.value.status ? `${OrderStatusLabels[order.value.status] || order.value.status}` : '加载中'
);

const statusTip = computed(() => {
  switch (order.value.status) {
    case 'PENDING_DISPATCH': return '已提交，等待系统智能调度';
    case 'MANUAL_DISPATCH': return '待后勤主管人工调派';
    case 'ASSIGNED': return '师傅已指派，等待接单出发';
    case 'IN_PROGRESS': return '师傅已接单出发前往现场施工';
    case 'SUSPENDED': return '师傅已申请施工挂起';
    case 'PENDING_ACCEPTANCE': return '师傅已完工上报，请您核验现场实况并打分评价';
    case 'REWORK_PENDING': return '您已提交差评，工单正由后勤主管安排二次返工';
    case 'COMPLETED': return '工单已圆满验收结单';
    case 'CANCELLED': return '工单已核实作废';
    default: return '';
  }
});

const statusAlertType = computed<'success' | 'warning' | 'info' | 'error' | 'primary'>(() => {
  switch (order.value.status) {
    case 'COMPLETED': return 'success';
    case 'REWORK_PENDING': return 'error';
    case 'PENDING_ACCEPTANCE': return 'primary';
    case 'IN_PROGRESS': case 'ASSIGNED': return 'warning';
    default: return 'info';
  }
});

onMounted(fetchData);

async function fetchData() {
  loading.value = true;
  try {
    const [orderRes, evalRes] = await Promise.all([
      httpClient.get<DispatchOrderItemVO>(`/work-orders/${orderId}`),
      httpClient.get<WorkOrderEvaluationVO>(`/work-orders/${orderId}/evaluation-detail`)
    ]);
    if (orderRes.code === 200 && orderRes.data) order.value = orderRes.data;
    if (evalRes.code === 200 && evalRes.data) evaluation.value = evalRes.data;
  } catch (e: any) {
    ElMessage.error(e.message || '获取工单信息失败');
  } finally {
    loading.value = false;
  }
}

function toggleTag(tag: string) {
  const idx = selectedTags.value.indexOf(tag);
  if (idx >= 0) selectedTags.value.splice(idx, 1);
  else selectedTags.value.push(tag);
}

async function onSubmitEvaluation() {
  if (ratingStar.value <= 2 && !comment.value.trim()) {
    ElMessage.warning('打分不高于2星时，必须填写未解决的问题说明！');
    return;
  }

  evaluating.value = true;
  try {
    const req: WorkOrderEvaluateRequest = {
      ratingStar: ratingStar.value,
      ratingTags: ratingStar.value >= 3 ? selectedTags.value : [],
      comment: comment.value.trim()
    };

    const res = await httpClient.post<void>(`/work-orders/${orderId}/evaluate`, req);
    if (res.code === 200) {
      ElMessage.success(ratingStar.value <= 2 ? '已提交差评反馈，工单转入返工督办' : '验收结单成功，感谢您的评价！');
      fetchData();
    } else {
      ElMessage.error(res.message || '评价提交失败');
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || e.message || '请求异常');
  } finally {
    evaluating.value = false;
  }
}
</script>

<style scoped>
.student-order-detail {
  padding: 16px;
}
.page-head {
  margin-bottom: 14px;
}
.status-banner {
  margin-bottom: 14px;
}
.block {
  margin-bottom: 14px;
}
.block-title {
  font-weight: 600;
}
.diff-grid {
  display: flex;
  gap: 12px;
  align-items: stretch;
  flex-wrap: wrap;
}
.diff-pane {
  flex: 1 1 280px;
  min-width: 0;
  background: var(--canvas);
  border-radius: var(--radius-card);
  padding: 12px;
}
.diff-badge {
  margin-bottom: 8px;
}
.diff-desc {
  margin: 0 0 8px 0;
  font-size: 13px;
  color: var(--ink);
  line-height: 1.5;
  min-height: 36px;
}
.image-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.diff-thumb {
  width: 96px;
  height: 96px;
  border-radius: var(--radius);
}
.no-image {
  font-size: var(--fs-base);
  color: var(--faint);
  font-style: italic;
}
/* 返工产生的差异成本**不再染红**——金额不是警戒 */
.diff-cost {
  margin: 10px 0 0 0;
  font-size: var(--fs-base);
  color: var(--ink);
  font-weight: var(--fw-medium);
}
.diff-divider {
  display: flex;
  align-items: center;
}
/* 「报修初始实况 ➔ 完工结果」之间的箭头：可交互方向指示 → 走主色令牌 */
.diff-arrow {
  color: var(--el-color-primary);
}
.tag-gap {
  margin-right: 6px;
}
.muted {
  color: var(--faint);
}
.rate-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.rate-title {
  font-weight: var(--fw-medium);
  color: var(--ink);
}
.tag-title {
  font-size: 13px;
  color: var(--muted);
  margin-bottom: 8px;
}
.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 12px;
}
.tag-pill {
  font-size: 13px;
  padding: 5px 14px;
  border-radius: var(--radius);
  background: var(--canvas);
  color: var(--muted);
  cursor: pointer;
  /* 只过渡具体属性：`all` 会把 padding/圆角也纳入过渡 */
  transition: background-color 0.2s, color 0.2s;
  user-select: none;
}
.tag-pill.active {
  background: var(--el-color-primary);
  color: var(--surface);
}
.rework-alert {
  margin-bottom: 12px;
}
.comment-input {
  max-width: 640px;
}
.panel-actions {
  margin-top: 14px;
}
</style>
