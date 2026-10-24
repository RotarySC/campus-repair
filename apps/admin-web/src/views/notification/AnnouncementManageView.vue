<template>
  <div class="announcement-manage">
    <div class="tab-toolbar">
      <div class="search-row">
        <el-input
          v-model="query.keyword"
          placeholder="搜索公告标题"
          clearable
          style="width: 200px;"
          @clear="search"
          @keyup.enter="search"
        />
        <el-select v-model="query.status" style="width: 150px;" @change="search">
          <el-option label="全部状态" value="" />
          <el-option
            v-for="status in ANNOUNCEMENT_STATUS_OPTIONS"
            :key="status"
            :label="ANNOUNCEMENT_STATUS_LABELS[status]"
            :value="status"
          />
        </el-select>
        <el-button type="primary" icon="Search" @click="search">查询</el-button>
        <el-button icon="Refresh" @click="loadList">刷新</el-button>
      </div>
      <el-button type="primary" icon="Plus" @click="openCreate">新建公告</el-button>
    </div>

    <p class="tab-note">
      公告先审后发：草稿 → 提交审核 → 通过 / 驳回（驳回必填理由）→ 发布。
      只有走到「已发布」才会扇出站内信，未发布的公告对师生不可见；草稿与被驳回的公告可继续编辑后重新提交。
    </p>

    <ResponsiveTable
      class="announcement-table"
      :items="list"
      :columns="columns"
      :loading="loading"
      empty-text="暂无公告，先新建一份草稿"
    >
      <template #col-title="{ row }">
        <span class="cell-strong">{{ row.title }}</span>
      </template>
      <template #col-status="{ row }">
        <StatusRing :status="statusHueKey(row.status)" :label="statusText(row.status)" />
      </template>
      <template #col-reviewReason="{ row }">
        <span :class="{ 'text-faint': !row.reviewReason }">{{ row.reviewReason || '—' }}</span>
      </template>
      <template #col-actions="{ row }">
        <div class="row-actions">
          <el-button link size="small" :disabled="!canEdit(row)" @click="openEdit(row)">编辑</el-button>
          <el-button link size="small" :disabled="!canSubmit(row)" @click="submitReview(row)">
            提交审核
          </el-button>
          <el-button link type="primary" size="small" :disabled="!canReview(row)" @click="openReview(row)">
            审核
          </el-button>
          <el-button link type="primary" size="small" :disabled="!canPublish(row)" @click="publish(row)">
            发布
          </el-button>
        </div>
      </template>
    </ResponsiveTable>

    <div class="pagination-row">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="loadList"
        @current-change="loadList"
      />
    </div>

    <!-- 新建 / 编辑抽屉：仅 DRAFT / REJECTED 可保存 -->
    <el-drawer
      v-model="editorVisible"
      :title="editorForm.id ? '编辑公告' : '新建公告'"
      size="min(560px, 94vw)"
      direction="rtl"
      @closed="editorFormRef?.clearValidate()"
    >
      <el-form
        ref="editorFormRef"
        :model="editorForm"
        :rules="editorRules"
        label-position="top"
        @submit.prevent
      >
        <el-form-item label="公告标题（≤128 字）" prop="title">
          <el-input
            v-model="editorForm.title"
            maxlength="128"
            show-word-limit
            placeholder="例如：全校供水阀门维保停水通知"
          />
        </el-form-item>
        <el-form-item label="公告正文（≤2000 字）" prop="content">
          <el-input
            v-model="editorForm.content"
            type="textarea"
            :rows="10"
            maxlength="2000"
            show-word-limit
            placeholder="请写清停水/停电/检修的时间与影响范围；正文同样会过内容初筛（广告导流、辱骂、他人隐私、与维修无关）"
          />
        </el-form-item>
      </el-form>
      <p class="editor-note">
        正文会做一次内容初筛，命中词表的公告仍会进入复核队列；提交审核后不再允许编辑，被驳回后才能改稿重提。
      </p>
      <template #footer>
        <div class="editor-footer">
          <el-button @click="editorVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="saveDraft">保存草稿</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 审核：通过 / 驳回（驳回必填理由 ≤500） -->
    <el-dialog
      v-model="reviewVisible"
      title="审核公告"
      width="min(520px, 94vw)"
      :close-on-click-modal="false"
    >
      <p class="dialog-note">
        审核通过只是进入「已通过」，仍需再点「发布」才会扇出给师生；驳回后作者可改稿重提。
      </p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="审核结论">
          <el-radio-group v-model="reviewForm.decision">
            <el-radio label="APPROVED">通过</el-radio>
            <el-radio label="REJECTED">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item
          :label="reviewForm.decision === 'REJECTED' ? '驳回理由（必填，将展示给作者）' : '审核说明（可选）'"
        >
          <el-input
            v-model="reviewForm.reason"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="例如：正文含未核实的停电时段，请与后勤确认后重新提交"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <!-- 两个字面按钮而不是 `:type` 三元：本仓只允许「主操作色」与「破坏性 danger」两种字面档，
             绑定式会把颜色档变成运行时值（且被彩色控件棘轮判为不合规）。 -->
        <el-button
          v-if="reviewForm.decision === 'REJECTED'"
          type="danger"
          :loading="reviewing"
          @click="submitReviewDecision"
        >
          确认驳回
        </el-button>
        <el-button v-else type="primary" :loading="reviewing" @click="submitReviewDecision">
          确认通过
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { ANNOUNCEMENT_STATUS_LABELS, OrderStatusEnum } from '@campus/shared';
import type { AnnouncementStatus, AnnouncementVO, PageResult } from '@campus/shared';
import { httpClient } from '../../stores/auth';
import ResponsiveTable from '../../components/ResponsiveTable.vue';
import StatusRing from '../../components/StatusRing.vue';
import type { TableColumn } from '../../components/ResponsiveTable.vue';

