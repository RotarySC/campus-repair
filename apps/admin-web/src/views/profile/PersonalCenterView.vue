<template>
  <div class="personal-center">
    <el-row :gutter="14">
      <!-- ============ 左栏：账户概览 + 资料维护 ============ -->
      <el-col :xs="24" :md="12" :lg="9">
        <!-- ① 账户概览 -->
        <el-card shadow="never" class="pc-card">
          <template #header><span class="block-title">账户概览</span></template>

          <div class="account-head">
            <el-upload
              class="avatar-upload"
              :show-file-list="false"
              accept="image/jpeg,image/png,image/webp"
              :before-upload="beforeAvatarUpload"
              :http-request="uploadAvatar"
              v-loading="avatarUploading"
            >
              <div class="avatar-wrap">
                <el-avatar :size="72" :src="userInfo?.avatarUrl || undefined" class="account-avatar">
                  {{ initial }}
                </el-avatar>
                <!--
                  悬浮遮罩：桌面档鼠标悬停（或键盘聚焦上传控件）时把头像压暗、正中浮现「更换头像」。
                  文案常驻 DOM（只用 opacity 隐藏），既作视觉提示也作上传入口的可访问名；
                  触屏没有 hover，故 @media (hover:none) 下常显，避免手机上丢掉「可点」的提示。
                -->
                <div class="avatar-mask">
                  <el-icon :size="16" aria-hidden="true"><Camera /></el-icon>
                  <span>更换头像</span>
                </div>
              </div>
            </el-upload>

            <div class="account-meta">
              <div class="account-name">{{ userInfo?.realName || '—' }}</div>
              <el-tag class="account-role" size="small" effect="light">{{ identityText }}</el-tag>
              <div class="account-line">账号：{{ userInfo?.username || '—' }}</div>
              <div class="account-line">单位：{{ userInfo?.department || '—' }}</div>
              <div class="account-line">注册时间：{{ formatDateTime(userInfo?.createdAt) }}</div>
            </div>
          </div>
        </el-card>

        <!-- ② 基本联系资料 -->
        <el-card shadow="never" class="pc-card">
          <template #header><span class="block-title">基本联系资料</span></template>
          <el-form
            ref="profileFormRef"
            :model="profileForm"
            :rules="profileRules"
            :label-width="isNarrowPhone ? undefined : '110px'"
            :label-position="isNarrowPhone ? 'top' : 'right'"
          >
            <el-form-item label="姓名" prop="realName">
              <el-input v-model="profileForm.realName" placeholder="真实姓名" maxlength="32" />
            </el-form-item>
            <el-form-item label="手机号码" prop="phone">
              <el-input v-model="profileForm.phone" placeholder="常用联系电话" maxlength="11" />
            </el-form-item>
            <!-- 账号/身份不在表单里重复：它们是只读身份信息，统一由「账户概览」承载 -->
            <!--
              行政信息化条线（原「超管/后勤」）没有「学院/部门」这个词条 → 整行不渲染（v-if="!isAdmin"）。
              注意仍然把已存 department 原样提交，不能在隐藏时把它清掉。
            -->
            <el-form-item v-if="!isAdmin" label="学院/部门" prop="department">
              <DrilldownCascader
                v-model="orgPath"
                :options="orgOptions"
                label="学院/部门"
                :placeholder="orgPlaceholder"
                separator=" "
                @change="onOrgChange"
              />
              <!--
                历史自由文本（注册页/Excel 导入写入、或字典里已移除的旧值）：回显不到时**不清空**，
                只提示；PUT /users/profile 是全量覆盖，若这里顺手置空会把用户没动过的值抹掉。
              -->
              <div v-if="departmentLegacy" class="field-note">
                原单位「{{ departmentLegacy }}」不在字典中，未重新选择将保留原值
              </div>
            </el-form-item>
            <el-form-item label="默认报修空间">
              <DrilldownCascader
                v-model="spacePath"
                :options="spaceOptions"
                label="默认报修空间"
                placeholder="选择默认宿舍/办公房间"
                @change="onSpaceChange"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="profileLoading" @click="onUpdateProfile">保存资料修改</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <!-- ============ 右栏：安全设置 + 角色化统计 ============ -->
      <el-col :xs="24" :md="12" :lg="15">
        <!-- ③ 安全密码修改 -->
        <el-card shadow="never" class="pc-card">
          <template #header><span class="block-title">安全密码修改</span></template>
          <el-form
            ref="passwordFormRef"
            :model="passwordForm"
            :rules="passwordRules"
            :label-width="isNarrowPhone ? undefined : '110px'"
            :label-position="isNarrowPhone ? 'top' : 'right'"
          >
            <el-form-item label="原密码" prop="oldPassword">
              <el-input v-model="passwordForm.oldPassword" type="password" show-password placeholder="请输入当前密码" />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="passwordForm.newPassword" type="password" show-password placeholder="请输入新密码" />
            </el-form-item>
            <el-form-item label="确认新密码" prop="confirmPassword">
              <el-input v-model="passwordForm.confirmPassword" type="password" show-password placeholder="请再次输入新密码" />
            </el-form-item>
            <el-form-item>
              <el-button type="danger" plain :loading="passwordLoading" @click="onChangePassword">确认修改密码</el-button>
            </el-form-item>
          </el-form>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            :title="`${PASSWORD_RULE_MESSAGE}；修改成功后将退出登录，请使用新密码重新登录。`"
          />
        </el-card>

        <!-- ④ 师生：我的报修概览 -->
        <el-card v-if="hasReporter" shadow="never" class="pc-card">
          <template #header>
            <div class="panel-header">
              <span class="block-title">我的报修概览</span>
              <el-button link type="primary" size="small" @click="router.push('/')">查看全部工单</el-button>
            </div>
          </template>

          <el-row :gutter="12" v-loading="summaryLoading">
            <el-col v-for="card in repairCards" :key="card.label" :xs="12" :sm="8" :md="8">
              <div class="mini-kpi">
                <div class="mini-kpi__label">{{ card.label }}</div>
                <div class="mini-kpi__value" :style="{ color: card.color }">
                  {{ card.value }}<span class="mini-kpi__unit">{{ card.unit }}</span>
                </div>
              </div>
            </el-col>
          </el-row>

          <div class="rating-row">
            <span class="rating-label">我对服务的评价均分</span>
            <template v-if="avgRating !== null">
              <el-rate :model-value="Number(avgRating)" disabled show-score score-template="{value} 分" />
            </template>
            <span v-else class="text-muted">—（暂无评价记录）</span>
          </div>

          <div class="recent-head">最近 5 条报修</div>
          <ResponsiveTable
            :items="recentOrders"
            :columns="orderColumns"
            :loading="ordersLoading"
            clickable
            empty-text="暂无报修记录，去「提交报修」发起第一条"
            @row-click="openOrderDetail"
          >
            <template #col-status="{ row }">
              <el-tag :type="statusTagType(row.status)" effect="light">
                {{ OrderStatusLabels[row.status as keyof typeof OrderStatusLabels] || row.status }}
              </el-tag>
            </template>
            <!-- 宽档表格直接渲染原始 ISO（带 T 与微秒）会换行，这里统一成「YYYY-MM-DD HH:mm」 -->
            <template #col-createdAt="{ row }">
              {{ formatDateTime(row.createdAt) }}
            </template>
          </ResponsiveTable>
        </el-card>

        <!-- ④ 师傅：我的作业概览（与上方报修概览是**并列**关系：多职能账号两块都要渲染） -->
        <el-card v-if="isWorker" shadow="never" class="pc-card">
          <template #header><span class="block-title">我的作业概览</span></template>

          <div v-loading="workerLoading">
            <el-empty
              v-if="!workerLoading && !workerProfile"
              description="尚未建立师傅档案，请联系管理员开通后查看作业数据"
              :image-size="80"
            />
            <template v-else-if="workerProfile">
              <el-descriptions :column="isNarrowPhone ? 1 : 2" border>
                <el-descriptions-item label="维修工种">
                  {{ WorkerSkillLabels[workerProfile.skillCode || ''] || workerProfile.skillCode || '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="所属校区">
                  {{ workerProfile.campusName || '—' }}
                </el-descriptions-item>
                <el-descriptions-item label="考勤状态">
                  <el-tag :type="workerProfile.onDutyStatus === 1 ? 'success' : 'info'" size="small">
                    {{ workerProfile.onDutyStatus === 1 ? '在岗' : '休息' }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="综合评分">
                  <template v-if="workerProfile.totalCompletedCount > 0">
                    {{ Number(workerProfile.ratingAvg || 0).toFixed(2) }} 分
                  </template>
                  <span v-else class="text-muted">—（暂无完工记录）</span>
                </el-descriptions-item>
                <el-descriptions-item label="历史完工单数">
                  {{ workerProfile.totalCompletedCount }} 单
                </el-descriptions-item>
                <el-descriptions-item label="今日完工">
                  {{ workerProfile.todayCompletedCount }} 单
                </el-descriptions-item>
                <el-descriptions-item label="当前负荷" :span="isNarrowPhone ? 1 : 2">
                  <div class="load-cell">
                    <el-progress
                      :percentage="workerProfile.loadRate"
                      :status="workerProfile.loadRate >= 100 ? 'exception' : undefined"
                      :stroke-width="12"
                      style="flex: 1; min-width: 120px;"
                    />
                    <span class="load-text">
                      {{ workerProfile.currentLoad }} / {{ workerProfile.maxLoad }} 单
                    </span>
                  </div>
                </el-descriptions-item>
              </el-descriptions>
            </template>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules, UploadRequestOptions } from 'element-plus';
import {
  httpClient,
  useAuthStore
} from '../../stores/auth';
import { useIdentity } from '../../composables/useIdentity';
import {
  compressImage,
  OrderStatusLabels,
  BranchOrgPlaceholders,
  identityLabelOf,
  PASSWORD_PATTERN,
  PASSWORD_RULE_MESSAGE,
  WorkerSkillLabels
} from '@campus/shared';
import type { OrgBranch } from '@campus/shared';
import type {
  DispatchOrderItemVO,
  LoginResult,
  MyWorkerProfileVO,
  OrgNodeVO,
  RepairSummaryVO,
  UserInfo
} from '@campus/shared';
import { useViewport } from '../../composables/useViewport';
import DrilldownCascader from '../../components/DrilldownCascader.vue';
import ResponsiveTable from '../../components/ResponsiveTable.vue';
import type { TableColumn } from '../../components/ResponsiveTable.vue';

const { isNarrowPhone } = useViewport();

const router = useRouter();
const authStore = useAuthStore();

const userInfo = computed<UserInfo | null>(() => authStore.userInfo);
/** 页面显隐一律按**职能**判定（口径见 CONTEXT.md）；userType 只用于单位字典的条线 */
const identity = useIdentity();
const hasReporter = computed(() => identity.isReporter.value);
const isWorker = computed(() => identity.isWorker.value);
const identityText = computed(() => identityLabelOf(userInfo.value?.userType));
const initial = computed(() => (userInfo.value?.realName || userInfo.value?.username || '—').slice(0, 1));

/**
 * 行政信息化条线没有「学院/部门」词条（整行不渲染）；其余条线各自只见自己那条线下的单位。
 * 条线取自 `useIdentity().branch`（与全局同一判据），不要再直接读 `userInfo.userType`。
 */
const isAdmin = computed(() => identity.branch.value === 'ADMIN');
/**
 * 该条线在字典里的分支键：**直接就是 userType**（同一个值、不做二次映射，见 CONTEXT.md）。
 * 服务端据 org_node.audience 逐层剪枝——教师只见学院、后勤只见处室/班组，互为选项的堆叠现象由此消除。
 */
const orgAudience = computed<OrgBranch | null>(() => (identity.branch.value as OrgBranch) || null);
const orgPlaceholder = computed(() => BranchOrgPlaceholders[orgAudience.value ?? ''] ?? '选择所属单位');

const profileFormRef = ref<FormInstance>();
const passwordFormRef = ref<FormInstance>();
const profileLoading = ref(false);
const passwordLoading = ref(false);
const avatarUploading = ref(false);
const spaceOptions = ref<any[]>([]);
const spacePath = ref<number[]>([]);
const orgOptions = ref<OrgNodeVO[]>([]);
const orgPath = ref<number[]>([]);
/** 回显不到字典的历史自由文本；非空时提交原值而不是空串（全量覆盖语义下的防误清） */
const departmentLegacy = ref('');

const summaryLoading = ref(false);
const ordersLoading = ref(false);
const workerLoading = ref(false);
const repairSummary = ref<RepairSummaryVO | null>(null);
const recentOrders = ref<DispatchOrderItemVO[]>([]);
const workerProfile = ref<MyWorkerProfileVO | null>(null);

const profileForm = reactive({
  realName: '',
  phone: '',
  department: '',
  defaultSpaceId: null as number | null
});

const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
});

