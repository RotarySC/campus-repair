<template>
  <div class="identity-review">
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- ==================== ① 待核验 ==================== -->
      <el-tab-pane label="待核验" name="pending">
        <div class="tab-toolbar">
          <div class="search-row">
            <el-input
              v-model="pendingQuery.keyword"
              placeholder="搜索学号/工号或姓名"
              clearable
              style="width: 240px;"
              @clear="searchPending"
              @keyup.enter="searchPending"
            />
            <el-button type="primary" icon="Search" @click="searchPending">查询</el-button>
            <el-button icon="Refresh" @click="loadPending">刷新</el-button>
          </div>
          <span class="tab-hint">共 {{ pendingTotal }} 条待核验</span>
        </div>

        <ResponsiveTable
          class="identity-table"
          :items="pendingList"
          :columns="pendingColumns"
          :loading="pendingLoading"
          empty-text="暂无待核验申请"
        >
          <template #col-userType="{ row }">
            <MetaChip>{{ branchLabel(row.userType) }}</MetaChip>
          </template>
          <template #col-matchResult="{ row }">
            <MetaChip>{{ matchResultText(row.matchResult) }}</MetaChip>
          </template>
          <template #col-applyType="{ row }">
            <MetaChip>{{ applyTypeText(row.applyType) }}</MetaChip>
          </template>
          <template #col-actions="{ row }">
            <div class="row-actions">
              <el-button link type="primary" size="small" @click="handleApprove(row)">通过</el-button>
              <el-button link type="danger" size="small" @click="openReject(row)">驳回</el-button>
            </div>
          </template>
        </ResponsiveTable>

        <div class="pagination-row">
          <el-pagination
            v-model:current-page="pendingQuery.page"
            v-model:page-size="pendingQuery.size"
            :total="pendingTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="loadPending"
            @current-change="loadPending"
          />
        </div>
      </el-tab-pane>

      <!-- ==================== ② 审核记录 ==================== -->
      <el-tab-pane label="审核记录" name="records">
        <div class="tab-toolbar">
          <div class="search-row">
            <el-input
              v-model="recordsQuery.keyword"
              placeholder="搜索学号/工号或姓名"
              clearable
              style="width: 220px;"
              @clear="searchRecords"
              @keyup.enter="searchRecords"
            />
            <el-select v-model="recordsQuery.outcome" style="width: 140px;" @change="searchRecords">
              <el-option label="全部结论" value="ALL" />
              <el-option label="已通过" value="APPROVED" />
              <el-option label="已驳回" value="REJECTED" />
            </el-select>
            <el-button type="primary" icon="Search" @click="searchRecords">查询</el-button>
          </div>
        </div>

        <ResponsiveTable
          class="identity-table"
          :items="recordsList"
          :columns="recordsColumns"
          :loading="recordsLoading"
          empty-text="暂无审核记录"
        >
          <template #col-userType="{ row }">
            <MetaChip>{{ branchLabel(row.userType) }}</MetaChip>
          </template>
          <template #col-matchResult="{ row }">
            <MetaChip>{{ matchResultText(row.matchResult) }}</MetaChip>
          </template>
          <template #col-outcome="{ row }">
            <MetaChip>{{ outcomeText(row.outcome) }}</MetaChip>
          </template>
          <template #col-reason="{ row }">
            <span :class="{ 'text-faint': !row.reason }">{{ row.reason || '—' }}</span>
          </template>
        </ResponsiveTable>

        <div class="pagination-row">
          <el-pagination
            v-model:current-page="recordsQuery.page"
            v-model:page-size="recordsQuery.size"
            :total="recordsTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="loadRecords"
            @current-change="loadRecords"
          />
        </div>
      </el-tab-pane>

      <!-- ==================== ③ 名单管理 ==================== -->
      <el-tab-pane label="名单管理" name="roster">
        <div class="tab-toolbar">
          <div class="search-row">
            <el-input
              v-model="rosterQuery.keyword"
              placeholder="搜索学号/工号、姓名或手机号"
              clearable
              style="width: 240px;"
              @clear="searchRoster"
              @keyup.enter="searchRoster"
            />
            <el-select v-model="rosterQuery.status" style="width: 140px;" @change="searchRoster">
              <el-option label="全部状态" value="" />
              <el-option label="在册" value="1" />
              <el-option label="已失效" value="0" />
            </el-select>
            <el-button type="primary" icon="Search" @click="searchRoster">查询</el-button>
          </div>

          <el-upload
            action="#"
            :http-request="handleRosterImport"
            :show-file-list="false"
            accept=".xlsx,.xls"
          >
            <el-button icon="Upload" :loading="importing">导入名单(Excel)</el-button>
          </el-upload>
        </div>

        <p class="tab-note">
          名单是注册核验的依据：在册且姓名一致自动通过；名单外或姓名不符进入「待核验」。
          离校/离职只标记失效，不删除行——审核轨迹要能回指。
        </p>

        <ResponsiveTable
          class="identity-table"
          :items="rosterList"
          :columns="rosterColumns"
          :loading="rosterLoading"
          empty-text="暂无名单数据，请先导入 Excel"
        >
          <template #col-userType="{ row }">
            <MetaChip>{{ branchLabel(row.userType) }}</MetaChip>
          </template>
          <template #col-status="{ row }">
            <div class="status-cell">
              <el-switch
                :model-value="row.status === 1"
                aria-label="名单在册状态"
                @change="(val: boolean) => toggleRosterStatus(row, val)"
              />
              <span class="status-text">{{ rosterStatusText(row.status) }}</span>
            </div>
          </template>
          <template #col-importBatch="{ row }">
            <span :class="{ 'text-faint': !row.importBatch }">{{ row.importBatch || '—' }}</span>
          </template>
        </ResponsiveTable>

        <div class="pagination-row">
          <el-pagination
            v-model:current-page="rosterQuery.page"
            v-model:page-size="rosterQuery.size"
            :total="rosterTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="loadRoster"
            @current-change="loadRoster"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 驳回理由弹窗：理由必填 ≤255（阶段文档 §4.3） -->
    <el-dialog
      v-model="rejectVisible"
      title="驳回身份核验"
      width="min(520px, 94vw)"
      @closed="rejectFormRef?.clearValidate()"
    >
      <el-form
        ref="rejectFormRef"
        :model="rejectForm"
        :rules="rejectRules"
        label-position="top"
        @submit.prevent
      >
        <el-form-item label="驳回理由（必填，将展示给申请人）" prop="reason">
          <el-input
            v-model="rejectForm.reason"
            type="textarea"
            :rows="4"
            maxlength="255"
            show-word-limit
            placeholder="例如：姓名与名册不符，请携带学生证到信息中心"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejecting" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 名单导入报告 -->
    <el-dialog v-model="reportVisible" title="名单导入结果" width="min(600px, 94vw)">
      <template v-if="importReport">
        <el-result
          :icon="importReport.skipped === 0 ? 'success' : 'warning'"
          :title="`共解析 ${importReport.total} 行，新增 ${importReport.created} 条、更新 ${importReport.updated} 条、跳过 ${importReport.skipped} 条`"
          sub-title="重复导入按学号/工号更新，不会产生重复行"
        />
        <div v-if="importReport.errors && importReport.errors.length > 0">
          <el-divider content-position="left">跳过明细</el-divider>
          <ul class="import-errors">
            <li v-for="(item, index) in importReport.errors" :key="index">{{ item }}</li>
          </ul>
        </div>
      </template>
      <template #footer>
        <el-button type="primary" @click="reportVisible = false">完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import {
  ElMessage,
  ElMessageBox,
  type FormInstance,
  type FormRules,
  type UploadRequestOptions
} from 'element-plus';
import {
  IdentityApplyTypeLabels,
  IdentityMatchResultLabels,
  IdentityReviewOutcomeLabels,
  IdentityRosterStatusLabels,
  branchLabel
} from '@campus/shared';
import type {
  IdentityApplyType,
  IdentityImportResultVO,
  IdentityMatchResult,
  IdentityReviewOutcome,
  IdentityReviewVO,
  IdentityRosterStatus,
  IdentityRosterVO,
  PageResult
} from '@campus/shared';
import { httpClient } from '../../stores/auth';
import ResponsiveTable from '../../components/ResponsiveTable.vue';
import MetaChip from '../../components/MetaChip.vue';
import type { TableColumn } from '../../components/ResponsiveTable.vue';

