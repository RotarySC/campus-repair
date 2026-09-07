<template>
  <div class="order-detail-container">
    <van-nav-bar
      title="工单详情与质检验收"
      left-arrow
      @click-left="router.back()"
      fixed
      placeholder
    />

    <van-pull-refresh v-model="refreshing" @refresh="fetchData">
      <div v-if="loading" style="padding: 24px 16px;">
        <van-skeleton title :row="5" />
      </div>

      <div v-else class="detail-body">
        <!-- 1. 状态条 -->
        <div class="status-banner" :class="statusClass">
          <div class="status-title">{{ statusLabel }}</div>
          <div class="status-desc">{{ statusTip }}</div>
        </div>

        <!-- 2. 工单基本信息 -->
        <van-cell-group inset title="报修基本信息">
          <van-cell title="工单流水号" :value="order.orderNo" />
          <van-cell title="报修空间" :label="order.spaceFullPath" />
          <van-cell title="报修故障" :value="order.categoryFullName" />
          <van-cell title="期望预约" :value="order.appointmentPeriod" />
          <van-cell title="提单时间" :value="order.createdAt" />
          <van-cell v-if="order.workerName" title="指派师傅" :value="`${order.workerName} (${order.workerPhone || '暂无电话'})`" />
        </van-cell-group>

        <!-- 3. 前后实况对比卡片 (Visual Diff) -->
        <van-cell-group inset title="现场维修实况对比 (Visual Diff)" style="margin-top: 12px;">
          <div class="diff-card">
            <!-- 报修前 -->
            <div class="diff-pane before-pane">
              <div class="diff-badge before-badge">报修初始实况</div>
              <div class="diff-desc">{{ evaluation.description || order.description }}</div>
              <div class="image-grid" v-if="evaluation.faultImages && evaluation.faultImages.length > 0">
                <van-image
                  v-for="(img, idx) in evaluation.faultImages"
                  :key="idx"
                  :src="img"
                  fit="cover"
                  radius="6"
                  class="preview-thumb"
                  @click="previewImage(evaluation.faultImages || [], idx)"
                />
              </div>
              <div v-else class="no-image">未上传照片</div>
            </div>

            <!-- 分割线 -->
            <div class="diff-divider">
              <van-icon name="arrow" color="#1989fa" size="18" />
            </div>

            <!-- 维修后 -->
            <div class="diff-pane after-pane">
              <div class="diff-badge after-badge">完工防伪实况</div>
              <div class="diff-desc">{{ evaluation.repairSummary || '师傅现场处置中或尚未上报' }}</div>
              <div class="image-grid" v-if="evaluation.repairImages && evaluation.repairImages.length > 0">
                <van-image
                  v-for="(img, idx) in evaluation.repairImages"
                  :key="idx"
                  :src="img"
                  fit="cover"
                  radius="6"
                  class="preview-thumb"
                  @click="previewImage(evaluation.repairImages || [], idx)"
                />
              </div>
              <div v-else class="no-image">暂无完工照</div>
              <div v-if="evaluation.materialCost !== undefined" class="diff-cost">
                耗材直接成本: ¥{{ Number(evaluation.materialCost).toFixed(2) }}
              </div>
            </div>
          </div>
        </van-cell-group>

        <!-- 4. 已有评价展示卡片 -->
        <van-cell-group v-if="order.status === 'COMPLETED' || order.status === 'REWORK_PENDING'" inset title="服务质检评价记录" style="margin-top: 12px;">
          <van-cell title="综合评价星级">
            <template #value>
              <van-rate :model-value="evaluation.ratingStar || 5" readonly color="#ffd21e" void-icon="star" void-color="#eee" />
            </template>
          </van-cell>
          <van-cell v-if="evaluation.ratingTags && evaluation.ratingTags.length > 0" title="满意标签">
            <template #value>
              <van-tag v-for="tag in evaluation.ratingTags" :key="tag" type="primary" plain style="margin-left: 4px;">
                {{ tag }}
              </van-tag>
            </template>
          </van-cell>
          <van-cell v-if="evaluation.evaluationComment" title="用户评语" :label="evaluation.evaluationComment" />
          <van-cell v-if="evaluation.isAutoConfirmed === 1" title="确认方式" value="72h超时自动闭环结单" />
          <van-cell v-if="evaluation.reworkFlag === 1" title="返工督办" value="已触发差评返工流程" />
          <van-cell v-if="evaluation.arbitrationNote" title="主管仲裁说明" :label="evaluation.arbitrationNote" />
        </van-cell-group>

        <!-- 5. 待验收评价操作面板 (当处于 PENDING_ACCEPTANCE 时常驻展示) -->
        <div v-if="order.status === 'PENDING_ACCEPTANCE'" class="evaluate-panel">
          <van-cell-group inset title="师傅已完工，请确认验收并评价">
            <div style="padding: 16px;">
              <div class="rate-row">
                <span class="rate-title">服务满意度打分：</span>
                <van-rate v-model="ratingStar" size="24" color="#ffd21e" void-icon="star" void-color="#eee" />
                <span class="rate-text">{{ starDescription }}</span>
              </div>

              <!-- 快捷标签 -->
              <div class="tag-selector" v-if="ratingStar >= 3">
                <div class="tag-title">选择满意标签：</div>
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
              </div>

              <!-- 差评 <= 2 星强提醒 -->
              <div v-if="ratingStar <= 2" class="rework-alert">
                <van-icon name="warning-o" color="#ee0a24" size="18" />
                <div class="rework-alert-text">
                  打分 ≤ 2 星将立即触发【差评返工督办】流程！工单将被置顶由后勤主管调度二次上门整改，请详细在下方说明未解决问题。
                </div>
              </div>

              <!-- 评语输入框 -->
              <van-field
                v-model="comment"
                rows="3"
                autosize
                type="textarea"
                maxlength="300"
                show-word-limit
                :placeholder="ratingStar <= 2 ? '请详细指出尚未彻底修好的问题（必填，便于安排返工）' : '师傅辛苦了，写几句鼓励或反馈吧（选填）'"
                :rules="[{ required: ratingStar <= 2, message: '差评必须填写未解决的问题说明' }]"
                class="comment-field"
              />

              <div style="margin-top: 16px;">
                <van-button
                  round
                  block
                  :type="ratingStar <= 2 ? 'danger' : 'primary'"
                  :loading="evaluating"
                  @click="onSubmitEvaluation"
                >
                  {{ ratingStar <= 2 ? '提交差评并申请返工' : '确认验收结单' }}
                </van-button>
              </div>
            </div>
          </van-cell-group>
        </div>
      </div>
    </van-pull-refresh>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showToast, showSuccessToast, showFailToast, showImagePreview } from 'vant';
