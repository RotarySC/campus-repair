<template>
  <div class="student-submit-page">
    <el-alert
      v-if="hasDraft"
      type="info"
      show-icon
      closable
      title="检测到您有未提交的报修草稿，已为您自动恢复表单内容。"
      style="margin-bottom: 14px"
      @close="hasDraft = false"
    />

    <el-alert
      v-if="duplicateTip"
      type="warning"
      show-icon
      :title="duplicateTip"
      style="margin-bottom: 14px"
      @close="duplicateTip = ''"
    />

    <el-card shadow="never">
      <template #header>
        <span class="card-title">极速报修提单</span>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" class="submit-form">
        <el-divider content-position="left">报修故障位置</el-divider>
        <el-form-item label="空间节点" prop="spaceId">
          <el-cascader
            v-model="spacePath"
            :options="spaceOptions"
            :props="cascaderProps"
            placeholder="请选择校区/楼栋/房间"
            style="width: 420px"
            @change="onSpaceChange"
          />
          <span v-if="spaceFullPathName" class="path-hint">{{ spaceFullPathName }}</span>
        </el-form-item>

        <el-divider content-position="left">故障类型与工种</el-divider>
        <el-form-item label="故障细类" prop="categoryId">
          <el-cascader
            v-model="categoryPath"
            :options="categoryOptions"
            :props="cascaderProps"
            placeholder="请选择分类与对应工种"
            style="width: 420px"
            @change="onCategoryChange"
          />
          <span v-if="categoryFullPathName" class="path-hint">{{ categoryFullPathName }}</span>
        </el-form-item>

        <el-divider content-position="left">现场详情与凭证</el-divider>
        <el-form-item label="现场情况" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="4"
            maxlength="300"
            show-word-limit
            placeholder="请详细描述故障现象（如爆水管、断闸跳闸、插座松动等）"
            style="width: 560px"
            @input="saveDraft"
          />
        </el-form-item>

        <el-form-item label="损坏照片">
          <el-upload
            v-model:file-list="fileList"
            list-type="picture-card"
            accept="image/*"
            :limit="3"
            :http-request="uploadImage"
            :on-exceed="() => ElMessage.warning('最多上传 3 张照片')"
            :on-error="() => ElMessage.error('图片压缩或上传失败，请删除后重试')"
          >
            <el-icon><Plus /></el-icon>
            <template #tip>
              <div class="upload-tip">1~3 张，自动压缩后上传（JPG/PNG）</div>
            </template>
          </el-upload>
        </el-form-item>

        <el-divider content-position="left">预约与紧急程度</el-divider>
        <el-form-item label="加急处理">
          <el-switch v-model="isUrgent" active-color="#F56C6C" @change="saveDraft" />
          <span class="urgency-hint">{{ isUrgent ? '将按紧急工种优先调度（SLA 更短）' : '按普通工单进入智能调度' }}</span>
        </el-form-item>

        <el-form-item label="期望上门时段">
          <el-radio-group v-model="form.appointmentPeriod" @change="saveDraft">
            <el-radio-button value="上午08:30-11:30">上午</el-radio-button>
            <el-radio-button value="下午14:00-17:30">下午</el-radio-button>
            <el-radio-button value="晚间18:30-21:00">晚间</el-radio-button>
            <el-radio-button value="周末全天">周末</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="联系电话" prop="applicantPhone">
          <el-input v-model="form.applicantPhone" placeholder="留空默认使用个人中心手机号" style="width: 280px" @input="saveDraft" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" size="large" :loading="submitting" @click="onSubmit">确认提交报修单</el-button>
          <el-button size="large" @click="router.push('/')">返回我的工单</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox, FormInstance, UploadRequestOptions, UploadUserFile } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { useAuthStore, httpClient } from '../../stores/auth';
import { compressImage } from '@campus/shared';

const router = useRouter();
const authStore = useAuthStore();

const submitting = ref(false);
const hasDraft = ref(false);
const duplicateTip = ref('');

const formRef = ref<FormInstance>();
const isUrgent = ref(false);
const spacePath = ref<number[]>([]);
const categoryPath = ref<number[]>([]);
const spaceFullPathName = ref('');
const categoryFullPathName = ref('');

const spaceOptions = ref<any[]>([]);
const categoryOptions = ref<any[]>([]);
const fileList = ref<UploadUserFile[]>([]);

const cascaderProps = { value: 'id', label: 'name', children: 'children', emitPath: true };

const form = reactive({
  spaceId: null as number | null,
  categoryId: null as number | null,
  description: '',
  appointmentPeriod: '上午08:30-11:30',
  applicantPhone: ''
});

const rules = {
  spaceId: [{ required: true, message: '请选择报修具体空间位置', trigger: 'change' }],
  categoryId: [{ required: true, message: '请选择故障分类', trigger: 'change' }],
  description: [{ required: true, message: '请描述故障详情', trigger: 'blur' }]
};

const DRAFT_STORAGE_KEY = 'CAMPUS_REPAIR_SUBMIT_DRAFT';

function saveDraft() {
  const draft = {
    spaceId: form.spaceId,
    selectedSpaceName: spaceFullPathName.value,
    categoryId: form.categoryId,
    selectedCategoryName: categoryFullPathName.value,
    description: form.description,
    appointmentPeriod: form.appointmentPeriod,
    applicantPhone: form.applicantPhone,
    isUrgent: isUrgent.value
  };
  localStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify(draft));
}

