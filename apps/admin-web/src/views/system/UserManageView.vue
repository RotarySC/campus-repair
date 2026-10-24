<template>
  <div class="user-management">
    <div class="toolbar">
      <div class="search-inputs">
        <el-input v-model="query.keyword" placeholder="搜索学号/姓名/手机号" clearable style="width: 220px;" @clear="handleSearch" @keyup.enter="handleSearch" />
        <el-select v-model="query.userType" placeholder="所属条线" clearable style="width: 160px;" @change="handleSearch">
          <el-option label="学生" value="STUDENT" />
          <el-option label="教学科研" value="FACULTY" />
          <el-option label="后勤保障" value="LOGISTICS" />
          <el-option label="行政信息化" value="ADMIN" />
        </el-select>
        <el-button type="primary" icon="Search" @click="handleSearch">查询</el-button>
      </div>

      <div class="actions">
        <el-upload
          action="#"
          :http-request="handleCustomUpload"
          :show-file-list="false"
          accept=".xlsx,.xls"
        >
 <!-- 中性化（执行顺序 1）：导入不是状态，原 `success` 绿与「成功」无关。 -->
          <el-button icon="Upload" :loading="importing">批量导入用户(Excel)</el-button>
        </el-upload>
      </div>
    </div>

    <!-- 用户列表：宽档=表格 / 窄档(<768px)=卡片列表（同一份列定义） -->
    <ResponsiveTable
      class="user-table"
      :items="tableData"
      :columns="columns"
      :loading="loading"
      empty-text="暂无用户"
    >
      <template #col-userType="{ row }">
        <MetaChip>{{ branchLabel(row.userType) }}</MetaChip>
      </template>

      <!--
        联系电话：列里只出现后端给的 `phoneDisplay`（`DISPATCHER` 掩码 / `PLATFORM_ADMIN` 完整）。
        用户台账不再出网 `password` 与完整号码；这里的 tooltip 解释可见性口径，
        避免管理员误以为掩码是数据缺失。
      -->
      <template #col-phoneDisplay="{ row }">
        <span :title="phoneColumnTip()">{{ row.phoneDisplay || '—' }}</span>
      </template>

      <template #col-status="{ row }">
        <!-- Phase 01：账号三态。开关只对 0/1 生效；2 = 待核验，不做启停 -->
        <div class="status-cell">
          <el-switch
            v-if="row.status === 0 || row.status === 1"
            :model-value="row.status === 1"
            aria-label="账号状态"
            @change="(val: boolean) => toggleStatus(row, val)"
          />
          <span v-if="row.status === 0 || row.status === 1" class="status-text">
            {{ statusLabel(row.status) }}
          </span>
          <StatusRing
            v-else-if="row.status === 2"
            variant="dot"
            tone="muted"
            label="待核验"
          />
          <span v-else class="text-faint">未知状态</span>
        </div>
      </template>

      <template #col-isFirstLogin="{ row }">
        <!--
 ⚠️ 2026-10-13（C 组 #4）：`el-tag`（warning/info）→ **提示档**（6px 中性点，静止）。
          这是 C 组 6 处里**唯一保留标记**的一处，理由：多数账号是「已改密」，而「需改密」是**少数派**，
          管理员会去催 —— 逐行容器里给少数派画标记，正是把注意力引过去的手段（与「加急」同一类）。
          但它**不是警戒**：改密由系统在首次登录时强制（`is_first_login=1`），不构成风险，
          所以走中性档而不是红色、也不呼吸。
          「已改密」是正常态 ⇒ 纯文字，不画标记（一侧标记；容器规则管的是固定同级集合，逐行容器允许）。
          ⚠️ **必须与 `columns` 里那条 `cardTag` 同时改**，否则同一条信息两种长相。
          顺带统一了文案：宽档原写「正常」、窄档原写「已改密」—— 现在两档都是「已改密」。
        -->
        <StatusRing v-if="row.isFirstLogin === 1" variant="dot" tone="muted" label="需改密" />
        <span v-else-if="row.isFirstLogin === 0">已改密</span>
        <!-- 字段缺失（旧后端 / 契约最小集）时显示未知，不猜成「已改密」 -->
        <span v-else class="text-faint">—</span>
      </template>

      <template #col-actions="{ row }">
        <div class="row-actions">
          <el-button
            v-if="row.status === 2"
            link
            type="primary"
            size="small"
            @click="goIdentityReview"
          >
            去核验
          </el-button>
          <el-button link type="primary" size="small" @click="handleResetPassword(row)">重置密码</el-button>
        </div>
      </template>
    </ResponsiveTable>

    <!-- 分页器 -->
    <div class="pagination-row">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="loadData"
        @current-change="loadData"
      />
    </div>

    <!-- 导入报告弹窗 -->
    <el-dialog v-model="reportVisible" title="Excel 批量导入报告" width="min(600px, 94vw)">
      <el-result
        :icon="importReport.failureCount === 0 ? 'success' : 'warning'"
        :title="`成功导入 ${importReport.successCount} 条，失败 ${importReport.failureCount} 条`"
        :sub-title="`总计解析行数：${importReport.totalCount}`"
      />
      <div v-if="importReport.failureDetails && importReport.failureDetails.length > 0">
        <el-divider content-position="left">失败明细（带行号）</el-divider>
        <el-table :data="importReport.failureDetails" size="small" border max-height="240">
          <el-table-column prop="rowNumber" label="行号" width="70" align="center" />
          <el-table-column prop="username" label="学号/工号" width="120" />
          <el-table-column prop="reason" label="失败原因" />
        </el-table>
      </div>
      <template #footer>
        <el-button type="primary" @click="reportVisible = false">完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import MetaChip from '../../components/MetaChip.vue';
