<template>
  <div class="finish-container">
    <!-- 顶部导航栏 -->
    <van-nav-bar
      title="现场完工填报"
      left-arrow
      @click-left="router.back()"
      fixed
      placeholder
    />

    <van-notice-bar
      left-icon="info-o"
      text="上传现场照片自动压制防伪防篡改时间与单号水印，领用耗材实时校验物理库存。"
    />

    <van-form @submit="onSubmitFinish" class="finish-form">
      <!-- 1. 工单信息摘要卡片 -->
      <van-cell-group inset title="工单信息">
        <van-cell title="工单单号" :value="orderInfo.orderNo || `BX-${orderId}`" />
        <van-cell title="报修位置" :label="orderInfo.spaceFullPath || '空间位置加载中...'" />
        <van-cell title="报修描述" :label="orderInfo.description || '现场排查处置'" />
      </van-cell-group>

      <!-- 2. 现场完工拍照 (带防伪水印) -->
      <van-cell-group inset title="完工照片 (1~3张，自动合成防伪水印)" style="margin-top: 12px;">
        <div style="padding: 12px 16px;">
          <van-uploader
            v-model="fileList"
            multiple
            :max-count="3"
            capture="camera"
            :before-read="beforeReadImage"
            :after-read="afterReadImage"
            @delete="onDeleteImage"
          />
          <div class="watermark-tip">
            * 拍照或选择图片后，客户端将自动压制包含【工单单号】、【师傅姓名】及【当前时间戳】的高清防篡改水印。
          </div>
        </div>
      </van-cell-group>

      <!-- 3. 维修处理总结文本框 -->
      <van-cell-group inset title="维修总结" style="margin-top: 12px;">
        <van-field
          v-model="repairSummary"
          rows="3"
          autosize
          label="处理说明"
          type="textarea"
          maxlength="500"
          placeholder="请详细描述维修处置过程、更换配件及最终测验结果（如：更换冷水角阀，缠绕生料带，闭水打压测试5分钟无渗漏）"
          show-word-limit
          :rules="[{ required: true, message: '请填写现场维修总结' }]"
        />
      </van-cell-group>

      <!-- 4. 耗材备件领用核销面板 -->
      <van-cell-group inset title="物料核销 (若无消耗可不添加)" style="margin-top: 12px;">
        <div class="consume-header">
          <span class="consume-title">已选领料清单</span>
          <van-button
            type="primary"
            size="small"
            plain
            round
            icon="plus"
            @click="openMaterialPicker"
          >
            添加耗材
          </van-button>
        </div>

        <div v-if="selectedParts.length === 0" class="empty-material">
          <van-empty image="search" description="本次维修暂未领用库房耗材" />
        </div>

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
              <van-stepper
                v-model="item.consumeQuantity"
                :min="1"
                :max="item.part.stockQuantity"
                integer
              />
              <van-icon
                name="delete-o"
                color="#ee0a24"
                size="20"
                style="margin-left: 12px;"
                @click="removePart(index)"
              />
            </div>
          </div>
        </div>

        <div class="cost-summary">
          <span>直接耗材总金额：</span>
          <span class="cost-amount">¥ {{ totalMaterialCost.toFixed(2) }}</span>
        </div>
      </van-cell-group>

      <!-- 5. 提交按钮 -->
      <div style="margin: 24px 16px 40px;">
        <van-button
          round
          block
          type="primary"
          native-type="submit"
          :loading="submitting"
          loading-text="正在核销出库并提交..."
        >
          确认完工并提交验收
        </van-button>
      </div>
    </van-form>

    <!-- 耗材选择面板弹窗 -->
    <van-popup
      v-model:show="showMaterialPopup"
      position="bottom"
      round
      :style="{ height: '75%' }"
      closeable
    >
      <div class="picker-popup">
        <div class="picker-title">选择库房耗材备件</div>
        <van-search
          v-model="searchKeyword"
          placeholder="搜索物料名称 / 编号 / 规格"
          @search="searchParts"
          @clear="searchParts"
        />

        <div class="picker-list">
          <van-loading v-if="loadingParts" size="24px" vertical>加载耗材台账中...</van-loading>
          <div v-else-if="availableParts.length === 0" class="empty-result">
            <van-empty description="未找到匹配的在库耗材" />
          </div>
          <div
            v-else
            v-for="part in availableParts"
            :key="part.id"
            class="picker-item"
            :class="{ 'disabled-part': part.stockQuantity <= 0 }"
            @click="selectPart(part)"
          >
            <div class="picker-item-info">
              <div class="picker-part-name">
                {{ part.partName }}
                <van-tag v-if="part.stockQuantity <= 0" type="danger">缺货</van-tag>
                <van-tag v-else-if="part.isLowStock" type="warning">紧缺</van-tag>
              </div>
              <div class="picker-part-sub">
                编号: {{ part.partCode }} | 规格: {{ part.specification || '通用' }}
              </div>
              <div class="picker-part-bottom">
                <span class="picker-price">¥{{ part.guidePrice.toFixed(2) }} / {{ part.unit }}</span>
                <span class="picker-stock">当前库存: {{ part.stockQuantity }} {{ part.unit }}</span>
              </div>
            </div>
            <van-icon name="plus" size="18" color="#1989fa" v-if="part.stockQuantity > 0" />
          </div>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showToast, showSuccessToast, showFailToast, UploaderFileListItem } from 'vant';
