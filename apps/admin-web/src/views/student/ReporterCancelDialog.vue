<template>
  <el-dialog
    :model-value="modelValue"
    title="取消报修工单"
    width="min(480px, 94vw)"
    :close-on-click-modal="false"
    @update:model-value="onVisibleChange"
  >
    <!-- 二次确认的第一层：弹窗内常驻不可逆提示；点「确认取消」后还会再弹一次确认框 -->
    <AlertBanner>取消后不可恢复，如需维修请重新提单。</AlertBanner>

    <div v-if="order" class="cancel-target">
      <span class="cancel-target__label">工单</span>
      <span class="cancel-target__value">{{ order.orderNo }}</span>
      <span class="cancel-target__desc">{{ order.categoryFullName }}</span>
    </div>

    <el-form label-position="top">
      <el-form-item label="取消原因（选填，≤200 字）">
        <el-input
          v-model="reason"
          type="textarea"
          :rows="3"
          maxlength="200"
          show-word-limit
          placeholder="例如：已自行修好、误报、重复提单…（不填默认记为「报修人自行取消」）"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="close">再想想</el-button>
      <el-button type="danger" :loading="submitting" @click="onConfirm">确认取消</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { httpClient } from '../../stores/auth';
import type { DispatchOrderItemVO } from '@campus/shared';
import AlertBanner from '../../components/AlertBanner.vue';

/**
 * 报修人自助取消弹窗（学生列表与详情共用）。
 *
 * 抽成组件的理由：两个页面的取消语义必须是**同一句承诺**——原因可选、二次确认、
 * 不可逆文案。各写一份迟早出现「一处说不可恢复、一处没说」。
 *
 * 判定完全不在这里：入口由 VO 的 `canCancel` 决定，端点自己还会做状态与属主校验
 * （`2006` 状态不允许 / `403` 非本人）。前端只负责收集原因与呈现结果。
 */
const props = defineProps<{
  modelValue: boolean;
  /** 只需要展示与端点定位所需的三个字段，所以接受 Partial（列表行与详情对象都能传） */
  order: Partial<DispatchOrderItemVO> | null;
}>();

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void;
  /** 取消成功：由父页面刷新列表/详情（本组件不持有数据） */
  (e: 'cancelled', orderId: number): void;
}>();

const reason = ref('');
const submitting = ref(false);

// 每次打开都清空原因：上一次输入不应该延续到另一张工单
watch(
  () => props.modelValue,
  (visible) => {
    if (visible) reason.value = '';
  }
);

function onVisibleChange(value: boolean) {
  emit('update:modelValue', value);
}

function close() {
  emit('update:modelValue', false);
}

async function onConfirm() {
  const order = props.order;
  if (!order?.id) return;

  // 二次确认（D4：报修人端原因是选填，但取消动作本身不可逆，必须再问一次）
  const confirmed = await ElMessageBox.confirm(
    '取消后不可恢复，如需维修请重新提单。确定取消该工单吗？',
    '再次确认',
    {
      confirmButtonText: '确定取消',
      cancelButtonText: '再想想',
      type: 'warning'
    }
  ).catch(() => false);
  if (!confirmed) return;

  submitting.value = true;
  try {
    const res = await httpClient.post<null>(`/work-orders/${order.id}/cancel-by-reporter`, {
      reason: reason.value.trim() || undefined
    });
    if (res.code === 200) {
      ElMessage.success('工单已取消');
      emit('cancelled', order.id);
      close();
    } else {
      ElMessage.error(res.message || '取消失败');
    }
  } catch (e: any) {
    ElMessage.error(e.response?.data?.message || e.message || '取消失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.cancel-target {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 0 12px;
  font-size: var(--fs-base);
  color: var(--ink);
}
.cancel-target__label {
  color: var(--muted);
}
.cancel-target__value {
  font-weight: var(--fw-medium);
}
.cancel-target__desc {
  color: var(--muted);
}
</style>
