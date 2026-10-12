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
                <!--
                  A4：层级改用**图标 + 中性文字**。原先是一个按层级染色的 `el-tag`
                  （`getLevelTagType()` 给 校区/组团/楼栋/房间 配了 红/橙/蓝/绿 四个 EP 语义色），
                  而**层级是分类，不是状态**——那四个色在这里没有任何语义，只是「四个不一样的颜色」。
                  换成图标后颜色维度整个消失：图标继承 `currentColor`，文字走中性令牌。
                -->
                <span class="level-tag">
                  <LevelIcon kind="space" :level="data.levelType" />
                  {{ getLevelLabel(data.levelType) }}
                </span>
                <strong>{{ data.name }}</strong>
                <span class="full-path">({{ data.fullPathName }})</span>
                <!-- 🐞 原先直接渲染 `data.buildingNature`，于是同一页里
                     「建筑性质」下拉框是中文（学生宿舍/教学科研/…），树节点上却是
                     **`DORMITORY` / `TEACHING` / `OFFICE`** —— 与工种那两个裸码同一族。
                     现在两处共用 `BuildingNatureLabels` 这一处声明。 -->
                <el-tag v-if="data.buildingNature" size="small" effect="plain" style="margin-inline-start: 8px;">
                  {{ buildingNatureLabelOf(data.buildingNature) }}
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
          <el-table-column prop="name" label="分类名称" width="260">
            <template #default="{ row }">
              <div class="category-name-cell">
                <div class="category-name-line">
                  <!-- A3：分类图标。按分类名取，取不到回落到工种码（用户新建的分类也不空白）。 -->
                  <CategoryIcon :name="row.name" :skill="row.workerSkillCode" />
                  <span>{{ row.name }}</span>
                </div>
                <!-- 助手知识：不单开一列（1272px 下会把「操作」列挤到换行），挂在名称下面 -->
                <span v-if="row.keywords" class="category-assist-line">
                  助手同义词：{{ row.keywords }}
                </span>
                <span v-if="row.selfHelpTip" class="category-assist-line">
                  自助提示：{{ row.selfHelpTip }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="level" label="层级" width="100" align="center">
            <template #default="{ row }">
              <!-- 🐞 第五处「四个装饰色」同族缺陷：这里原是
                   `:type="row.level === 1 ? 'primary' : 'success'"`——大类=蓝、二级细类=绿。
                   层级是**分类**，不是状态（与 §17.3 删掉的 `getLevelTagType()` 逐字同病）。
                   现在层级由 CategoryIcon 的图形承担，这里只留一枚**中性**的发丝线 chip。 -->
              <MetaChip>{{ row.level === 1 ? '大类' : '二级细类' }}</MetaChip>
            </template>
          </el-table-column>
          <el-table-column prop="workerSkillCode" label="指派维修工种" width="160">
            <template #default="{ row }">
              <!-- 🐞 原先直接渲染 `row.workerSkillCode`，于是用户看到的是 **`PLUMBER` / `HVAC` / `LOCKSMITH`**
                   这些字典码本身（AGENTS.md 禁止的正是这一类，而它举的反例恰好就是 `PLUMBER`）。
                   工种的唯一中文来源是 shared 的 `WorkerSkillLabels`——与调度中心、助手候选卡同源。 -->
              <MetaChip v-if="row.workerSkillCode">
                {{ WorkerSkillLabels[row.workerSkillCode] || row.workerSkillCode }}
              </MetaChip>
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
              <el-button link type="primary" size="small" @click="openEditCategoryDialog(row)">编辑</el-button>
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
                <CategoryIcon :name="data.name" :skill="data.workerSkillCode" />
                <strong class="category-card__name">{{ data.name }}</strong>
                <MetaChip>{{ data.level === 1 ? '大类' : '二级细类' }}</MetaChip>
              </div>
              <div class="category-card__row">
                <span class="category-card__label">指派维修工种</span>
                <!-- 同上：窄档卡片也要走中文标签，别把字典码端给用户 -->
                <MetaChip v-if="data.workerSkillCode">
                  {{ WorkerSkillLabels[data.workerSkillCode] || data.workerSkillCode }}
                </MetaChip>
                <span v-else class="text-muted">--</span>
              </div>
              <div class="category-card__row">
                <span class="category-card__label">承诺SLA解决时限</span>
                <span>{{ data.slaHours ? `${data.slaHours} 小时` : '—' }}</span>
              </div>
              <div v-if="data.keywords" class="category-card__row">
                <span class="category-card__label">助手同义词</span>
                <span class="category-card__text">{{ data.keywords }}</span>
              </div>
              <div v-if="data.selfHelpTip" class="category-card__row">
                <span class="category-card__label">自助提示</span>
                <span class="category-card__text">{{ data.selfHelpTip }}</span>
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
                <el-button link type="primary" size="small" @click="openEditCategoryDialog(data)">编辑</el-button>
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
            <!-- 选项由 shared 的 `BuildingNatureLabels` 生成：**一处声明、两处消费**
                 （这里 + 树节点上的标签），新增一个性质只改那一个文件 -->
            <el-option
              v-for="opt in BuildingNatureOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
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

    <!-- 分类创建/编辑弹窗 -->
    <el-dialog
      v-model="categoryDialogVisible"
      :title="editingCategoryId ? '编辑故障分类' : '创建故障分类'"
      width="520px"
    >
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
        <el-form-item label="助手同义词">
          <el-input
            v-model="categoryForm.keywords"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
            placeholder="逗号分隔，如：水龙头,龙头,滴水,关不紧"
          />
          <div class="form-tip">
            供智能助手判断故障类别：用户描述里出现这些词就会推荐本分类。**单个词请写 2 个字以上**
            （「水」「灯」这类单字会被忽略，只会制造噪声）。
          </div>
        </el-form-item>
        <el-form-item v-if="categoryForm.level === 2" label="自助排查提示">
          <el-input
            v-model="categoryForm.selfHelpTip"
            type="textarea"
            :rows="3"
            maxlength="255"
            show-word-limit
            placeholder="多个步骤用 | 分隔，如：先关闭角阀|用盆桶接水|拍照留存现场"
          />
          <div class="form-tip">提交报修前展示给师生「可以先自己试试」的步骤，只提示、不阻断提单。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCategoryForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import MetaChip from '../../components/MetaChip.vue';
import { ref, reactive, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { branchLabel, WorkerSkillLabels, BuildingNatureOptions, buildingNatureLabelOf } from '@campus/shared';
import { httpClient } from '../../stores/auth';
import { useViewport } from '../../composables/useViewport';
import LevelIcon from '../../components/LevelIcon.vue';
import CategoryIcon from '../../components/CategoryIcon.vue';

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
/** 非空表示当前弹窗处于「编辑」态（否则是新建） */
const editingCategoryId = ref<number | null>(null);
const categoryForm = reactive({
  parentId: 0,
  level: 1,
  name: '',
  workerSkillCode: 'PLUMBER',
  slaHours: 24,
  keywords: '',
  selfHelpTip: ''
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

/*
 * `getLevelTagType()` **已删除**（A4）：它把四个层级映射成四个 EP 语义色
 * （校区=danger 红 / 组团=warning 橙 / 楼栋=primary 蓝 / 房间=success 绿）。
 * 层级是**分类**不是状态，这四个色纯属装饰——而且它们还借用了「危险/警告」这两个
 * 有确定含义的语义档去表示「校区/组团」，属于 AGENTS.md 说的「一个色相两个意思」。
 * 现在层级由 `LevelIcon` 的图形承担。
 */

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
  editingCategoryId.value = null;
  categoryForm.parentId = parentId;
  categoryForm.level = level;
  categoryForm.name = '';
  categoryForm.workerSkillCode = 'PLUMBER';
  categoryForm.slaHours = 24;
  categoryForm.keywords = '';
  categoryForm.selfHelpTip = '';
  categoryDialogVisible.value = true;
}

/** 编辑：把行上的值填进同一个弹窗（后端 `PUT /categories` 支持部分字段，null 表示不改） */
function openEditCategoryDialog(row: any) {
  editingCategoryId.value = row.id;
  categoryForm.parentId = row.parentId ?? 0;
  categoryForm.level = row.level;
  categoryForm.name = row.name || '';
  categoryForm.workerSkillCode = row.workerSkillCode || 'PLUMBER';
  categoryForm.slaHours = row.slaHours ?? 24;
  categoryForm.keywords = row.keywords || '';
  categoryForm.selfHelpTip = row.selfHelpTip || '';
  categoryDialogVisible.value = true;
}

async function submitCategoryForm() {
  if (!categoryForm.name.trim()) {
    ElMessage.warning('请输入分类名称');
    return;
  }
  // 两列助手知识一律传字符串（含空串）：空串才是"清空"，不传则后端视为"本次不修改"
  const payload: Record<string, unknown> = {
    name: categoryForm.name.trim(),
    workerSkillCode: categoryForm.workerSkillCode,
    slaHours: categoryForm.slaHours,
    keywords: categoryForm.keywords.trim(),
    selfHelpTip: categoryForm.level === 2 ? categoryForm.selfHelpTip.trim() : categoryForm.selfHelpTip
  };
  try {
    let res;
    if (editingCategoryId.value) {
      res = await httpClient.put<any>('/categories', { id: editingCategoryId.value, ...payload });
    } else {
      res = await httpClient.post<any>('/categories', {
        parentId: categoryForm.parentId,
        level: categoryForm.level,
        ...payload
      });
    }
    if (res.code === 200) {
      ElMessage.success(editingCategoryId.value ? '分类已更新' : '创建分类成功');
      categoryDialogVisible.value = false;
      loadCategoryTree();
    }
  } catch (err: any) {
    ElMessage.error(err.message || (editingCategoryId.value ? '更新分类失败' : '创建分类失败'));
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
  background: var(--surface);
  border-radius: var(--radius-card);
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
/* 层级标记：图标 + 中性文字（A4）。图标 16px、继承 currentColor；文字比节点名弱一档。 */
.level-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: none;
  margin-right: 8px;
  color: var(--muted);
  font-size: var(--fs-base);
}
.full-path {
  font-size: var(--fs-base);
  color: var(--faint);
  margin-inline-start: 6px;
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
  color: var(--faint);
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
  border: var(--hairline) solid var(--line);
  border-radius: var(--radius-card);
  background: var(--surface);
  margin: 6px 0;
}
.category-card__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding-bottom: 8px;
  border-bottom: var(--hairline) dashed var(--line);
}
.category-card__name {
  font-size: 14px;
  color: var(--ink);
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
  color: var(--muted);
}
.category-card__text {
  flex: 1 1 auto;
  min-width: 0;
  word-break: break-all;
}
/* 分类名称下的助手知识（不单开一列，避免把「操作」列挤到换行） */
.category-name-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.5;
}
.category-assist-line {
  font-size: var(--fs-base);
  color: var(--faint);
  word-break: break-all;
}
/* 分类名那一行：图标 + 名称（图标 16px、继承 currentColor） */
.category-name-line {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ink);
}
/*
 * 层级标记（A3）：**中性发丝线 chip**。这里原来是 `el-tag type="primary|success"`——
 * 大类=蓝、二级细类=绿，与 §17.3 删掉的 `getLevelTagType()` 同一个病：
 * **层级是分类，不是状态**。注意 `el-tag` 的 `type` 缺省值就是 `primary`，
 * 所以「不传 type」**不是**中性，必须换元素。
 *
 * ⚠️ 那段就地样式**已搬走**：本批把它连同「工种」「条线」「账号职能」等 8 处一起收进
 * `components/MetaChip.vue` —— 全仓的「分类的中性表达」只留**一种长相**，
 * 而不是 9 份各写一遍的 `border: 0.667px solid var(--line)`。
 */
.form-tip {
  margin-top: 4px;
  font-size: var(--fs-base);
  color: var(--faint);
  line-height: 1.6;
}
.category-card__foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: var(--hairline) dashed var(--line);
}
</style>
