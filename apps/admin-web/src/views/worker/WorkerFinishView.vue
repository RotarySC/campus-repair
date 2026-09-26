<template>
  <div class="finish-container">
    <el-alert
      class="finish-notice"
      type="info"
      :closable="false"
      show-icon
      title="上传现场照片自动压制防伪防篡改时间与单号水印，领用耗材实时校验物理库存。"
    />

    <el-form
      ref="finishFormRef"
      :model="finishForm"
      :rules="finishRules"
      :label-width="isNarrowPhone ? undefined : '110px'"
      :label-position="isNarrowPhone ? 'top' : 'right'"
      class="finish-form"
      @submit.prevent
    >
      <!-- 返回工作台 -->
      <div class="back-row">
        <el-button link type="primary" :icon="ArrowLeft" @click="router.push('/worker/workbench')">
          返回工作台
        </el-button>
      </div>

      <!-- 1. 工单信息摘要卡片 -->
      <el-card shadow="never" class="finish-section">
        <template #header><span class="section-title">工单信息</span></template>
        <el-descriptions :column="isNarrowPhone ? 1 : 2" border>
          <el-descriptions-item label="工单单号">{{ orderInfo.orderNo || `BX-${orderId}` }}</el-descriptions-item>
          <el-descriptions-item label="报修位置">{{ orderInfo.spaceFullPath || '空间位置加载中...' }}</el-descriptions-item>
          <el-descriptions-item label="报修描述" :span="2">{{ orderInfo.description || '现场排查处置' }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 2. 现场完工拍照 (带防伪水印) -->
      <el-card shadow="never" class="finish-section">
        <template #header><span class="section-title">完工照片 (1~3张，自动合成防伪水印)</span></template>
        <el-upload
          v-model:file-list="fileList"
          list-type="picture-card"
          multiple
          accept="image/*"
          :limit="3"
          :on-exceed="() => ElMessage.warning('最多上传 3 张完工照片')"
          :before-upload="beforeImageUpload"
          :http-request="uploadWithWatermark"
          :on-remove="onRemoveImage"
        >
          <el-icon :size="22"><Plus /></el-icon>
        </el-upload>
        <div class="watermark-tip">
          * 拍照或选择图片后，客户端将自动压制包含【工单单号】、【师傅姓名】及【当前时间戳】的高清防篡改水印。
        </div>
      </el-card>

      <!-- 3. 维修处理总结文本框 -->
      <el-card shadow="never" class="finish-section">
        <template #header><span class="section-title">维修总结</span></template>
        <el-form-item label="处理说明" prop="repairSummary">
          <el-input
            v-model="finishForm.repairSummary"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="请详细描述维修处置过程、更换配件及最终测验结果（如：更换冷水角阀，缠绕生料带，闭水打压测试5分钟无渗漏）"
          />
        </el-form-item>
      </el-card>

      <!-- 4. 耗材备件领用核销面板 -->
      <el-card shadow="never" class="finish-section">
        <template #header>
          <div class="consume-header">
            <span class="section-title">物料核销 (若无消耗可不添加)</span>
            <el-button type="primary" size="small" plain :icon="Plus" @click="openMaterialPicker">
              添加耗材
            </el-button>
          </div>
        </template>

        <el-empty
          v-if="selectedParts.length === 0"
          description="本次维修暂未领用库房耗材"
          :image-size="72"
        />

        <div v-else class="part-list">
          <div v-for="(item, index) in selectedParts" :key="item.part.id" class="part-item">
            <div class="part-info">
              <div class="part-name">{{ item.part.partName }}</div>
              <div class="part-meta">
                规格: {{ item.part.specification || '标准' }} |
                单价: ¥{{ item.part.guidePrice.toFixed(2) }} / {{ item.part.unit }}
              </div>
              <div class="part-stock" :class="{ 'low-stock': item.part.stockQuantity <= item.part.safetyStockThreshold }">
                库房现存: {{ item.part.stockQuantity }} {{ item.part.unit }}
                <span v-if="item.consumeQuantity > item.part.stockQuantity" class="over-warning">
                  (已超出库存上限!)
                </span>
              </div>
            </div>

            <div class="part-action">
              <el-input-number
                v-model="item.consumeQuantity"
                :min="1"
                :max="item.part.stockQuantity"
                :step-strictly="true"
                size="small"
              />
              <el-button type="danger" :icon="Delete" circle size="small" plain @click="removePart(index)" />
            </div>
          </div>
        </div>

        <div class="cost-summary">
          <span>直接耗材总金额：</span>
          <span class="cost-amount">¥ {{ totalMaterialCost.toFixed(2) }}</span>
        </div>
      </el-card>

      <!-- 5. 提交按钮 -->
      <div class="submit-row">
        <el-button
          type="primary"
          size="large"
          class="submit-btn"
          :loading="submitting"
          @click="onSubmitFinish"
        >
          {{ submitting ? '正在核销出库并提交...' : '确认完工并提交验收' }}
        </el-button>
      </div>
    </el-form>

    <!-- 耗材选择面板弹窗 -->
    <el-dialog
      v-model="showMaterialPopup"
      title="选择库房耗材备件"
      width="min(560px, 94vw)"
      top="6vh"
      :close-on-click-modal="false"
    >
      <el-input
        v-model="searchKeyword"
        placeholder="搜索物料名称 / 编号 / 规格"
        clearable
        :prefix-icon="Search"
        @keyup.enter="searchParts"
        @clear="searchParts"
      >
        <template #append>
          <el-button :icon="Search" @click="searchParts">搜索</el-button>
        </template>
      </el-input>

      <div v-loading="loadingParts" class="picker-list">
        <el-empty v-if="!loadingParts && availableParts.length === 0" description="未找到匹配的在库耗材" />
        <div
          v-for="part in availableParts"
          :key="part.id"
          class="picker-item"
          :class="{ 'disabled-part': part.stockQuantity <= 0 }"
          @click="selectPart(part)"
        >
          <div class="picker-item-info">
            <div class="picker-part-name">
              {{ part.partName }}
              <el-tag v-if="part.stockQuantity <= 0" type="danger" size="small">缺货</el-tag>
              <el-tag v-else-if="part.isLowStock" type="warning" size="small">紧缺</el-tag>
            </div>
            <div class="picker-part-sub">
              编号: {{ part.partCode }} | 规格: {{ part.specification || '通用' }}
            </div>
            <div class="picker-part-bottom">
              <span class="picker-price">¥{{ part.guidePrice.toFixed(2) }} / {{ part.unit }}</span>
              <span class="picker-stock">当前库存: {{ part.stockQuantity }} {{ part.unit }}</span>
            </div>
          </div>
          <el-icon :size="18" color="#1677FF" v-if="part.stockQuantity > 0"><Plus /></el-icon>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules, type UploadFile, type UploadRequestOptions } from 'element-plus';