const activeTab = ref<'pending' | 'records' | 'roster'>('pending');

function matchResultText(value: IdentityMatchResult | string): string {
  return IdentityMatchResultLabels[value as IdentityMatchResult] || String(value);
}

function applyTypeText(value: IdentityApplyType | string): string {
  return IdentityApplyTypeLabels[value as IdentityApplyType] || String(value);
}

function outcomeText(value: IdentityReviewOutcome | string): string {
  return IdentityReviewOutcomeLabels[value as IdentityReviewOutcome] || String(value);
}

function rosterStatusText(value: IdentityRosterStatus | number): string {
  return IdentityRosterStatusLabels[value as IdentityRosterStatus] || String(value);
}

/* ==================== ① 待核验 ==================== */
const pendingLoading = ref(false);
const pendingList = ref<IdentityReviewVO[]>([]);
const pendingTotal = ref(0);
const pendingQuery = reactive({ page: 1, size: 10, keyword: '' });

const pendingColumns: TableColumn<IdentityReviewVO>[] = [
  { key: 'username', label: '学号/工号', width: 130, card: 'title' },
  { key: 'realName', label: '姓名', width: 100 },
  {
    key: 'userType',
    label: '所属条线',
    width: 120,
    card: 'tag',
    cardTag: (row) => ({ text: branchLabel(row.userType) })
  },
  { key: 'department', label: '单位/学院', minWidth: 150 },
  {
    key: 'matchResult',
    label: '比对结果',
    width: 110,
    card: 'tag',
    cardTag: (row) => ({ text: matchResultText(row.matchResult) })
  },
  {
    key: 'applyType',
    label: '来源',
    width: 100
  },
  { key: 'createdAt', label: '提交时间', width: 140, valueType: 'datetime' },
  { key: 'actions', label: '操作', width: 140, align: 'center', card: 'action' }
];

