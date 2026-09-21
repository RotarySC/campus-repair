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

      <el-form ref="formRef" :model="form" :rules="rules" :label-width="isNarrowPhone ? undefined : '110px'" :label-position="isNarrowPhone ? 'top' : 'right'" class="submit-form">
        <el-divider content-position="left">报修故障位置</el-divider>
        <el-form-item label="空间节点" prop="spaceId">
          <DrilldownCascader
            v-model="spacePath"
            :options="spaceOptions"
            label="空间节点"
            placeholder="请选择校区/楼栋/房间"
            style="width: 100%; max-width: 420px"
            @change="onSpaceChange"
          />
          <span v-if="spaceFullPathName" class="path-hint">{{ spaceFullPathName }}</span>
        </el-form-item>

        <el-divider content-position="left">故障类型与工种</el-divider>
        <el-form-item label="故障细类" prop="categoryId">
          <DrilldownCascader
            v-model="categoryPath"
            :options="categoryOptions"
            label="故障细类"
            placeholder="请选择分类与对应工种"
            root-label="全部分类"
            style="width: 100%; max-width: 420px"
            @change="onCategoryChange"
          />
          <span v-if="categoryFullPathName" class="path-hint">{{ categoryFullPathName }}</span>
        </el-form-item>

        <!-- 助手内联推荐：只提示、只建议，用户不点「采用」就不动表单 -->
        <el-form-item v-if="assistantCandidates.length || assistantHint" label=" ">
          <div class="assistant-inline">
            <div class="assistant-inline-head">
              <span class="assistant-inline-title">智能助手建议</span>
              <el-button link size="small" @click="dismissAssistant">收起</el-button>
            </div>
            <p v-if="assistantHint" class="assistant-inline-hint">{{ assistantHint }}</p>
            <SuggestionCardList
              v-if="assistantCandidates.length"
              :candidates="assistantCandidates"
              @apply="applyAssistantCandidate"
            />
          </div>
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
            style="width: 100%; max-width: 560px"
            @input="onDescriptionInput"
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
          <el-input v-model="form.applicantPhone" placeholder="留空默认使用个人中心手机号" style="width: 100%; max-width: 280px" @input="saveDraft" />
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
import { reactive, ref, onMounted, onBeforeUnmount } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox, FormInstance, UploadRequestOptions, UploadUserFile } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { useAuthStore, httpClient } from '../../stores/auth';
import { useViewport } from '../../composables/useViewport';
import DrilldownCascader from '../../components/DrilldownCascader.vue';
import SuggestionCardList from '../../components/assistant/SuggestionCardList.vue';
import {
  ASSISTANT_DEGRADED_LABELS,
  compressImage,
  type AssistantCandidate,
  type AssistantSuggestion
} from '@campus/shared';

const { isNarrowPhone } = useViewport();

const route = useRoute();
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
      // 草稿里只有 id：必须把 id 路径回填给选择器，否则"表单已有值但控件显示为空"
      spacePath.value = data.spaceId ? findIdPath(spaceOptions.value, data.spaceId) || [] : [];
      categoryPath.value = data.categoryId ? findIdPath(categoryOptions.value, data.categoryId) || [] : [];
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

// ---------------------------------------------------------------------------
// 智能助手内联推荐
//   · 只建议、不自动改表单——必须用户点「采用」
//   · 助手不可用时静默清空，绝不打断提单
//   · 用户手动选了分类、或已对同一段描述点过「收起」，就不再打扰
// ---------------------------------------------------------------------------

const ASSISTANT_DEBOUNCE_MS = 600;

const assistantCandidates = ref<AssistantCandidate[]>([]);
const assistantHint = ref('');
/** 已被用户收起的描述文本：同一段描述不再自动弹建议 */
const dismissedDescription = ref('');
let suggestTimer: number | undefined;

function onDescriptionInput() {
  saveDraft();
  if (suggestTimer) window.clearTimeout(suggestTimer);
  suggestTimer = window.setTimeout(() => {
    void fetchAssistantSuggestion();
  }, ASSISTANT_DEBOUNCE_MS);
}

function clearAssistant() {
  assistantCandidates.value = [];
  assistantHint.value = '';
}

function dismissAssistant() {
  dismissedDescription.value = form.description.trim();
  clearAssistant();
}

async function fetchAssistantSuggestion() {
  const text = form.description.trim();
  if (form.categoryId) {
    clearAssistant();
    return;
  }
  if (text.length < 2 || dismissedDescription.value === text) {
    return;
  }
  try {
    const res = await httpClient.post<AssistantSuggestion>('/assistant/suggest', { description: text });
    if (res.code !== 200 || !res.data) {
      clearAssistant();
      return;
    }
    // 请求期间用户可能已经自己选了分类、或又改了描述——两种情况都不该再覆盖界面
    if (form.categoryId || form.description.trim() !== text) {
      return;
    }
    assistantCandidates.value = res.data.candidates || [];
    if (res.data.noMatch) {
      assistantHint.value =
        '没能判断出具体细类，请手动选择；补充「哪个房间、什么声音、什么时候开始的」会更准。';
    } else if (res.data.degradedReason) {
      assistantHint.value = ASSISTANT_DEGRADED_LABELS[res.data.degradedReason];
    } else {
      assistantHint.value = '';
    }
  } catch (_) {
    clearAssistant();
  }
}

function applyAssistantCandidate(candidate: AssistantCandidate) {
  const path = findIdPath(categoryOptions.value, candidate.categoryId);
  if (!path) {
    ElMessage.warning('该分类已停用，请手动选择其它细类');
    return;
  }
  categoryPath.value = path;
  onCategoryChange();
  clearAssistant();
  ElMessage.success(`已采用「${candidate.categoryName}」`);
}

/**
 * URL 预填（助手「采用并提单」跳过来时带 categoryId / spaceId）。
 *
 * 两条语义刻意不同：
 * · `categoryId` **覆盖**草稿——用户是从助手显式点过来的，意图比旧草稿新；
 * · `spaceId` **只在本页为空时**填——不覆盖草稿里已选的位置，也不覆盖个人中心的默认位置。
 * 应用完就 `router.replace` 摘掉 query，避免刷新时重复应用一次。
 */
function applyQueryPrefill() {
  const rawCategoryId = route.query.categoryId;
  const rawSpaceId = route.query.spaceId;
  if (!rawCategoryId && !rawSpaceId) return;

  const categoryId = Number(rawCategoryId);
  if (Number.isFinite(categoryId) && categoryId > 0) {
    const path = findIdPath(categoryOptions.value, categoryId);
    if (path) {
      categoryPath.value = path;
      onCategoryChange();
    }
  }

  const spaceId = Number(rawSpaceId);
  if (!form.spaceId && Number.isFinite(spaceId) && spaceId > 0) {
    const path = findIdPath(spaceOptions.value, spaceId);
    if (path) {
      spacePath.value = path;
      onSpaceChange();
    }
  }

  router.replace({ path: route.path });
}

onBeforeUnmount(() => {
  if (suggestTimer) window.clearTimeout(suggestTimer);
});

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

  // 助手「采用并提单」带过来的预填（必须在选项加载完与草稿恢复之后，才能回显 id 路径）
  applyQueryPrefill();

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
.assistant-inline {
  width: 100%;
  max-width: 560px;
  padding: 10px 12px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
}
.assistant-inline-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.assistant-inline-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-color-primary);
}
.assistant-inline-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