import { ArrowLeft, Delete, Plus, Search } from '@element-plus/icons-vue';
import { useAuthStore, httpClient } from '../../stores/auth';
import { useViewport } from '../../composables/useViewport';
import {
  DispatchOrderItemVO,
  SparePartVO,
  WorkOrderFinishRequest,
  addWatermarkToImage
} from '@campus/shared';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const { isNarrowPhone } = useViewport();

const orderId = Number(route.params.id);
const orderInfo = ref<Partial<DispatchOrderItemVO>>({});
const finishFormRef = ref<FormInstance>();
const finishForm = ref({ repairSummary: '' });
const finishRules: FormRules = {
  repairSummary: [{ required: true, message: '请填写现场维修总结', trigger: 'blur' }]
};

const fileList = ref<UploadFile[]>([]);
const uploadedImageUrls = ref<string[]>([]);
const submitting = ref(false);

// 耗材核销
interface SelectedPartItem {
  part: SparePartVO;
  consumeQuantity: number;
}
const selectedParts = ref<SelectedPartItem[]>([]);
const showMaterialPopup = ref(false);
const searchKeyword = ref('');
const loadingParts = ref(false);
const availableParts = ref<SparePartVO[]>([]);

// 耗材总成本计算
const totalMaterialCost = computed(() => {
  return selectedParts.value.reduce((sum, item) => {
    return sum + (item.part.guidePrice || 0) * (item.consumeQuantity || 0);
  }, 0);
});

onMounted(() => {
  fetchOrderDetail();
});