async function loadPending() {
  pendingLoading.value = true;
  try {
    const res = await httpClient.get<PageResult<IdentityReviewVO>>('/identity/reviews', {
      params: {
        outcome: 'PENDING',
        page: pendingQuery.page,
        size: pendingQuery.size,
        keyword: pendingQuery.keyword
      }
    });
    if (res.code === 200) {
      pendingList.value = res.data?.records || [];
      pendingTotal.value = res.data?.total || 0;
    } else {
      ElMessage.error(res.message || '加载待核验列表失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '加载待核验列表失败');
  } finally {
    pendingLoading.value = false;
  }
}

function searchPending() {
  pendingQuery.page = 1;
  loadPending();
}

/* ==================== ② 审核记录 ==================== */
const recordsLoading = ref(false);
const recordsList = ref<IdentityReviewVO[]>([]);
const recordsTotal = ref(0);
const recordsQuery = reactive({ page: 1, size: 10, keyword: '', outcome: 'ALL' });

const recordsColumns: TableColumn<IdentityReviewVO>[] = [
  { key: 'username', label: '学号/工号', width: 125, card: 'title' },
  { key: 'realName', label: '姓名', width: 95 },
  {
    key: 'userType',
    label: '所属条线',
    width: 115,
    card: 'tag',
    cardTag: (row) => ({ text: branchLabel(row.userType) })
  },
  {
    key: 'matchResult',
    label: '比对结果',
    width: 105,
    card: 'tag',
    cardTag: (row) => ({ text: matchResultText(row.matchResult) })
  },
  {
    key: 'outcome',
    label: '结论',
    width: 95,
    card: 'tag',
    cardTag: (row) => ({ text: outcomeText(row.outcome) })
  },
  { key: 'reason', label: '驳回理由', minWidth: 180, showOverflowTooltip: true },
  { key: 'reviewerName', label: '审核人', width: 110 },
  { key: 'reviewedAt', label: '审核时间', width: 140, valueType: 'datetime' },
  { key: 'createdAt', label: '提交时间', width: 140, valueType: 'datetime', cardOnly: true }
];

async function loadRecords() {
  recordsLoading.value = true;
  try {
    // outcome=ALL 由后端约定为「全部结论」（省略/空只返回 PENDING）
    const res = await httpClient.get<PageResult<IdentityReviewVO>>('/identity/reviews', {
      params: {
        outcome: recordsQuery.outcome,
        page: recordsQuery.page,
        size: recordsQuery.size,
        keyword: recordsQuery.keyword
      }
    });
    if (res.code === 200) {
      recordsList.value = res.data?.records || [];
      recordsTotal.value = res.data?.total || 0;
    } else {
      ElMessage.error(res.message || '加载审核记录失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '加载审核记录失败');
  } finally {
    recordsLoading.value = false;
  }
}

function searchRecords() {
  recordsQuery.page = 1;
  loadRecords();
}

/* ==================== ③ 名单管理 ==================== */
const rosterLoading = ref(false);
const rosterList = ref<IdentityRosterVO[]>([]);
const rosterTotal = ref(0);
const rosterQuery = reactive({ page: 1, size: 10, keyword: '', status: '' });

const rosterColumns: TableColumn<IdentityRosterVO>[] = [
  { key: 'username', label: '学号/工号', width: 130, card: 'title' },
  { key: 'realName', label: '姓名', width: 100 },
  {
    key: 'userType',
    label: '所属条线',
    width: 120,
    card: 'tag',
    cardTag: (row) => ({ text: branchLabel(row.userType) })
  },
  { key: 'department', label: '单位/学院', minWidth: 150 },
  { key: 'phone', label: '手机号', width: 125 },
  {
    key: 'status',
    label: '在册状态',
    width: 140,
    align: 'center'
  },
  { key: 'importBatch', label: '导入批次', width: 130, cardOnly: true }
];

async function loadRoster() {
  rosterLoading.value = true;
  try {
    const res = await httpClient.get<PageResult<IdentityRosterVO>>('/identity/roster', {
      params: {
        page: rosterQuery.page,
        size: rosterQuery.size,
        keyword: rosterQuery.keyword,
        // 空串 = 全部；后端 status 省略时不筛选
        status: rosterQuery.status === '' ? undefined : rosterQuery.status
      }
    });
    if (res.code === 200) {
      rosterList.value = res.data?.records || [];
      rosterTotal.value = res.data?.total || 0;
    } else {
      ElMessage.error(res.message || '加载名单失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '加载名单失败');
  } finally {
    rosterLoading.value = false;
  }
}

function searchRoster() {
  rosterQuery.page = 1;
  loadRoster();
}

/** 名单状态只有 0/1：失效只标记不删行（保留审核轨迹可回指） */
async function toggleRosterStatus(row: IdentityRosterVO, enabled: boolean) {
  const nextStatus: IdentityRosterStatus = enabled ? 1 : 0;
  if (nextStatus === 0) {
    try {
      await ElMessageBox.confirm(
        `确认将【${row.realName}（${row.username}）】标记为离校/离职？标记后将不能再用该学号注册，但历史审核记录保留。`,
        '标记名单失效',
        { confirmButtonText: '确认标记', cancelButtonText: '取消', type: 'warning' }
      );
    } catch {
      return;
    }
  }
  try {
    const res = await httpClient.put<null>(
      `/identity/roster/${row.id}/status`,
      null,
      { params: { status: nextStatus } }
    );
    if (res.code === 200) {
      row.status = nextStatus;
      ElMessage.success(nextStatus === 1 ? '已恢复在册' : '已标记为失效');
    } else {
      ElMessage.error(res.message || '名单状态更新失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '名单状态更新失败');
  }
}

const importing = ref(false);
const reportVisible = ref(false);
const importReport = ref<IdentityImportResultVO | null>(null);

async function handleRosterImport(options: UploadRequestOptions) {
  const formData = new FormData();
  formData.append('file', options.file as File);

  importing.value = true;
  try {
    const res = await httpClient.post<IdentityImportResultVO>('/identity/roster/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
    if (res.code === 200 && res.data) {
      importReport.value = res.data;
      reportVisible.value = true;
      rosterQuery.page = 1;
      await loadRoster();
    } else {
      ElMessage.error(res.message || '名单导入失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '名单导入解析失败');
  } finally {
    importing.value = false;
  }
}

/* ==================== 核验决策 ==================== */
async function handleApprove(row: IdentityReviewVO) {
  try {
    await ElMessageBox.confirm(
      `确认通过【${row.realName || row.username}】的校内身份核验？通过后该账号可提交报修。`,
      '通过身份核验',
      { confirmButtonText: '确认通过', cancelButtonText: '取消', type: 'info' }
    );
  } catch {
    return;
  }

  try {
    const res = await httpClient.post<null>(`/identity/reviews/${row.id}/approve`);
    if (res.code === 200) {
      ElMessage.success('核验已通过，申请人重新登录后即可提交报修');
      await loadPending();
      if (recordsList.value.length) await loadRecords();
    } else if (res.code === 1013) {
      ElMessage.warning(res.message || '该审核单已被处理，请刷新列表');
      await loadPending();
    } else {
      ElMessage.error(res.message || '核验通过失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '核验通过失败');
  }
}

const rejectVisible = ref(false);
const rejecting = ref(false);
const rejectTarget = ref<IdentityReviewVO | null>(null);
const rejectFormRef = ref<FormInstance>();
const rejectForm = reactive({ reason: '' });

const rejectRules: FormRules = {
  reason: [
    { required: true, message: '驳回理由必填，申请人需要看到原因', trigger: 'blur' },
    { max: 255, message: '驳回理由不能超过 255 个字符', trigger: 'blur' }
  ]
};

function openReject(row: IdentityReviewVO) {
  rejectTarget.value = row;
  rejectForm.reason = '';
  rejectVisible.value = true;
}

async function submitReject() {
  const valid = await rejectFormRef.value?.validate().catch(() => false);
  if (!valid || !rejectTarget.value) return;

  rejecting.value = true;
  try {
    const res = await httpClient.post<null>(
      `/identity/reviews/${rejectTarget.value.id}/reject`,
      { reason: rejectForm.reason.trim() }
    );
    if (res.code === 200) {
      ElMessage.success('已驳回，理由会展示给申请人');
      rejectVisible.value = false;
      await loadPending();
      if (recordsList.value.length) await loadRecords();
    } else if (res.code === 1013) {
      ElMessage.warning(res.message || '该审核单已被处理，请刷新列表');
      rejectVisible.value = false;
      await loadPending();
    } else {
      ElMessage.error(res.message || '驳回失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '驳回失败');
  } finally {
    rejecting.value = false;
  }
}

/* ==================== 生命周期 ==================== */
function onTabChange(name: string | number) {
  if (name === 'pending') {
    loadPending();
  } else if (name === 'records') {
    loadRecords();
  } else if (name === 'roster') {
    loadRoster();
  }
}

onMounted(loadPending);
</script>

<style scoped>
.identity-review {
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

.tab-hint {
  font-size: 13px;
  color: var(--faint);
}

.tab-note {
  margin: 10px 0 0;
  font-size: var(--fs-base);
  line-height: 1.6;
  color: var(--faint);
}

.identity-table {
  display: block;
  margin-top: 14px;
}

.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.status-text {
  font-size: var(--fs-base);
  color: var(--muted);
}

.text-faint {
  color: var(--faint);
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.import-errors {
  margin: 0;
  padding-inline-start: 18px;
  max-height: 220px;
  overflow-y: auto;
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
    flex: 1 1 160px;
    width: 100% !important;
  }
}
</style>