function restoreDraft() {
  const cached = localStorage.getItem(DRAFT_STORAGE_KEY);
  if (!cached) return;
  try {
    const data = JSON.parse(cached);
    if (data.description || data.spaceId || data.categoryId) {
      form.spaceId = data.spaceId;
      spaceFullPathName.value = data.selectedSpaceName || '';
      form.categoryId = data.categoryId;
      categoryFullPathName.value = data.selectedCategoryName || '';
      form.description = data.description || '';
      form.appointmentPeriod = data.appointmentPeriod || '上午08:30-11:30';
      form.applicantPhone = data.applicantPhone || '';
      isUrgent.value = !!data.isUrgent;
      hasDraft.value = true;
      triggerDuplicateCheck();
    }
  } catch (_) {}
}

function findPathName(list: any[], id: number, prefix = ''): string | null {
  for (const node of list) {
    const curPath = prefix ? `${prefix} / ${node.name}` : node.name;
    if (node.id === id) return curPath;
    if (node.children) {
      const r = findPathName(node.children, id, curPath);
      if (r) return r;
    }
  }
  return null;
}

function findIdPath(list: any[], id: number, prefix: number[] = []): number[] | null {
  for (const node of list) {
    const curPath = [...prefix, node.id];
    if (node.id === id) return curPath;
    if (node.children) {
      const r = findIdPath(node.children, id, curPath);
      if (r) return r;
    }
  }
  return null;
}

async function loadSpaceOptions() {
  try {
    const res = await httpClient.get<any[]>('/spatial/tree', { params: { onlyEnabled: true } });
    if (res.code === 200) spaceOptions.value = res.data || [];
  } catch (_) {}
}

async function loadCategoryOptions() {
  try {
    const res = await httpClient.get<any[]>('/categories/tree', { params: { onlyEnabled: true } });
    if (res.code === 200) categoryOptions.value = res.data || [];
  } catch (_) {}
}

function onSpaceChange() {
  form.spaceId = spacePath.value.length ? spacePath.value[spacePath.value.length - 1] : null;
  spaceFullPathName.value = form.spaceId ? (findPathName(spaceOptions.value, form.spaceId) || '') : '';
  saveDraft();
  triggerDuplicateCheck();
}

function onCategoryChange() {
  form.categoryId = categoryPath.value.length ? categoryPath.value[categoryPath.value.length - 1] : null;
  categoryFullPathName.value = form.categoryId ? (findPathName(categoryOptions.value, form.categoryId) || '') : '';
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
    duplicateTip.value = res.code === 200 && res.data?.hasActiveOrder ? res.data.tipMessage : '';
  } catch (_) {}
}

/**
 * el-upload 自定义上传：resolve 即成功（file.response = { url }），reject 即失败。
 * 实际提交的图片 URL 在 onSubmit 时从 fileList 中已成功的条目派生。
 */
async function uploadImage(options: UploadRequestOptions): Promise<{ url: string }> {
  const compressedBlob = await compressImage(options.file as File, { maxDimension: 1600, quality: 0.8 });
  const compressedFile = new File([compressedBlob], options.file.name, { type: 'image/jpeg' });

  const formData = new FormData();
  formData.append('file', compressedFile);

  const res = await httpClient.post<{ url: string }>('/common/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  });

  if (res.code === 200 && res.data?.url) {
    return { url: res.data.url };
  }
  throw new Error(res.message || '上传失败');
}

function collectUploadedUrls(): string[] {
  return fileList.value
    .filter((f) => f.status === 'success' && (f.response as { url?: string } | undefined)?.url)
    .map((f) => (f.response as { url: string }).url);
}

async function onSubmit() {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  submitting.value = true;
  try {
    const payload = {
      spaceId: form.spaceId,
      categoryId: form.categoryId,
      description: form.description,
      appointmentPeriod: form.appointmentPeriod,
      urgencyLevel: isUrgent.value ? 2 : 1,
      applicantPhone: form.applicantPhone || undefined,
      faultImages: collectUploadedUrls()
    };

    const res = await httpClient.post<any>('/work-orders/submit', payload);
    if (res.code === 200) {
      localStorage.removeItem(DRAFT_STORAGE_KEY);
      await ElMessageBox.alert(
        `您的报修工单已受理！工单编号：${res.data?.orderNo}，系统正在为您自动调度匹配师傅。`,
        '报修提交成功',
        { confirmButtonText: '查看我的工单', type: 'success' }
      );
      router.push('/');
    } else {
      ElMessage.error(res.message || '提单失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '提交异常');
  } finally {
    submitting.value = false;
  }
}

onMounted(async () => {
  await Promise.all([loadSpaceOptions(), loadCategoryOptions()]);
  restoreDraft();

  // 若无草稿且用户绑定了默认空间，自动带出默认空间
  if (!form.spaceId && authStore.userInfo?.defaultSpaceId) {
    const defaultId = authStore.userInfo.defaultSpaceId;
    form.spaceId = defaultId;
    spacePath.value = findIdPath(spaceOptions.value, defaultId) || [];
    spaceFullPathName.value = findPathName(spaceOptions.value, defaultId) || '';
  }
  if (authStore.userInfo?.phone && !form.applicantPhone) {
    form.applicantPhone = authStore.userInfo.phone;
  }
});
</script>

<style scoped>
.student-submit-page {
  padding: 16px;
}
.card-title {
  font-weight: 600;
}
.submit-form {
  max-width: 760px;
}
.path-hint {
  margin-left: 12px;
  color: #6b7280;
  font-size: 12px;
}
.upload-tip {
  color: #9ca3af;
  font-size: 12px;
}
.urgency-hint {
  margin-left: 12px;
  color: #6b7280;
  font-size: 12px;
}
</style>