const profileRules: FormRules = {
  realName: [
    { required: true, message: '姓名不能为空', trigger: 'blur' },
    { max: 32, message: '姓名长度不能超过 32 个字符', trigger: 'blur' }
  ],
  phone: [
    { required: true, message: '手机号不能为空', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '请输入 11 位大陆手机号', trigger: 'blur' }
  ],
  department: [{ max: 64, message: '学院/部门长度不能超过 64 个字符', trigger: 'blur' }]
};

const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (value && !PASSWORD_PATTERN.test(value)) {
          callback(new Error(PASSWORD_RULE_MESSAGE));
          return;
        }
        callback();
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的新密码不一致'));
          return;
        }
        callback();
      },
      trigger: 'blur'
    }
  ]
};

/** 报修概览 KPI：空值一律渲染 `—`，不伪造兜底数字 */
const repairCards = computed(() => {
  const s = repairSummary.value;
  const num = (v: number | undefined) => (s ? String(v ?? 0) : '—');
  return [
    { label: '累计报修', value: num(s?.totalCount), unit: '单', color: '#1677FF' },
    { label: '待派单', value: num(s?.pendingDispatchCount), unit: '单', color: '#FA8C16' },
    { label: '在办', value: num(s?.processingCount), unit: '单', color: '#722ED1' },
    { label: '待验收', value: num(s?.pendingAcceptanceCount), unit: '单', color: '#13C2C2' },
    { label: '已结单', value: num(s?.completedCount), unit: '单', color: '#52C41A' },
    { label: '已作废', value: num(s?.cancelledCount), unit: '单', color: '#8C8C8C' }
  ];
});

