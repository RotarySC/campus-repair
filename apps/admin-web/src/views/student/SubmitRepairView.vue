<template>
  <div class="submit-repair-page">
    <van-nav-bar title="极速报修提单" left-text="返回主页" left-arrow @click-left="router.push('/')" />

    <!-- 防丢草稿恢复提示条 -->
    <van-notice-bar
      v-if="hasDraft"
      mode="closeable"
      left-icon="info-o"
      text="检测到您有未提交的报修草稿，已为您自动恢复表单内容。"
      @close="hasDraft = false"
    />

    <!-- 同位置同分类未结单防重预警条 -->
    <div v-if="duplicateTip" class="duplicate-alert">
      <van-icon name="warning" color="#FAAD14" size="18" />
      <div class="tip-content">
        <span class="tip-text">{{ duplicateTip }}</span>
      </div>
    </div>

    <van-form @submit="onSubmit" class="submit-form">
      <!-- 报修空间位置 -->
      <van-cell-group inset title="报修故障位置">
        <van-cell
          is-link
          title="空间节点"
          :value="selectedSpaceName || '点击选择校区/楼栋/房间'"
          @click="showSpaceCascader = true"
        />
      </van-cell-group>

      <!-- 故障分类选择 -->
      <van-cell-group inset title="故障类型与工种">
        <van-cell
          is-link
          title="故障细类"
          :value="selectedCategoryName || '点击选择分类与工种'"
          @click="showCategoryCascader = true"
        />
      </van-cell-group>

      <!-- 故障说明与图片 -->
      <van-cell-group inset title="现场详情与凭证">
        <van-field
          v-model="form.description"
          rows="3"
          autosize
          label="现场情况"
          type="textarea"
          maxlength="300"
          placeholder="请详细描述故障现象（如爆水管、断闸跳闸、插座松动等）"
          show-word-limit
          :rules="[{ required: true, message: '请描述故障详情' }]"
          @update:model-value="saveDraft"
        />

        <van-cell title="现场损坏照片 (1~3张，自动压缩)">
          <template #value>
            <van-uploader
              v-model="fileList"
              multiple
              :max-count="3"
              :after-read="afterReadImage"
            />
          </template>
        </van-cell>
      </van-cell-group>

      <!-- 预约时段与紧急度 -->
      <van-cell-group inset title="预约与紧急程度">
        <van-field name="urgencyLevel" label="加急处理">
          <template #input>
            <van-switch v-model="isUrgent" size="22" active-color="#ee0a24" />
          </template>
        </van-field>

        <van-field label="期望上门时段">
          <template #input>
            <van-radio-group v-model="form.appointmentPeriod" direction="horizontal" @change="saveDraft">
              <van-radio name="上午08:30-11:30">上午</van-radio>
              <van-radio name="下午14:00-17:30">下午</van-radio>
              <van-radio name="晚间18:30-21:00">晚间</van-radio>
              <van-radio name="周末全天">周末</van-radio>
            </van-radio-group>
          </template>
        </van-field>

        <van-field
          v-model="form.applicantPhone"
          type="tel"
          label="联系电话"
          placeholder="留空默认使用个人中心手机号"
          @update:model-value="saveDraft"
        />
      </van-cell-group>

      <div style="margin: 24px 16px;">
        <van-button round block type="primary" native-type="submit" :loading="submitting" size="large">
          确认提交报修单
        </van-button>
      </div>
    </van-form>

    <!-- 空间级联 Popup -->
    <van-popup v-model:show="showSpaceCascader" round position="bottom">
      <van-cascader
        v-model="cascaderSpaceValue"
        title="选择故障发生空间"
        :options="spaceCascaderOptions"
        @close="showSpaceCascader = false"
        @finish="onSpaceFinish"
      />
    </van-popup>

    <!-- 分类级联 Popup -->
    <van-popup v-model:show="showCategoryCascader" round position="bottom">
      <van-cascader
        v-model="cascaderCategoryValue"
        title="选择报修故障类型"
        :options="categoryCascaderOptions"
        @close="showCategoryCascader = false"
        @finish="onCategoryFinish"
      />
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { showToast, showNotify, showDialog } from 'vant';
import { useAuthStore, httpClient } from '../../stores/auth';
import { compressImage } from '@campus/shared';