/** 驳回理由上限（与后端 `review_reason VARCHAR(500)` 对齐） */
const REVIEW_REASON_MAX = 500;

const ANNOUNCEMENT_STATUS_OPTIONS = Object.keys(ANNOUNCEMENT_STATUS_LABELS) as AnnouncementStatus[];

function statusText(value: AnnouncementStatus | string): string {
  return ANNOUNCEMENT_STATUS_LABELS[value as AnnouncementStatus] || String(value);
}

/**
 * 公告状态 → **既有 8 个状态色相里的一个**（不新增颜色 token）。
 *
 * `StatusRing` 的色相由 `status` 推导，它只认识工单状态码，因此这里做一次语义平行的映射：
 * 草稿=未进入流程（灰，已作废档）、待审核=等他人处理（琥珀，待派单档）、
 * 已通过=等待下一步（青，待承接档）、已驳回=需要改稿（红，返工待派档）、已发布=完结（绿，已结单档）。
 * 每个色相在页内只服务一个公告状态，不会与同一屏上的别的含义撞车。
 */
const ANNOUNCEMENT_STATUS_HUE: Record<AnnouncementStatus, OrderStatusEnum> = {
  DRAFT: OrderStatusEnum.CANCELLED,
  PENDING_REVIEW: OrderStatusEnum.PENDING_DISPATCH,
  APPROVED: OrderStatusEnum.ASSIGNED,
  REJECTED: OrderStatusEnum.REWORK_PENDING,
  PUBLISHED: OrderStatusEnum.COMPLETED
};

function statusHueKey(value: AnnouncementStatus | string): string {
  return ANNOUNCEMENT_STATUS_HUE[value as AnnouncementStatus] || OrderStatusEnum.CANCELLED;
}

/* ==================== 列表 ==================== */
const loading = ref(false);
const list = ref<AnnouncementVO[]>([]);
const total = ref(0);
const query = reactive({ page: 1, size: 10, status: '', keyword: '' });

