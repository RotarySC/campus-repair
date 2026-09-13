<template>
  <div class="spatial-manage">
    <el-tabs v-model="activeTab">
      <!-- 空间网格管理 -->
      <el-tab-pane label="四级空间网格树" name="spatial">
        <div class="tab-toolbar">
          <el-button type="primary" icon="Plus" @click="openCreateNodeDialog(0, 1)">添加校区根节点</el-button>
          <el-switch v-model="onlyEnabled" active-text="仅看启用空间" inactive-text="全部空间" @change="loadSpatialTree" />
        </div>

        <el-tree
          :data="spatialTree"
          node-key="id"
          default-expand-all
          :expand-on-click-node="false"
          style="margin-top: 16px;"
        >
          <template #default="{ node, data }">
            <div class="tree-node-row">
              <span class="node-title">
                <el-tag size="small" :type="getLevelTagType(data.levelType)" style="margin-right: 8px;">
                  {{ getLevelLabel(data.levelType) }}
                </el-tag>
                <strong>{{ data.name }}</strong>
                <span class="full-path">({{ data.fullPathName }})</span>
                <el-tag v-if="data.buildingNature" size="small" effect="plain" style="margin-left: 8px;">
                  {{ data.buildingNature }}
                </el-tag>
              </span>

              <span class="node-actions">
                <!-- 「添加下级」对第 4 级（房间）不存在，但仍渲染占位（visibility:hidden）：
                     否则叶子行的操作组少一格，开关会向左漂移，与其它行的位置对不齐 -->
                <el-button
                  link
                  type="primary"
                  size="small"
                  class="action-add-btn"
                  :class="{ 'is-placeholder': data.levelType >= 4 }"
                  @click="openCreateNodeDialog(data.id, data.levelType + 1)"
                >
                  添加下级
                </el-button>
                <el-switch
                  :model-value="data.status === 1"
                  aria-label="空间启用状态"
                  size="small"
                  @change="(val: boolean) => toggleNodeStatus(data, val)"
                />
                <el-button link type="danger" size="small" @click="handleDeleteNode(data)">删除</el-button>
              </span>
            </div>
          </template>
        </el-tree>
      </el-tab-pane>

      <!-- 故障分类与工种映射 -->
      <el-tab-pane label="故障分类与工种字典" name="category">
        <div class="tab-toolbar">
          <el-button type="primary" icon="Plus" @click="openCreateCategoryDialog(0, 1)">添加一级故障大类</el-button>
        </div>

        <!-- 宽档=树形表格；窄档(<768px)=树形卡片列表（不再依赖 880px 宽的横向滚动） -->
        <el-table
          v-if="!isNarrowPhone"
          :data="categoryList"
          row-key="id"
          default-expand-all
          border
          style="width: 100%; margin-top: 16px;"
        >
          <el-table-column prop="name" label="分类名称" width="260" />
          <el-table-column prop="level" label="层级" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.level === 1 ? 'primary' : 'success'" size="small">
                {{ row.level === 1 ? '大类' : '二级细类' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="workerSkillCode" label="指派维修工种" width="160">
            <template #default="{ row }">
              <el-tag v-if="row.workerSkillCode" type="warning">{{ row.workerSkillCode }}</el-tag>
              <span v-else class="text-muted">--</span>
            </template>
          </el-table-column>
          <el-table-column prop="slaHours" label="承诺SLA解决时限(小时)" width="180" align="center" />
          <el-table-column label="操作" align="right">
            <template #default="{ row }">
              <!-- 二级细类没有「添加细类」，同样用占位撑住列宽，保证「删除」在所有行对齐 -->
              <el-button
                link
                type="primary"
                size="small"
                class="action-add-btn"
                :class="{ 'is-placeholder': row.level !== 1 }"
                @click="openCreateCategoryDialog(row.id, 2)"
              >
                添加细类
              </el-button>
              <el-button link type="danger" size="small" @click="handleDeleteCategory(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-tree
          v-else
          :data="categoryList"
          node-key="id"
          default-expand-all
          :expand-on-click-node="false"
          style="margin-top: 16px;"
        >
          <template #default="{ data }">
            <div class="category-card">
              <div class="category-card__head">
                <strong class="category-card__name">{{ data.name }}</strong>
                <el-tag :type="data.level === 1 ? 'primary' : 'success'" size="small">
                  {{ data.level === 1 ? '大类' : '二级细类' }}
                </el-tag>
              </div>
              <div class="category-card__row">
                <span class="category-card__label">指派维修工种</span>
                <el-tag v-if="data.workerSkillCode" type="warning" size="small">{{ data.workerSkillCode }}</el-tag>
                <span v-else class="text-muted">--</span>
              </div>
              <div class="category-card__row">
                <span class="category-card__label">承诺SLA解决时限</span>
                <span>{{ data.slaHours ? `${data.slaHours} 小时` : '—' }}</span>
              </div>
              <div class="category-card__foot">
                <el-button
                  link
                  type="primary"
                  size="small"
                  class="action-add-btn"
                  :class="{ 'is-placeholder': data.level !== 1 }"
                  @click="openCreateCategoryDialog(data.id, 2)"
                >
                  添加细类
                </el-button>
                <el-button link type="danger" size="small" @click="handleDeleteCategory(data)">删除</el-button>
              </div>
            </div>
          </template>
        </el-tree>
      </el-tab-pane>

      <!-- 业务参数配置 -->
      <el-tab-pane label="全局业务参数与规则" name="config">
        <el-table :data="configList" border style="width: 100%; margin-top: 16px;">
          <el-table-column prop="configKey" label="参数标识" width="280" />
          <el-table-column prop="description" label="说明" width="340" />
          <el-table-column prop="configValue" label="参数值">
            <template #default="{ row }">
              <el-input v-model="row.configValue" style="max-width: 220px;" size="small">
                <template #append>
                  <el-button icon="Check" @click="handleSaveConfig(row)">保存</el-button>
                </template>
              </el-input>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 空间创建弹窗 -->
    <el-dialog v-model="nodeDialogVisible" title="创建空间节点" width="460px">
      <el-form label-width="100px">
        <el-form-item label="空间名称">
          <el-input v-model="nodeForm.name" placeholder="如：12号楼 / 302室" />
        </el-form-item>
        <el-form-item v-if="nodeForm.levelType === 3" label="建筑性质">
          <el-select v-model="nodeForm.buildingNature" placeholder="请选择建筑性质">
            <el-option label="学生宿舍" value="DORMITORY" />
            <el-option label="教学科研" value="TEACHING" />
            <el-option label="行政办公" value="OFFICE" />
            <el-option label="公共场馆" value="VENUE" />
            <el-option label="室外公共" value="OUTDOOR" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="nodeForm.levelType === 3" label="楼栋责任人">
          <el-select v-model="nodeForm.leaderUserId" placeholder="选择楼栋网格责任人/宿管" clearable filterable>
            <el-option
              v-for="user in adminUserOptions"
              :key="user.id"
              :label="`${user.realName} (${user.username} - ${branchLabel(user.userType)})`"
              :value="user.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="nodeDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreateNode">确定</el-button>
      </template>
    </el-dialog>

    <!-- 分类创建弹窗 -->
    <el-dialog v-model="categoryDialogVisible" title="创建故障分类" width="480px">
      <el-form label-width="120px">
        <el-form-item label="分类名称">
          <el-input v-model="categoryForm.name" placeholder="如：水暖设施 / 水龙头破裂" />
        </el-form-item>
        <el-form-item v-if="categoryForm.level === 2" label="维修工种">
          <el-select v-model="categoryForm.workerSkillCode" placeholder="请选择标准维修工种">
            <el-option label="水暖工 (PLUMBER)" value="PLUMBER" />
            <el-option label="电工 (ELECTRICIAN)" value="ELECTRICIAN" />
            <el-option label="空调暖通 (HVAC)" value="HVAC" />
            <el-option label="锁具木工 (LOCKSMITH)" value="LOCKSMITH" />
            <el-option label="网络多媒体 (NETWORK)" value="NETWORK" />
          </el-select>
        </el-form-item>
        <el-form-item label="SLA时效(小时)">
          <el-input-number v-model="categoryForm.slaHours" :min="1" :max="168" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreateCategory">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { branchLabel } from '@campus/shared';
import { httpClient } from '../../stores/auth';
import { useViewport } from '../../composables/useViewport';

const activeTab = ref('spatial');
const onlyEnabled = ref(false);
const { isNarrowPhone } = useViewport();

const spatialTree = ref<any[]>([]);
const categoryList = ref<any[]>([]);
const configList = ref<any[]>([]);

const nodeDialogVisible = ref(false);
const nodeForm = reactive({
  parentId: 0,
  levelType: 1,
  name: '',
  buildingNature: 'DORMITORY',
  leaderUserId: null as number | null
});
const adminUserOptions = ref<any[]>([]);

async function loadAdminUsers() {
  try {
    const res = await httpClient.get<any>('/users/page', {
      params: { page: 1, size: 100 }
    });
    if (res.code === 200) adminUserOptions.value = res.data.records;
  } catch (_) {}
}

const categoryDialogVisible = ref(false);
const categoryForm = reactive({
  parentId: 0,
  level: 1,
  name: '',
  workerSkillCode: 'PLUMBER',
  slaHours: 24
});

function getLevelLabel(level: number) {
  switch (level) {
    case 1: return '校区';
    case 2: return '组团';
    case 3: return '楼栋';
    case 4: return '房间';
    default: return '节点';
  }
}

function getLevelTagType(level: number) {
  switch (level) {
    case 1: return 'danger';
    case 2: return 'warning';
    case 3: return 'primary';
    case 4: return 'success';
    default: return 'info';
  }
}

async function loadSpatialTree() {
  try {
    const res = await httpClient.get<any[]>('/spatial/tree', {
      params: { onlyEnabled: onlyEnabled.value }
    });
    if (res.code === 200) spatialTree.value = res.data;
  } catch (err: any) {
    ElMessage.error(err.message || '加载空间树失败');
  }
}

async function loadCategoryTree() {
  try {
    const res = await httpClient.get<any[]>('/categories/tree');
    if (res.code === 200) categoryList.value = res.data;
  } catch (err: any) {
    ElMessage.error(err.message || '加载分类失败');
  }
}

async function loadConfigs() {
  try {
    const res = await httpClient.get<any[]>('/configs');
    if (res.code === 200) configList.value = res.data;
  } catch (err: any) {
    ElMessage.error(err.message || '加载配置失败');
  }
}

function openCreateNodeDialog(parentId: number, levelType: number) {
  nodeForm.parentId = parentId;
  nodeForm.levelType = levelType;
  nodeForm.name = '';
  nodeDialogVisible.value = true;
}

async function submitCreateNode() {
  if (!nodeForm.name.trim()) {
    ElMessage.warning('请输入空间名称');
    return;
  }
  try {
    const res = await httpClient.post<any>('/spatial/nodes', nodeForm);
    if (res.code === 200) {
      ElMessage.success('创建成功');
      nodeDialogVisible.value = false;
      loadSpatialTree();
    }
  } catch (err: any) {
    ElMessage.error(err.message || '创建空间节点失败');
  }
}

async function toggleNodeStatus(node: any, val: boolean) {
  const newStatus = val ? 1 : 0;
  try {
    const res = await httpClient.put<null>(`/spatial/nodes/${node.id}/status`, null, {
      params: { status: newStatus }
    });
    if (res.code === 200) {
      node.status = newStatus;
      ElMessage.success('状态已切换');
    }
  } catch (err: any) {
    ElMessage.error(err.message || '切换状态失败');
  }
}

function handleDeleteNode(node: any) {
  ElMessageBox.confirm(`确定删除空间节点【${node.name}】吗？`, '删除警告', {
    type: 'warning'
  }).then(async () => {
    try {
      const res = await httpClient.delete<null>(`/spatial/nodes/${node.id}`);
      if (res.code === 200) {
        ElMessage.success('删除成功');
        loadSpatialTree();
      }
    } catch (err: any) {
      ElMessage.error(err.message || '删除失败');
    }
  }).catch(() => {});
}

function openCreateCategoryDialog(parentId: number, level: number) {
  categoryForm.parentId = parentId;
  categoryForm.level = level;
  categoryForm.name = '';
  categoryDialogVisible.value = true;
}

async function submitCreateCategory() {
  if (!categoryForm.name.trim()) {
    ElMessage.warning('请输入分类名称');
    return;
  }
  try {
    const res = await httpClient.post<any>('/categories', categoryForm);
    if (res.code === 200) {
      ElMessage.success('创建分类成功');
      categoryDialogVisible.value = false;
      loadCategoryTree();
    }
  } catch (err: any) {
    ElMessage.error(err.message || '创建分类失败');
  }
}

function handleDeleteCategory(row: any) {
  ElMessageBox.confirm(`确定删除故障分类【${row.name}】吗？`, '删除警告', {
    type: 'warning'
  }).then(async () => {
    try {
      const res = await httpClient.delete<null>(`/categories/${row.id}`);
      if (res.code === 200) {
        ElMessage.success('删除成功');
        loadCategoryTree();
      }
    } catch (err: any) {
      ElMessage.error(err.message || '删除失败');
    }
  }).catch(() => {});
}

async function handleSaveConfig(row: any) {
  try {
    const res = await httpClient.put<null>(`/configs/${row.configKey}`, null, {
      params: { value: row.configValue }
    });
    if (res.code === 200) ElMessage.success('参数保存成功');
  } catch (err: any) {
    ElMessage.error(err.message || '保存参数失败');
  }
}

onMounted(() => {
  loadSpatialTree();
  loadCategoryTree();
  loadConfigs();
  loadAdminUsers();
});
</script>

<style scoped>
.spatial-manage {
  padding: 16px;
  background: #ffffff;
  border-radius: 8px;
  min-height: calc(100vh - var(--topbar-h, 56px) - 32px);
}
.tab-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.tree-node-row {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 14px;
  padding-right: 8px;
  min-width: 0;
}
/* 标题组必须可收缩：否则标题 + 全路径（nowrap）会把操作组顶出卡片（<768px 实测溢出 233px） */
.node-title {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.full-path {
  font-size: 12px;
  color: #9CA3AF;
  margin-left: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.node-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
  margin-left: auto;
}
/* 条件按钮的占位格：占住原始宽度但不可见（visibility 不参与命中与 tab 序），
   让同一行的「开关」「删除」在所有行落在同一列 */
.action-add-btn.is-placeholder {
  visibility: hidden;
}
/* 窄档：全路径是辅助信息，先让位；操作组允许换行，保证「添加下级/开关/删除」始终可点 */
@media (max-width: 767px) {
  .node-title .full-path {
    display: none;
  }
  .node-actions {
    gap: 4px;
    flex-wrap: wrap;
    justify-content: flex-end;
  }
}
.text-muted {
  color: #9CA3AF;
}

/* 窄档：故障分类字典的树形卡片（替代 880px 宽的树形表格） */
/* 注意：el-tree 默认给节点固定行高（26/32/40px）且用绝对定位排布子节点，
   内容换成多行卡片后必须放开这三处，否则卡片会互相重叠、父节点高度不增长。 */
:deep(.el-tree-node__content) {
  height: auto;
  align-items: stretch;
}
:deep(.el-tree-node) {
  position: static;
}
:deep(.el-tree-node__children) {
  position: static;
}
.category-card {
  flex: 1;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fff;
  margin: 6px 0;
}
.category-card__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding-bottom: 8px;
  border-bottom: 1px dashed #ebeef5;
}
.category-card__name {
  font-size: 14px;
  color: #1f2937;
  word-break: break-all;
}
.category-card__row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  line-height: 1.6;
  margin-top: 6px;
}
.category-card__label {
  flex: 0 0 108px;
  color: #909399;
}
.category-card__foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #ebeef5;
}
</style>