import { httpClient } from '../../stores/auth';
import {
  ApiResponse,
  DispatchOrderItemVO,
  WorkOrderEvaluationVO,
  WorkOrderEvaluateRequest,
  OrderStatusLabels
} from '@campus/shared';

const route = useRoute();
const router = useRouter();
const orderId = Number(route.params.id);

const loading = ref(true);
const refreshing = ref(false);
const evaluating = ref(false);

const order = ref<Partial<DispatchOrderItemVO>>({});
const evaluation = ref<Partial<WorkOrderEvaluationVO>>({});

// 评价输入
const ratingStar = ref(5);
const selectedTags = ref<string[]>([]);
const comment = ref('');

const commonTags = ['准时到达', '技术过硬', '着装整洁', '清理彻底', '礼貌热情'];

const starDescription = computed(() => {
  switch (ratingStar.value) {
    case 5: return '非常满意';
    case 4: return '满意';
    case 3: return '一般';
    case 2: return '不满意 (返工)';
    case 1: return '非常差 (严重返工)';
    default: return '';
  }
});

const statusLabel = computed(() => {
  const s = order.value.status;
  return s ? (OrderStatusLabels[s] || s) : '加载中';
});

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

const statusClass = computed(() => {
  switch (order.value.status) {
    case 'PENDING_ACCEPTANCE': return 'status-pending-accept';
    case 'REWORK_PENDING': return 'status-rework';
    case 'COMPLETED': return 'status-completed';
    case 'IN_PROGRESS': return 'status-in-progress';
    default: return 'status-default';
  }
});

onMounted(() => {
  fetchData();
});

async function fetchData() {
  loading.value = true;
  try {
    const [orderRes, evalRes] = await Promise.all([
      httpClient.get<ApiResponse<DispatchOrderItemVO>>(`/api/v1/work-orders/${orderId}`),
      httpClient.get<ApiResponse<WorkOrderEvaluationVO>>(`/api/v1/work-orders/${orderId}/evaluation-detail`)
    ]);

    if (orderRes.data.code === 200 && orderRes.data.data) {
      order.value = orderRes.data.data;
    }
    if (evalRes.data.code === 200 && evalRes.data.data) {
      evaluation.value = evalRes.data.data;
    }
  } catch (err: any) {
    showFailToast('获取工单信息失败');
  } finally {
    loading.value = false;
    refreshing.value = false;
  }
}