const columns: TableColumn<AnnouncementVO>[] = [
  { key: 'title', label: '公告标题', minWidth: 200, card: 'title' },
  {
    key: 'status',
    label: '状态',
    width: 105,
    card: 'tag',
    cardTag: (row) => ({ text: statusText(row.status), type: 'ring', status: statusHueKey(row.status) })
  },
  { key: 'authorName', label: '作者', width: 100 },
  { key: 'reviewReason', label: '审核说明', minWidth: 170, showOverflowTooltip: true },
  { key: 'submittedAt', label: '提交审核', width: 140, valueType: 'datetime', cardOnly: true },
  { key: 'publishedAt', label: '发布时间', width: 140, valueType: 'datetime' },
  { key: 'createdAt', label: '创建时间', width: 140, valueType: 'datetime' },
  { key: 'actions', label: '操作', width: 270, align: 'center', fixed: 'right', card: 'action' }
];

async function loadList() {
  loading.value = true;
  try {
    const res = await httpClient.get<PageResult<AnnouncementVO>>('/announcements', {
      params: {
        status: query.status || undefined,
        keyword: query.keyword || undefined,
        page: query.page,
        size: query.size
      }
    });
    if (res.code === 200) {
      list.value = res.data?.records || [];
      total.value = res.data?.total || 0;
    } else {
      ElMessage.error(res.message || '加载公告列表失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '加载公告列表失败');
  } finally {
    loading.value = false;
  }
}

function search() {
  query.page = 1;
  loadList();
}

/*
 * 状态机判据（与后端 `AnnouncementService` 同表）：
 * 只有 DRAFT / REJECTED 可编辑与提交审核；只有 PENDING_REVIEW 可审核；只有 APPROVED 可发布。
 * 按钮用 `disabled` 而不是隐藏——让作者看见「为什么现在不能做」，不用猜状态。
 */
function canEdit(row: AnnouncementVO): boolean {
  return row.status === 'DRAFT' || row.status === 'REJECTED';
}

function canSubmit(row: AnnouncementVO): boolean {
  return row.status === 'DRAFT' || row.status === 'REJECTED';
}

function canReview(row: AnnouncementVO): boolean {
  return row.status === 'PENDING_REVIEW';
}

function canPublish(row: AnnouncementVO): boolean {
  return row.status === 'APPROVED';
}

/* ==================== 新建 / 编辑 ==================== */
const editorVisible = ref(false);
const saving = ref(false);
const editorFormRef = ref<FormInstance>();
const editorForm = reactive<{ id: number | null; title: string; content: string }>({
  id: null,
  title: '',
  content: ''
});

const editorRules: FormRules = {
  title: [
    { required: true, message: '请输入公告标题', trigger: 'blur' },
    { max: 128, message: '标题不能超过 128 个字符', trigger: 'blur' }
  ],
  content: [
    { required: true, message: '请输入公告正文', trigger: 'blur' },
    { max: 2000, message: '正文不能超过 2000 个字符', trigger: 'blur' }
  ]
};

function openCreate() {
  editorForm.id = null;
  editorForm.title = '';
  editorForm.content = '';
  editorVisible.value = true;
}

function openEdit(row: AnnouncementVO) {
  editorForm.id = row.id;
  editorForm.title = row.title;
  editorForm.content = row.content;
  editorVisible.value = true;
}

async function saveDraft() {
  const valid = await editorFormRef.value?.validate().catch(() => false);
  if (!valid) return;

  saving.value = true;
  try {
    const payload = { title: editorForm.title.trim(), content: editorForm.content.trim() };
    const res = editorForm.id
      ? await httpClient.put<AnnouncementVO>(`/announcements/${editorForm.id}`, payload)
      : await httpClient.post<AnnouncementVO>('/announcements', payload);
    if (res.code === 200) {
      ElMessage.success(editorForm.id ? '公告已保存' : '公告草稿已创建，提交审核后才会进入复核队列');
      editorVisible.value = false;
      await loadList();
    } else {
      ElMessage.error(res.message || '保存公告失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '保存公告失败');
  } finally {
    saving.value = false;
  }
}

/* ==================== 提交审核 ==================== */
async function submitReview(row: AnnouncementVO) {
  try {
    await ElMessageBox.confirm(
      `确认把【${row.title}】提交内容审核？提交后正文不可再编辑，直到审核通过或被驳回。`,
      '提交审核',
      { confirmButtonText: '确认提交', cancelButtonText: '取消', type: 'info' }
    );
  } catch {
    return;
  }
  try {
    const res = await httpClient.post<null>(`/announcements/${row.id}/submit`);
    if (res.code === 200) {
      ElMessage.success('已提交审核');
      await loadList();
    } else {
      ElMessage.error(res.message || '提交审核失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '提交审核失败');
  }
}

/* ==================== 审核 ==================== */
const reviewVisible = ref(false);
const reviewing = ref(false);
const reviewTarget = ref<AnnouncementVO | null>(null);
const reviewForm = reactive<{ decision: 'APPROVED' | 'REJECTED'; reason: string }>({
  decision: 'APPROVED',
  reason: ''
});

function openReview(row: AnnouncementVO) {
  reviewTarget.value = row;
  reviewForm.decision = 'APPROVED';
  reviewForm.reason = '';
  reviewVisible.value = true;
}

async function submitReviewDecision() {
  if (!reviewTarget.value) return;
  const reason = reviewForm.reason.trim();
  if (reviewForm.decision === 'REJECTED' && !reason) {
    ElMessage.warning('驳回必须填写理由，作者需要看到原因');
    return;
  }
  if (reason.length > REVIEW_REASON_MAX) {
    ElMessage.warning(`审核说明不能超过 ${REVIEW_REASON_MAX} 个字符`);
    return;
  }

  reviewing.value = true;
  try {
    const res = await httpClient.post<null>(`/announcements/${reviewTarget.value.id}/review`, {
      decision: reviewForm.decision,
      reason: reason || undefined
    });
    if (res.code === 200) {
      ElMessage.success(reviewForm.decision === 'APPROVED' ? '审核已通过，可以发布了' : '已驳回，作者可改稿重提');
      reviewVisible.value = false;
      await loadList();
    } else {
      ElMessage.error(res.message || '审核公告失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '审核公告失败');
  } finally {
    reviewing.value = false;
  }
}

/* ==================== 发布 ==================== */
async function publish(row: AnnouncementVO) {
  try {
    await ElMessageBox.confirm(
      `确认发布【${row.title}】？发布会向全部在册在校用户扇出站内信，且不可撤回。`,
      '发布公告',
      { confirmButtonText: '确认发布', cancelButtonText: '取消', type: 'warning' }
    );
  } catch {
    return;
  }
  try {
    const res = await httpClient.post<number>(`/announcements/${row.id}/publish`);
    if (res.code === 200) {
      ElMessage.success(
        typeof res.data === 'number' ? `公告已发布，已推送至 ${res.data} 位在册用户` : '公告已发布'
      );
      await loadList();
    } else if (res.code === 2008) {
      // 2008 ANNOUNCEMENT_NOT_APPROVED：队列被别人改过，刷新后重看状态
      ElMessage.warning(res.message || '公告尚未审核通过，不能发布');
      await loadList();
    } else {
      ElMessage.error(res.message || '发布公告失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '发布公告失败');
  }
}

onMounted(loadList);
</script>

<style scoped>
.announcement-manage {
  padding: 16px;
  background-color: var(--surface);
  border-radius: var(--radius-card);
  min-height: calc(100vh - var(--topbar-h, 56px) - 32px);
}

.tab-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.search-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.search-row .el-input,
.search-row .el-select {
  max-width: 100%;
}

.tab-note {
  margin: 10px 0 0;
  font-size: var(--fs-base);
  line-height: 1.7;
  color: var(--faint);
}

.announcement-table {
  display: block;
  margin-top: 14px;
}

.cell-strong {
  font-weight: var(--fw-medium);
  color: var(--ink);
}

.text-faint {
  color: var(--faint);
}

.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.editor-note {
  margin: 4px 0 0;
  font-size: var(--fs-base);
  line-height: 1.7;
  color: var(--faint);
}

.editor-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.dialog-note {
  margin: 0 0 12px;
  font-size: var(--fs-base);
  line-height: 1.7;
  color: var(--muted);
}

@media (max-width: 767px) {
  .search-row {
    width: 100%;
  }

  .search-row .el-input,
  .search-row .el-select {
    flex: 1 1 150px;
    width: 100% !important;
  }
}
</style>