async function fetchOrderDetail() {
  try {
    const res = await httpClient.get<DispatchOrderItemVO>(`/work-orders/${orderId}`);
    if (res.code === 200 && res.data) {
      orderInfo.value = res.data;
    }
  } catch (e) {
    // 允许通过
  }
}

// 现场完工照片上传与防伪水印处理（拦截默认上传，改为「压水印 → 传后端」两步）
type UploadError = Parameters<UploadRequestOptions['onError']>[0];
function makeUploadError(message: string): UploadError {
  return Object.assign(new Error(message), { status: 0, method: 'POST', url: '' }) as UploadError;
}

function beforeImageUpload(file: File) {
  if (!file.type.startsWith('image/')) {
    ElMessage.error('仅支持图片文件');
    return false;
  }
  if (fileList.value.length >= 3) {
    ElMessage.warning('最多上传 3 张完工照片');
    return false;
  }
  return true;
}

async function uploadWithWatermark(options: UploadRequestOptions) {
  const file = options.file as File;
  const workerName = authStore.userInfo?.realName || '作业师傅';
  const orderNo = orderInfo.value.orderNo || `BX-${orderId}`;

  try {
    // 1. 调用 Canvas 防伪水印工具压制时间戳、师傅与单号
    const watermarkedBlob = await addWatermarkToImage(file, {
      orderNo,
      workerName,
      maxDimension: 1600,
      quality: 0.85
    });

    // 2. 上传至后端统一文件服务端点（相对 baseURL /api/v1，勿写绝对路径以免被拦截器二次拼接）
    const formData = new FormData();
    formData.append('file', watermarkedBlob, file.name || 'finish_watermarked.jpg');

    const res = await httpClient.post<{ url: string }>('/common/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });

    if (res.code === 200 && res.data?.url) {
      uploadedImageUrls.value.push(res.data.url);
      options.onSuccess(res.data);
    } else {
      options.onError(makeUploadError(res.message || '上传失败'));
      ElMessage.error('照片上传失败');
    }
  } catch (err: any) {
    options.onError(makeUploadError(err?.message || '水印生成或上传失败'));
    ElMessage.error(err?.message || '水印生成或上传失败');
  }
}

function onRemoveImage(file: UploadFile) {
  const url = (file.response as { url?: string } | undefined)?.url;
  if (url) {
    const idx = uploadedImageUrls.value.indexOf(url);
    if (idx >= 0) uploadedImageUrls.value.splice(idx, 1);
  }
}

// 耗材选择面板
function openMaterialPicker() {
  showMaterialPopup.value = true;
  searchParts();
}

async function searchParts() {
  loadingParts.value = true;
  try {
    const res = await httpClient.get<{ records: SparePartVO[] }>('/inventory/parts', {
      params: {
        keyword: searchKeyword.value.trim(),
        page: 1,
        size: 50
      }
    });
    if (res.code === 200 && res.data) {
      availableParts.value = res.data.records || [];
    }
  } catch (e) {
    ElMessage.error('获取耗材台账失败');
  } finally {
    loadingParts.value = false;
  }
}

function selectPart(part: SparePartVO) {
  if (part.stockQuantity <= 0) {
    ElMessage.warning('该耗材当前库存为0，无法领用');
    return;
  }
  const existing = selectedParts.value.find(p => p.part.id === part.id);
  if (existing) {
    if (existing.consumeQuantity < part.stockQuantity) {
      existing.consumeQuantity += 1;
    } else {
      ElMessage.warning(`已达该耗材库存上限 (${part.stockQuantity}${part.unit})`);
    }
  } else {
    selectedParts.value.push({
      part,
      consumeQuantity: 1
    });
  }
  showMaterialPopup.value = false;
  ElMessage.success(`已选入: ${part.partName}`);
}

function removePart(index: number) {
  selectedParts.value.splice(index, 1);
}

// 提交完工
async function onSubmitFinish() {
  const valid = await finishFormRef.value?.validate().catch(() => false);
  if (!valid) return;

  if (uploadedImageUrls.value.length === 0) {
    ElMessage.warning('请至少拍摄或上传1张现场完工带水印照片');
    return;
  }

  // 检查是否超出库存
  for (const item of selectedParts.value) {
    if (item.consumeQuantity > item.part.stockQuantity) {
      ElMessage.warning(`物料 [${item.part.partName}] 领用量超过当前物理库存！`);
      return;
    }
  }

  submitting.value = true;
  try {
    const payload: WorkOrderFinishRequest = {
      repairSummary: finishForm.value.repairSummary.trim(),
      repairImages: uploadedImageUrls.value,
      materialConsumptions: selectedParts.value.map(item => ({
        partId: item.part.id,
        consumeQuantity: item.consumeQuantity
      }))
    };

    const res = await httpClient.post<void>(`/work-orders/${orderId}/finish`, payload);
    if (res.code === 200) {
      ElMessage.success('完工上报成功，已流转待验收');
      setTimeout(() => {
        router.push('/worker/workbench');
      }, 1000);
    } else {
      ElMessage.error(res.message || '完工填报失败');
    }
  } catch (err: any) {
    ElMessage.error(err.message || '网络请求异常');
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
/*
 * 视觉语言与其余页面一致（口径见 docs/20260906-1430-…-frontend-design.md §3.3）：
 * 根节点 16px 内边距；分段用 el-card shadow="never" + #header（同工单详情页 .block）；
 * 表单列限宽但**左对齐**——页面级不做 margin-inline:auto 居中；
 * 颜色只用设计文档 §3.1 令牌与品牌主色 #1677FF。
 */
.finish-container {
  padding: 16px;
}

.finish-notice {
  margin-bottom: 14px;
}

.back-row {
  margin-bottom: 10px;
}

/* 表单主体限宽：手机档自然满宽，桌面档不无限拉伸（与提单页 .submit-form 同法） */
.finish-form {
  max-width: 760px;
}

.finish-section {
  margin-bottom: 14px;
}

.section-title {
  font-weight: 600;
}

.watermark-tip {
  font-size: 12px;
  color: #9CA3AF;
  line-height: 1.5;
  margin-top: 8px;
}

.consume-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.part-list {
  display: flex;
  flex-direction: column;
}

.part-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 0;
  border-bottom: 1px solid #E5E7EB;
}

.part-info {
  flex: 1;
  min-width: 220px;
}

.part-name {
  font-size: 14px;
  font-weight: 600;
  color: #1F2937;
}

.part-meta {
  font-size: 12px;
  color: #4B5563;
  margin-top: 4px;
}

.part-stock {
  font-size: 12px;
  color: #52C41A;
  margin-top: 2px;
}

.part-stock.low-stock {
  color: #FA8C16;
}

.over-warning {
  color: #F5222D;
  font-weight: 600;
}

.part-action {
  display: flex;
  align-items: center;
  gap: 12px;
}

.cost-summary {
  display: flex;
  justify-content: flex-end;
  align-items: baseline;
  padding: 12px 0 0;
  font-size: 13px;
  color: #4B5563;
}

.cost-amount {
  font-size: 16px;
  font-weight: bold;
  color: #F5222D;
  margin-left: 4px;
}

.submit-row {
  margin: 20px 0 8px;
}

.submit-btn {
  min-height: 44px;
}

/* 窄档：提交按钮撑满，现场单手可点 */
@media (max-width: 767px) {
  .submit-btn {
    width: 100%;
  }
}

/* 耗材选择弹窗列表 */
.picker-list {
  max-height: 56vh;
  overflow-y: auto;
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.picker-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 12px;
  border-radius: 8px;
  background-color: #FFFFFF;
  border: 1px solid #E5E7EB;
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.picker-item:hover {
  border-color: #1677FF;
}

.picker-item.disabled-part {
  opacity: 0.5;
  background-color: #F9FAFB;
  cursor: not-allowed;
}

.picker-part-name {
  font-size: 14px;
  font-weight: 600;
  color: #1F2937;
  display: flex;
  align-items: center;
  gap: 6px;
}

.picker-part-sub {
  font-size: 12px;
  color: #9CA3AF;
  margin-top: 4px;
}

.picker-part-bottom {
  display: flex;
  gap: 16px;
  margin-top: 6px;
  font-size: 12px;
}

.picker-price {
  color: #F5222D;
  font-weight: 600;
}

.picker-stock {
  color: #1677FF;
}
</style>