function toggleTag(tag: string) {
  const idx = selectedTags.value.indexOf(tag);
  if (idx >= 0) {
    selectedTags.value.splice(idx, 1);
  } else {
    selectedTags.value.push(tag);
  }
}

function previewImage(images: string[], startPosition = 0) {
  if (!images || images.length === 0) return;
  showImagePreview({
    images,
    startPosition,
    closeable: true
  });
}

async function onSubmitEvaluation() {
  if (ratingStar.value <= 2 && !comment.value.trim()) {
    showToast('打分不高于2星时，必须填写未解决的问题说明！');
    return;
  }

  evaluating.value = true;
  try {
    const req: WorkOrderEvaluateRequest = {
      ratingStar: ratingStar.value,
      ratingTags: ratingStar.value >= 3 ? selectedTags.value : [],
      comment: comment.value.trim()
    };

    const res = await httpClient.post<ApiResponse<void>>(`/api/v1/work-orders/${orderId}/evaluate`, req);
    if (res.data.code === 200) {
      if (ratingStar.value <= 2) {
        showSuccessToast('已提交差评反馈，已转入返工督办');
      } else {
        showSuccessToast('验收结单成功，感谢您的评价！');
      }
      fetchData();
    } else {
      showFailToast(res.data.message || '评价提交失败');
    }
  } catch (e: any) {
    showFailToast(e.response?.data?.message || e.message || '请求异常');
  } finally {
    evaluating.value = false;
  }
}
</script>

<style scoped>
.order-detail-container {
  min-height: 100vh;
  background-color: #f7f8fa;
  padding-bottom: 40px;
}

.status-banner {
  padding: 16px 20px;
  color: #fff;
  margin-bottom: 8px;
}

.status-pending-accept {
  background: linear-gradient(135deg, #1989fa, #39a9ed);
}

.status-rework {
  background: linear-gradient(135deg, #ee0a24, #ff6034);
}

.status-completed {
  background: linear-gradient(135deg, #07c160, #2be187);
}

.status-in-progress {
  background: linear-gradient(135deg, #ff976a, #ed6a0c);
}

.status-default {
  background: linear-gradient(135deg, #969799, #c8c9cc);
}

.status-title {
  font-size: 18px;
  font-weight: bold;
}

.status-desc {
  font-size: 13px;
  margin-top: 4px;
  opacity: 0.9;
}

/* Visual Diff 卡片 */
.diff-card {
  display: flex;
  padding: 12px;
  background-color: #fff;
  border-radius: 8px;
  gap: 8px;
}

.diff-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  background-color: #f9f9f9;
  border-radius: 6px;
  padding: 10px;
}

.diff-badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 4px;
  margin-bottom: 6px;
  width: fit-content;
}

.before-badge {
  background-color: #f2f3f5;
  color: #646566;
}

.after-badge {
  background-color: #e8f4ff;
  color: #1989fa;
}

.diff-desc {
  font-size: 12px;
  color: #323233;
  line-height: 1.4;
  margin-bottom: 8px;
  min-height: 34px;
}

.image-grid {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.preview-thumb {
  width: 54px;
  height: 54px;
  cursor: pointer;
}

.no-image {
  font-size: 11px;
  color: #969799;
  font-style: italic;
  padding: 8px 0;
}

.diff-cost {
  font-size: 11px;
  color: #ee0a24;
  margin-top: 8px;
  font-weight: 600;
}

.diff-divider {
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 评价面板 */
.evaluate-panel {
  margin-top: 12px;
}

.rate-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rate-title {
  font-size: 14px;
  color: #323233;
  font-weight: 600;
}

.rate-text {
  font-size: 13px;
  font-weight: bold;
  color: #ff976a;
}

.tag-selector {
  margin-top: 12px;
}

.tag-title {
  font-size: 12px;
  color: #646566;
  margin-bottom: 6px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-pill {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 12px;
  background-color: #f2f3f5;
  color: #646566;
  cursor: pointer;
  transition: all 0.2s;
}

.tag-pill.active {
  background-color: #1989fa;
  color: #fff;
}

.rework-alert {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  background-color: #fff1f0;
  border: 1px solid #ffccc7;
  border-radius: 6px;
  padding: 8px 10px;
  margin-top: 12px;
}

.rework-alert-text {
  font-size: 12px;
  color: #cf1322;
  line-height: 1.4;
}

.comment-field {
  margin-top: 12px;
  background-color: #f7f8fa;
  border-radius: 6px;
}
</style>