const router = useRouter();
const authStore = useAuthStore();

const submitting = ref(false);
const hasDraft = ref(false);
const duplicateTip = ref('');

const showSpaceCascader = ref(false);
const showCategoryCascader = ref(false);
const cascaderSpaceValue = ref('');
const cascaderCategoryValue = ref('');

const selectedSpaceName = ref('');
const selectedCategoryName = ref('');

const spaceCascaderOptions = ref<any[]>([]);
const categoryCascaderOptions = ref<any[]>([]);
const fileList = ref<any[]>([]);
const uploadedUrls = ref<string[]>([]);

const isUrgent = ref(false);

const form = reactive({
  spaceId: null as number | null,
  categoryId: null as number | null,
  description: '',
  appointmentPeriod: '上午08:30-11:30',
  applicantPhone: ''
});

const DRAFT_STORAGE_KEY = 'CAMPUS_REPAIR_SUBMIT_DRAFT';

function saveDraft() {
  const draft = {
    spaceId: form.spaceId,
    selectedSpaceName: selectedSpaceName.value,
    categoryId: form.categoryId,
    selectedCategoryName: selectedCategoryName.value,
    description: form.description,
    appointmentPeriod: form.appointmentPeriod,
    applicantPhone: form.applicantPhone,
    isUrgent: isUrgent.value
  };
  localStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify(draft));
}

function restoreDraft() {
  const cached = localStorage.getItem(DRAFT_STORAGE_KEY);
  if (cached) {
    try {
      const data = JSON.parse(cached);
      if (data.description || data.spaceId || data.categoryId) {
        form.spaceId = data.spaceId;
        selectedSpaceName.value = data.selectedSpaceName || '';
        form.categoryId = data.categoryId;
        selectedCategoryName.value = data.selectedCategoryName || '';
        form.description = data.description || '';
        form.appointmentPeriod = data.appointmentPeriod || '上午08:30-11:30';
        form.applicantPhone = data.applicantPhone || '';
        isUrgent.value = !!data.isUrgent;
        hasDraft.value = true;
        // 恢复草稿后立即联动触发防重检测
        triggerDuplicateCheck();
      }
    } catch (_) {}
  }
}

function transformTreeToCascader(nodes: any[], isCategory = false): any[] {
  return nodes.map((node) => ({
    text: isCategory && node.workerSkillCode ? `${node.name} (${node.workerSkillCode})` : node.name,
    value: node.id,
    children: node.children && node.children.length > 0 ? transformTreeToCascader(node.children, isCategory) : undefined
  }));
}

async function loadSpaceOptions() {
  try {
    const res = await httpClient.get<any[]>('/spatial/tree', {
      params: { onlyEnabled: true }
    });
    if (res.code === 200) {
      spaceCascaderOptions.value = transformTreeToCascader(res.data);
    }
  } catch (_) {}
}

async function loadCategoryOptions() {
  try {
    const res = await httpClient.get<any[]>('/categories/tree', {
      params: { onlyEnabled: true }
    });
    if (res.code === 200) {
      categoryCascaderOptions.value = transformTreeToCascader(res.data, true);
    }
  } catch (_) {}
}

function onSpaceFinish({ selectedOptions }: any) {
  showSpaceCascader.value = false;
  const leaf = selectedOptions[selectedOptions.length - 1];
  form.spaceId = leaf.value;
  selectedSpaceName.value = selectedOptions.map((o: any) => o.text).join(' / ');
  saveDraft();
  triggerDuplicateCheck();
}

function onCategoryFinish({ selectedOptions }: any) {
  showCategoryCascader.value = false;
  const leaf = selectedOptions[selectedOptions.length - 1];
  form.categoryId = leaf.value;
  selectedCategoryName.value = selectedOptions.map((o: any) => o.text).join(' / ');
  saveDraft();
  triggerDuplicateCheck();
}