import { useAuthStore, httpClient } from '../../stores/auth';
import {
  DispatchOrderItemVO,
  SparePartVO,
  WorkOrderFinishRequest,
  addWatermarkToImage
} from '@campus/shared';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const orderId = Number(route.params.id);
const orderInfo = ref<Partial<DispatchOrderItemVO>>({});
const repairSummary = ref('');
const fileList = ref<UploaderFileListItem[]>([]);
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
    const res = await httpClient.get<DispatchOrderItemVO>(`/api/v1/work-orders/${orderId}`);
    if (res.code === 200 && res.data) {
      orderInfo.value = res.data;
    }
  } catch (e) {
    // 允许通过
  }
}

// 现场完工照片上传与防伪水印处理
function beforeReadImage(file: File | File[]) {
  if (Array.isArray(file)) {
    return file.every(f => f.type.startsWith('image/'));
  }
  return file.type.startsWith('image/');
}

async function afterReadImage(item: any) {
  item.status = 'uploading';
  item.message = '压制防伪水印...';

  try {
    const file = item.file as File;
    const workerName = authStore.userInfo?.realName || '作业师傅';
    const orderNo = orderInfo.value.orderNo || `BX-${orderId}`;

    // 1. 调用 Canvas 防伪水印工具压制时间戳、师傅与单号
    const watermarkedBlob = await addWatermarkToImage(file, {
      orderNo,
      workerName,
      maxDimension: 1600,
      quality: 0.85
    });

    item.message = '上传中...';

    // 2. 上传至后端统一文件服务端点（相对 baseURL /api/v1，勿写绝对路径以免被拦截器二次拼接）
    const formData = new FormData();
    formData.append('file', watermarkedBlob, file.name || 'finish_watermarked.jpg');

    const res = await httpClient.post<{ url: string }>('/common/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });

    if (res.code === 200 && res.data?.url) {
      item.status = 'done';
      item.url = res.data.url;
      uploadedImageUrls.value.push(res.data.url);
    } else {
      item.status = 'failed';
      item.message = '上传失败';
      showFailToast('照片上传失败');
    }
  } catch (err: any) {
    item.status = 'failed';
    item.message = '处理失败';
    showFailToast(err.message || '水印生成或上传失败');
  }
}

function onDeleteImage(item: any, detail: { index: number }) {
  uploadedImageUrls.value.splice(detail.index, 1);
}

// 耗材选择面板
function openMaterialPicker() {
  showMaterialPopup.value = true;
  searchParts();
}

async function searchParts() {
  loadingParts.value = true;
  try {
    const res = await httpClient.get<{ records: SparePartVO[] }>('/api/v1/inventory/parts', {
      params: {
        keyword: searchKeyword.value.trim(),
        page: 1,
        size: 50
      }
    });
    if (res.code === 200 && res.data) {
      availableParts.value = res.data.records || [];
    }
  } catch (e: any) {
    showFailToast('获取耗材台账失败');
  } finally {
    loadingParts.value = false;
  }
}

function selectPart(part: SparePartVO) {
  if (part.stockQuantity <= 0) {
    showToast('该耗材当前库存为0，无法领用');
    return;
  }
  const existing = selectedParts.value.find(p => p.part.id === part.id);
  if (existing) {
    if (existing.consumeQuantity < part.stockQuantity) {
      existing.consumeQuantity += 1;
    } else {
      showToast(`已达该耗材库存上限 (${part.stockQuantity}${part.unit})`);
    }
  } else {
    selectedParts.value.push({
      part,
      consumeQuantity: 1
    });
  }
  showMaterialPopup.value = false;
  showSuccessToast(`已选入: ${part.partName}`);
}

function removePart(index: number) {
  selectedParts.value.splice(index, 1);
}

// 提交完工
async function onSubmitFinish() {
  if (uploadedImageUrls.value.length === 0) {
    showToast('请至少拍摄或上传1张现场完工带水印照片');
    return;
  }

  // 检查是否超出库存
  for (const item of selectedParts.value) {
    if (item.consumeQuantity > item.part.stockQuantity) {
      showToast(`物料 [${item.part.partName}] 领用量超过当前物理库存！`);
      return;
    }
  }

  submitting.value = true;
  try {
    const payload: WorkOrderFinishRequest = {
      repairSummary: repairSummary.value.trim(),
      repairImages: uploadedImageUrls.value,
      materialConsumptions: selectedParts.value.map(item => ({
        partId: item.part.id,
        consumeQuantity: item.consumeQuantity
      }))
    };

    const res = await httpClient.post<void>(`/api/v1/work-orders/${orderId}/finish`, payload);
    if (res.code === 200) {
      showSuccessToast('完工上报成功，已流转待验收');
      setTimeout(() => {
        router.push('/worker/workbench');
      }, 1000);
    } else {
      showFailToast(res.message || '完工填报失败');
    }
  } catch (err: any) {
    showFailToast(err.message || '网络请求异常');
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.finish-container {
  min-height: 100vh;
  background-color: #f7f8fa;
  padding-bottom: 24px;
}

.finish-form {
  margin-top: 8px;
}

.watermark-tip {
  font-size: 12px;
  color: #969799;
  line-height: 1.5;
  margin-top: 8px;
}

.consume-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px 8px;
}

.consume-title {
  font-size: 14px;
  font-weight: 600;
  color: #323233;
}

.empty-material {
  padding: 8px 0;
}

.part-list {
  padding: 0 16px;
}

.part-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid #ebedf0;
}

.part-info {
  flex: 1;
}

.part-name {
  font-size: 14px;
  font-weight: 600;
  color: #323233;
}

.part-meta {
  font-size: 12px;
  color: #646566;
  margin-top: 4px;
}

.part-stock {
  font-size: 12px;
  color: #07c160;
  margin-top: 2px;
}

.part-stock.low-stock {
  color: #ed6a0c;
}

.over-warning {
  color: #ee0a24;
  font-weight: 600;
}

.part-action {
  display: flex;
  align-items: center;
}

.cost-summary {
  display: flex;
  justify-content: flex-end;
  align-items: baseline;
  padding: 12px 16px;
  font-size: 13px;
  color: #646566;
  background-color: #fafafa;
}

.cost-amount {
  font-size: 16px;
  font-weight: bold;
  color: #ee0a24;
  margin-left: 4px;
}

/* 耗材弹出层 */
.picker-popup {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.picker-title {
  font-size: 16px;
  font-weight: bold;
  text-align: center;
  padding: 16px;
  color: #323233;
}

.picker-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 16px 20px;
}

.picker-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  border-radius: 8px;
  background-color: #fff;
  border: 1px solid #ebedf0;
  margin-bottom: 8px;
  cursor: pointer;
}

.picker-item.disabled-part {
  opacity: 0.5;
  background-color: #f7f8fa;
  cursor: not-allowed;
}

.picker-part-name {
  font-size: 14px;
  font-weight: 600;
  color: #323233;
  display: flex;
  align-items: center;
  gap: 6px;
}

.picker-part-sub {
  font-size: 12px;
  color: #969799;
  margin-top: 4px;
}

.picker-part-bottom {
  display: flex;
  gap: 16px;
  margin-top: 6px;
  font-size: 12px;
}

.picker-price {
  color: #ee0a24;
  font-weight: 600;
}

.picker-stock {
  color: #1989fa;
}

.empty-result {
  padding-top: 30px;
}
</style>