/** 无有效评价时必须为 null → 页面渲染「—」（不得兜底成 5 星） */
const avgRating = computed(() => (repairSummary.value?.avgRating ?? null));

const orderColumns: TableColumn<DispatchOrderItemVO>[] = [
  { key: 'orderNo', label: '工单号', width: 165, card: 'title' },
  {
    key: 'status',
    label: '状态',
    width: 100,
    align: 'center',
    card: 'tag',
    cardTag: (row) => ({
      text: OrderStatusLabels[row.status as keyof typeof OrderStatusLabels] || row.status,
      type: statusTagType(row.status)
    })
  },
  { key: 'spaceFullPath', label: '报修位置', minWidth: 130, showOverflowTooltip: true },
  { key: 'createdAt', label: '提单时间', width: 135, valueType: 'datetime' }
];

function statusTagType(status: string): 'success' | 'primary' | 'danger' | 'warning' | 'info' {
  switch (status) {
    case 'COMPLETED': return 'success';
    case 'PENDING_ACCEPTANCE': return 'primary';
    case 'REWORK_PENDING': return 'danger';
    case 'IN_PROGRESS': case 'ASSIGNED': return 'warning';
    default: return 'info';
  }
}

function formatDateTime(value?: string | null): string {
  if (!value) return '—';
  return String(value).replace('T', ' ').slice(0, 16);
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

function onSpaceChange() {
  profileForm.defaultSpaceId = spacePath.value.length
    ? spacePath.value[spacePath.value.length - 1]
    : null;
}

/** 按 fullPathName **整串精确匹配**定位路径（字典与 sys_user.department 存的是同一串，无需解析分隔符） */
function findOrgPathByFullPath(list: OrgNodeVO[], fullPath: string, prefix: number[] = []): number[] | null {
  for (const node of list) {
    const curPath = [...prefix, node.id];
    if (node.fullPathName === fullPath) return curPath;
    if (node.children?.length) {
      const r = findOrgPathByFullPath(node.children, fullPath, curPath);
      if (r) return r;
    }
  }
  return null;
}

/** 沿 id 路径取末端节点（用于把选中的级联路径写回 department 文案） */
function orgNodeByPath(list: OrgNodeVO[], ids: number[]): OrgNodeVO | null {
  let level = list;
  let hit: OrgNodeVO | null = null;
  for (const id of ids) {
    hit = level.find((node) => node.id === id) || null;
    if (!hit) return null;
    level = hit.children || [];
  }
  return hit;
}

/**
 * 把已存的 department 文案回显成级联路径。
 * 回显不到（注册页/Excel 导入写的自由文本、或字典里已删掉的旧值）时**不置空** department：
 * `PUT /users/profile` 是全量覆盖，若在这里顺手清空，会把用户没动过的旧值抹掉。
 */
function syncOrgPath() {
  const saved = (userInfo.value?.department || '').trim();
  const path = saved ? findOrgPathByFullPath(orgOptions.value, saved) : null;
  orgPath.value = path || [];
  departmentLegacy.value = saved && !path ? saved : '';
}

/** 只有用户真的动了选择器才改写 department（含「清空」——那是明确的用户意图） */
function onOrgChange() {
  departmentLegacy.value = '';
  profileForm.department = orgPath.value.length
    ? orgNodeByPath(orgOptions.value, orgPath.value)?.fullPathName || ''
    : '';
}

function applyUserInfoToForm() {
  const info = userInfo.value;
  if (!info) return;
  profileForm.realName = info.realName || '';
  profileForm.phone = info.phone || '';
  profileForm.department = info.department || '';
  profileForm.defaultSpaceId = info.defaultSpaceId ?? null;
}

/**
 * 进页面先用服务端资料刷新登录快照（localStorage 里的 user_info 是登录时刻的副本）。
 * 失败时静默沿用本地快照，不阻塞页面其它区块。
 */
async function refreshProfile() {
  try {
    const res = await httpClient.get<UserInfo>('/users/me');
    // 形状校验：旧版该端点只返回 userId/username/roles，直接覆盖会丢 userType →
    // 角色化的统计区会整块消失（本轮实测踩到），因此只在字段齐备时替换快照。
    if (res.code === 200 && res.data?.id && res.data?.userType) {
      authStore.userInfo = res.data;
      localStorage.setItem('user_info', JSON.stringify(res.data));
    }
  } catch (_) {
    // 非关键路径：保留本地快照
  }
}

async function loadSpaceOptions() {
  try {
    const res = await httpClient.get<any[]>('/spatial/tree', { params: { onlyEnabled: true } });
    if (res.code === 200) {
      spaceOptions.value = res.data || [];
      if (profileForm.defaultSpaceId) {
        spacePath.value = findIdPath(spaceOptions.value, profileForm.defaultSpaceId) || [];
      }
    }
  } catch (_) {}
}

/** 单位组织字典（年级 → 学院 / 教职工 → 处室 → 班组）；**按身份只取所属分支**，取数后立刻回显已存文案 */
async function loadOrgOptions() {
  // 管理员不渲染该词条，连取数都省掉
  if (isAdmin.value) return;
  try {
    const res = await httpClient.get<OrgNodeVO[]>('/org/tree', {
      params: { onlyEnabled: true, audience: orgAudience.value ?? undefined }
    });
    if (res.code === 200) {
      orgOptions.value = res.data || [];
    }
  } catch (_) {}
  syncOrgPath();
}

async function onUpdateProfile() {
  if (!profileFormRef.value) return;
  const valid = await profileFormRef.value.validate().catch(() => false);
  if (!valid) return;

  profileLoading.value = true;
  try {
    const payload = {
      realName: profileForm.realName,
      phone: profileForm.phone,
      department: profileForm.department,
      defaultSpaceId: profileForm.defaultSpaceId ? Number(profileForm.defaultSpaceId) : null
    };
    // 全量覆盖：department/defaultSpaceId 传空即清空
    const res = await httpClient.put<null>('/users/profile', payload);
    if (res.code === 200) {
      ElMessage.success('资料保存成功');
      applyUserInfoToForm();
      await refreshProfile();
      syncOrgPath();
    } else if (res.code === 1007) {
      // 手机号冲突：落到表单内联错误，而不是只弹一条消息
      profileFormRef.value.validateField('phone');
      ElMessage.error(res.message || '该手机号已被其他账号使用');
    } else {
      ElMessage.error(res.message || '更新失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '更新出错');
  } finally {
    profileLoading.value = false;
  }
}

/** 头像上传：前端压缩 → 通用上传接口 → 独立头像端点（不会覆盖姓名/手机） */
async function uploadAvatar(options: UploadRequestOptions) {
  avatarUploading.value = true;
  try {
    const compressed = await compressImage(options.file as File, { maxDimension: 512, quality: 0.85 });
    const formData = new FormData();
    formData.append('file', new File([compressed], (options.file as File).name, { type: 'image/jpeg' }));

    const uploaded = await httpClient.post<{ url: string }>('/common/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
    if (uploaded.code !== 200 || !uploaded.data?.url) {
      throw new Error(uploaded.message || '头像上传失败');
    }

    const saved = await httpClient.put<UserInfo>('/users/me/avatar', { avatarUrl: uploaded.data.url });
    if (saved.code !== 200 || !saved.data) {
      throw new Error(saved.message || '头像保存失败');
    }

    authStore.userInfo = saved.data;
    localStorage.setItem('user_info', JSON.stringify(saved.data));
    ElMessage.success('头像已更新');
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '头像上传失败');
  } finally {
    avatarUploading.value = false;
  }
}

function beforeAvatarUpload(file: File): boolean {
  const allowed = ['image/jpeg', 'image/png', 'image/webp'];
  if (!allowed.includes(file.type)) {
    ElMessage.warning('头像仅支持 JPG / PNG / WEBP 格式');
    return false;
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning('头像文件不能超过 2MB');
    return false;
  }
  return true;
}

async function onChangePassword() {
  if (!passwordFormRef.value) return;
  const valid = await passwordFormRef.value.validate().catch(() => false);
  if (!valid) return;

  passwordLoading.value = true;
  try {
    // 改密端点会连带返回重新签发的令牌（首登强制改密时旧票带着 pwdChange 标记），
    // 但个人中心刻意走「改密后重新登录」，因此这里只需要成功/失败，不消费 data。
    const res = await httpClient.put<LoginResult>('/users/password/change', {
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    });
    if (res.code === 200) {
      ElMessage.success('密码修改成功，请重新登录');
      authStore.logout();
      router.push('/login');
    } else {
      ElMessage.error(res.message || '修改密码失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '修改出错');
  } finally {
    passwordLoading.value = false;
  }
}

async function loadRepairSummary() {
  summaryLoading.value = true;
  try {
    const res = await httpClient.get<RepairSummaryVO>('/users/me/repair-summary');
    if (res.code === 200 && res.data) {
      repairSummary.value = res.data;
    }
  } catch (_) {
  } finally {
    summaryLoading.value = false;
  }
}

async function loadRecentOrders() {
  ordersLoading.value = true;
  try {
    const res = await httpClient.get<{ records: DispatchOrderItemVO[]; total: number }>(
      '/work-orders/my',
      { params: { page: 1, size: 5 } }
    );
    if (res.code === 200 && res.data) {
      recentOrders.value = res.data.records || [];
    }
  } catch (_) {
  } finally {
    ordersLoading.value = false;
  }
}

async function loadWorkerProfile() {
  workerLoading.value = true;
  try {
    const res = await httpClient.get<MyWorkerProfileVO | null>('/users/me/worker-profile');
    if (res.code === 200) {
      // 未建档的师傅返回 data=null，页面降级为 el-empty
      workerProfile.value = res.data || null;
    }
  } catch (_) {
  } finally {
    workerLoading.value = false;
  }
}

function openOrderDetail(row: DispatchOrderItemVO) {
  if (row.id) router.push(`/student/orders/${row.id}`);
}

onMounted(async () => {
  // 顺序刻意固定：先刷新服务端资料（含 defaultSpaceId），再拉空间树回显已选路径，
  // 否则会出现「表单已有值但选择器显示为空」（等价于旧版 restoreDraft 的坑）
  await refreshProfile();
  applyUserInfoToForm();
  await loadSpaceOptions();
  await loadOrgOptions();

  // 多职能账号（演示数据里 worker02 兼「维修作业」+「报修人」）**两块概览都要出数**：
  // 这里刻意用两个并列 if —— 原先写成 if/else if，报修概览会把它吞掉，
  // 结果是兼职师傅永远看不到「我的作业概览」、也不调 /users/me/worker-profile。
  if (hasReporter.value) {
    loadRepairSummary();
    loadRecentOrders();
  }
  if (isWorker.value) {
    loadWorkerProfile();
  }
});
</script>

<style scoped>
.personal-center {
  padding: 16px;
}
.pc-card {
  margin-bottom: 14px;
}
.block-title {
  font-weight: 600;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
.text-muted {
  color: #9ca3af;
  font-size: 13px;
}
.field-note {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.5;
  color: #9ca3af;
}

/* 账户概览 */
.account-head {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}
.avatar-upload {
  cursor: pointer;
}
/* 头像 + 悬浮遮罩：遮罩压暗头像并在正中显示「更换头像」 */
.avatar-wrap {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 50%;
  overflow: hidden;
  line-height: 0;
}
.account-avatar {
  background-color: #1677ff;
  color: #ffffff;
  font-size: 26px;
}
.avatar-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border-radius: 50%;
  background-color: rgba(0, 0, 0, 0.45);
  color: #ffffff;
  font-size: 12px;
  line-height: 1.2;
  text-align: center;
  opacity: 0;
  transition: opacity 0.2s ease;
}
.avatar-wrap:hover .avatar-mask,
.avatar-upload:focus-within .avatar-mask {
  opacity: 1;
}
/* 触屏没有 hover：常显遮罩，否则手机上完全看不出头像可点 */
@media (hover: none) {
  .avatar-mask {
    opacity: 1;
  }
}
.account-meta {
  flex: 1;
  min-width: 160px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
/* 身份标签收缩到文字宽度：.account-meta 是 column flex，默认 stretch 会把标签拉满整行 */
.account-role {
  align-self: flex-start;
}
.account-name {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}
.account-line {
  font-size: 13px;
  color: #6b7280;
  /* 用 overflow-wrap 而不是 word-break:break-all：否则「2026-09-13 01:45」会被从数字中间掰断 */
  overflow-wrap: break-word;
}

/* 报修概览 */
.mini-kpi {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.mini-kpi__label {
  font-size: 12px;
  color: #6b7280;
}
.mini-kpi__value {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.4;
}
.mini-kpi__unit {
  font-size: 12px;
  font-weight: 400;
  color: #6b7280;
  margin-left: 2px;
}
.rating-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 4px 0 12px;
}
.rating-label {
  font-size: 13px;
  color: #6b7280;
}
.recent-head {
  font-size: 13px;
  font-weight: 600;
  color: #1f2937;
  margin-bottom: 8px;
}

/* 师傅负荷 */
.load-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.load-text {
  font-size: 13px;
  color: #6b7280;
  white-space: nowrap;
}
</style>