async function triggerDuplicateCheck() {
  if (!form.spaceId || !form.categoryId) {
    duplicateTip.value = '';
    return;
  }
  try {
    const res = await httpClient.post<any>('/work-orders/check-duplicate', {
      spaceId: form.spaceId,
      categoryId: form.categoryId
    });
    if (res.code === 200 && res.data.hasActiveOrder) {
      duplicateTip.value = res.data.tipMessage;
    } else {
      duplicateTip.value = '';
    }
  } catch (_) {}
}

async function afterReadImage(fileItem: any) {
  const file: File = fileItem.file;
  fileItem.status = 'uploading';
  fileItem.message = '压缩上传中...';

  try {
    // 客户端 Canvas 压缩
    const compressedBlob = await compressImage(file, { maxDimension: 1600, quality: 0.8 });
    const compressedFile = new File([compressedBlob], file.name, { type: 'image/jpeg' });

    const formData = new FormData();
    formData.append('file', compressedFile);

    const res = await httpClient.post<{ url: string }>('/common/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });

    if (res.code === 200) {
      fileItem.status = 'done';
      fileItem.url = res.data.url;
      uploadedUrls.value.push(res.data.url);
    } else {
      fileItem.status = 'failed';
      fileItem.message = '上传失败';
    }
  } catch (err: any) {
    fileItem.status = 'failed';
    fileItem.message = '压缩失败';
  }
}

async function onSubmit() {
  if (!form.spaceId) {
    showToast('请选择报修具体空间位置');
    return;
  }
  if (!form.categoryId) {
    showToast('请选择故障分类');
    return;
  }

  submitting.value = true;
  try {
    const payload = {
      spaceId: form.spaceId,
      categoryId: form.categoryId,
      description: form.description,
      appointmentPeriod: form.appointmentPeriod,
      urgencyLevel: isUrgent.value ? 2 : 1,
      applicantPhone: form.applicantPhone || undefined,
      faultImages: uploadedUrls.value
    };

    const res = await httpClient.post<any>('/work-orders/submit', payload);
    if (res.code === 200) {
      localStorage.removeItem(DRAFT_STORAGE_KEY);
      showDialog({
        title: '报修提交成功',
        message: `您的报修工单已受理！工单编号：${res.data.orderNo}，系统正在为您自动调度匹配师傅。`
      }).then(() => {
        router.push('/');
      });
    } else {
      showNotify({ type: 'danger', message: res.message || '提单失败' });
    }
  } catch (err: any) {
    showNotify({
      type: 'danger',
      message: err.response?.data?.message || err.message || '提交异常'
    });
  } finally {
    submitting.value = false;
  }
}

onMounted(async () => {
  await loadSpaceOptions();
  await loadCategoryOptions();
  restoreDraft();

  // 若无草稿且用户绑定了默认空间，自动带出默认空间
  if (!form.spaceId && authStore.userInfo?.defaultSpaceId) {
    const defaultId = authStore.userInfo.defaultSpaceId;
    form.spaceId = defaultId;
    // 递归查找匹配节点名称全路径
    const findPath = (list: any[], id: number, prefix = ''): string | null => {
      for (const node of list) {
        const curPath = prefix ? `${prefix} / ${node.text}` : node.text;
        if (node.value === id) return curPath;
        if (node.children) {
          const res = findPath(node.children, id, curPath);
          if (res) return res;
        }
      }
      return null;
    };
    const foundPath = findPath(spaceCascaderOptions.value, defaultId);
    if (foundPath) {
      selectedSpaceName.value = foundPath;
    }
  }

  if (authStore.userInfo?.phone && !form.applicantPhone) {
    form.applicantPhone = authStore.userInfo.phone;
  }
});
</script>

<style scoped>
.submit-repair-page {
  min-height: 100vh;
  background-color: #F7F8FA;
  padding-bottom: 24px;
}
.duplicate-alert {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #FFFBE6;
  border: 1px solid #FFE58F;
  border-radius: 8px;
  padding: 10px 16px;
  margin: 12px 16px 0 16px;
}
.tip-text {
  font-size: 13px;
  color: #D46B08;
  line-height: 1.4;
}
.submit-form {
  margin-top: 8px;
}
</style>