import StatusRing from '../../components/StatusRing.vue';
import { ref, reactive, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { AccountStatusLabels, branchLabel } from '@campus/shared';
import type { AccountStatus, UserAdminVO } from '@campus/shared';
import { httpClient, useAuthStore } from '../../stores/auth';
import ResponsiveTable from '../../components/ResponsiveTable.vue';
import type { TableColumn } from '../../components/ResponsiveTable.vue';

const router = useRouter();
const authStore = useAuthStore();

/**
 * 当前登录人是否平台管理：只有 `PLATFORM_ADMIN` 能拿到完整号码（要联系账号本人），
 * 其余（如调度）拿到的是掩码。判定只在后端，这里只决定提示文案。
 */
const isPlatformAdmin = computed(() => (authStore.userInfo?.roleCodes || []).includes('PLATFORM_ADMIN'));

/** 手机号列的提示：解释「为什么你看到的是掩码 / 为什么你能看到完整」 */
function phoneColumnTip(): string {
  return isPlatformAdmin.value
    ? '完整号码仅平台管理可见'
    : '号码已按隐私规则掩码，完整号码仅平台管理可见';
}

const loading = ref(false);
const importing = ref(false);
const tableData = ref<UserAdminVO[]>([]);
const total = ref(0);

/** 列定义双档共用：宽档渲染 el-table，窄档按 card 角色渲染卡片 */
const columns: TableColumn<UserAdminVO>[] = [
  { key: 'id', label: 'ID', width: 70, align: 'center' },
  { key: 'username', label: '学号/工号', width: 130, card: 'title' },
  { key: 'realName', label: '真实姓名', width: 110 },
  {
    // 台账只输出后端算好的展示值：`DISPATCHER` 掩码、`PLATFORM_ADMIN` 完整
    key: 'phoneDisplay',
    label: '联系电话（掩码）',
    width: 150,
    showOverflowTooltip: true
  },
  {
    key: 'userType',
    label: '所属条线',
    width: 140,
    card: 'tag',
    cardTag: (row) => ({ text: branchLabel(row.userType) })
  },
  { key: 'department', label: '所属部门/学院' },
  {
    key: 'isFirstLogin',
    label: '首次需改密',
    width: 110,
    align: 'center',
    card: 'tag',
 // ⚠️ 2026-10-13（C 组 #4）：带语义 `type`（warning）的胶囊 → **提示档**中性点
    //    （`type:'ring'` + `variant:'dot'` + `tone:'muted'`）。与宽档 `#col-isFirstLogin` 同一个长相。
    //    「已改密」是正常态 ⇒ 不带 `type` ⇒ `is-info`（已与 `MetaChip` 统一的中性胶囊）。
    //    字段缺失（旧后端 / 契约最小集）时显示「—」，不猜成「已改密」。
    cardTag: (row) =>
      row.isFirstLogin === 1
        ? { text: '需改密', type: 'ring', variant: 'dot', tone: 'muted' }
        : row.isFirstLogin === 0
          ? { text: '已改密' }
          : { text: '—' }
  },
  { key: 'status', label: '账号状态', width: 120, align: 'center' },
  { key: 'actions', label: '操作', width: 180, align: 'center', card: 'action' }
];

const query = reactive({
  page: 1,
  size: 10,
  keyword: '',
  userType: ''
});

/** 账号状态中文名：`status` 来自后端分页 VO（number），按三态取值收敛类型 */
function statusLabel(status: number): string {
  return AccountStatusLabels[status as AccountStatus] || '未知状态';
}

const reportVisible = ref(false);
const importReport = ref<any>({});

async function loadData() {
  loading.value = true;
  try {
    // 台账端点已改为 `R<IPage<UserAdminVO>>`：不再有 password / 完整号码
    const res = await httpClient.get<{ records: UserAdminVO[]; total: number }>('/users/page', {
      params: query
    });
    if (res.code === 200 && res.data) {
      tableData.value = res.data.records || [];
      total.value = res.data.total || 0;
    }
  } catch (err: any) {
    ElMessage.error(err.message || '加载用户台账失败');
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  query.page = 1;
  loadData();
}

async function toggleStatus(row: any, val: boolean) {
  // 三态下开关只服务 0/1；2（待核验）必须走「身份核验」，不能用启停覆盖核验状态
  if (row.status !== 0 && row.status !== 1) return;
  const newStatus = val ? 1 : 0;
  try {
    const res = await httpClient.put<null>(`/users/${row.id}/status`, null, {
      params: { status: newStatus }
    });
    if (res.code === 200) {
      row.status = newStatus;
      ElMessage.success('账号状态更新成功');
    }
  } catch (err: any) {
    ElMessage.error(err.message || '状态变更失败');
  }
}

/** 待核验账号跳「身份核验」页处理（路由由 Lead 集成：/system/identity） */
function goIdentityReview() {
  router.push('/system/identity');
}

function handleResetPassword(row: any) {  ElMessageBox.prompt(`请输入用户【${row.realName}】的新初始密码（留空默认 InitPass@2026）：`, '重置登录密码', {
    confirmButtonText: '确定重置',
    cancelButtonText: '取消',
    inputValue: 'InitPass@2026'
  }).then(async ({ value }) => {
    try {
      const res = await httpClient.put<null>(`/users/${row.id}/password/reset`, null, {
        params: { newPassword: value }
      });
      if (res.code === 200) {
        ElMessage.success('密码已成功重置，下次登录生效');
        loadData();
      }
    } catch (err: any) {
      ElMessage.error(err.message || '密码重置失败');
    }
  }).catch(() => {});
}

async function handleCustomUpload(options: any) {
  const formData = new FormData();
  formData.append('file', options.file);

  importing.value = true;
  try {
    const res = await httpClient.post<any>('/users/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
    if (res.code === 200) {
      importReport.value = res.data;
      reportVisible.value = true;
      loadData();
    }
  } catch (err: any) {
    ElMessage.error(err.message || '导入解析出现异常');
  } finally {
    importing.value = false;
  }
}

onMounted(() => {
  loadData();
});
</script>

<style scoped>
.user-management {
  padding: 16px;
  background-color: var(--surface);
  border-radius: var(--radius-card);
  min-height: calc(100vh - var(--topbar-h, 56px) - 32px);
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.search-inputs {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.search-inputs .el-input {
  width: 220px;
  max-width: 100%;
}
.search-inputs .el-select {
  width: 160px;
  max-width: 100%;
}
@media (max-width: 767px) {
  .search-inputs .el-input,
  .search-inputs .el-select {
    width: 100%;
    flex: 1 1 160px;
  }
}
.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.status-text {
  font-size: var(--fs-base);
  color: var(--muted);
}
.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.text-faint {
  color: var(--faint);
}
.user-table {
  display: block;
  margin-top: 16px;
}
</style>
