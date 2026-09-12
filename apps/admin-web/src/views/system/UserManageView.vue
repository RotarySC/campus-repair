<template>
  <div class="user-management">
    <div class="toolbar">
      <div class="search-inputs">
        <el-input v-model="query.keyword" placeholder="搜索学号/姓名/手机号" clearable style="width: 220px;" @clear="handleSearch" @keyup.enter="handleSearch" />
        <el-select v-model="query.userType" placeholder="角色类型" clearable style="width: 160px;" @change="handleSearch">
          <el-option label="超级管理员" value="SUPER_ADMIN" />
          <el-option label="后勤管理员" value="LOGISTICS_ADMIN" />
          <el-option label="维修师傅" value="REPAIR_WORKER" />
          <el-option label="学生" value="USER_STUDENT" />
          <el-option label="教工" value="USER_TEACHER" />
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
          <el-button type="success" icon="Upload" :loading="importing">批量导入用户(Excel)</el-button>
        </el-upload>
      </div>
    </div>

    <!-- 用户列表表格 -->
    <el-table :data="tableData" v-loading="loading" border stripe style="width: 100%; margin-top: 16px;">
      <el-table-column prop="id" label="ID" width="70" align="center" />
      <el-table-column prop="username" label="学号/工号" width="130" />
      <el-table-column prop="realName" label="真实姓名" width="110" />
      <el-table-column prop="phone" label="联系电话" width="130" />
      <el-table-column prop="userType" label="角色身份" width="140">
        <template #default="{ row }">
          <el-tag :type="getUserTypeTag(row.userType)">{{ row.userType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="department" label="所属部门/学院" />
      <el-table-column prop="status" label="账号状态" width="100" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 1"
            inline-prompt
            active-text="启"
            inactive-text="停"
            @change="(val: boolean) => toggleStatus(row, val)"
          />
        </template>
      </el-table-column>
      <el-table-column prop="isFirstLogin" label="首次需改密" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="row.isFirstLogin === 1 ? 'warning' : 'info'" size="small">
            {{ row.isFirstLogin === 1 ? '需改密' : '正常' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" align="center">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="handleResetPassword(row)">重置密码</el-button>
        </template>
      </el-table-column>
    </el-table>

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
import { ref, reactive, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { httpClient } from '../../stores/auth';

const loading = ref(false);
const importing = ref(false);
const tableData = ref<any[]>([]);
const total = ref(0);

const query = reactive({
  page: 1,
  size: 10,
  keyword: '',
  userType: ''
});

const reportVisible = ref(false);
const importReport = ref<any>({});

function getUserTypeTag(type: string) {
  switch (type) {
    case 'SUPER_ADMIN': return 'danger';
    case 'LOGISTICS_ADMIN': return 'warning';
    case 'REPAIR_WORKER': return 'success';
    default: return 'info';
  }
}

async function loadData() {
  loading.value = true;
  try {
    const res = await httpClient.get<any>('/users/page', {
      params: query
    });
    if (res.code === 200) {
      tableData.value = res.data.records;
      total.value = res.data.total;
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

function handleResetPassword(row: any) {
  ElMessageBox.prompt(`请输入用户【${row.realName}】的新初始密码（留空默认 InitPass@2026）：`, '重置登录密码', {
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
  background-color: #ffffff;
  border-radius: 8px;
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
</style>
