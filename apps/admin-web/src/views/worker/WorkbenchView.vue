<template>
  <div class="worker-workbench">
    <!-- 三栏任务切换：页面级筛选，与「我的工单」同法放在卡片之外 -->
    <div class="page-toolbar">
      <el-tabs v-model="activeTab" @tab-change="handleTabChange">
        <el-tab-pane name="ASSIGNED">
          <template #label>
            待承接
            <el-badge v-show="counts.assignedCount > 0" :value="counts.assignedCount" :max="99" class="tab-badge" />
          </template>
        </el-tab-pane>
        <el-tab-pane name="IN_PROGRESS">
          <template #label>
            维修中
            <el-badge v-show="counts.inProgressCount > 0" :value="counts.inProgressCount" :max="99" class="tab-badge" />
          </template>
        </el-tab-pane>
        <el-tab-pane name="HISTORY">
          <template #label>
            历史记录
            <el-badge v-show="counts.historyCount > 0" :value="counts.historyCount" :max="99" class="tab-badge" />
          </template>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-card shadow="never">
      <!-- 搜索栏 -->
      <div class="search-container">
        <el-input
          v-model="keyword"
          placeholder="搜索单号 / 报修人 / 故障位置..."
          clearable
          :prefix-icon="Search"
          @keyup.enter="fetchOrders"
          @clear="fetchOrders"
        >
          <template #append>
            <el-button :icon="Search" @click="fetchOrders">搜索</el-button>
          </template>
        </el-input>
      </div>

      <!--
        宽档（≥1400）= el-table 一行一单：信息横向铺成列，不再把移动端卡片拉成全宽
        （那样描述框 1562px 里只有 182px 是字，窗口越宽越浪费）；
        窄档（<1400）= 卡片。**1400 是「表格真装得下」的最小视口**（列宽合计 1060px，
        内容区 ≈ 视口−312），低于它表格会横滚、固定的操作列还会盖住报修人——见 columns 上的说明。
      -->
      <ResponsiveTable
        :items="orders"
        :columns="columns"
        :loading="loading"
        :card-below="1400"
        empty-text="暂无相关任务工单"
      >
        <!-- 单号：加急小标签内联（同调度中心 .order-no-wrap），省一列 -->
        <template #col-orderNo="{ row }">
          <span class="order-no-wrap">
            <span class="order-no">{{ row.orderNo }}</span>
            <!-- 加急＝警戒：§3.3 的「实心圆」，不是又一个彩色胶囊。
                 原先这里是 `el-tag type="danger"`（红底白字），是本页除状态列外最响的一处颜色。 -->
            <StatusRing v-if="row.urgencyLevel === 2" variant="dot" label="加急" />
          </span>
        </template>

        <template #col-status="{ row }">
          <!-- §5 的 V7 修订：状态不再用带底色的 `el-tag`，改用 9px 圆环 + 中性字 -->
          <StatusRing :status="row.status" />
        </template>

        <!-- 承诺截止：挂起期间 SLA 冻结，显示冻结说明而不是一个会误导人的日期 -->
        <template #col-slaDeadline="{ row }">
          <span v-if="row.status === 'SUSPENDED'" class="sla-frozen" :title="`累计挂起 ${row.suspendCount || 0} 次`">
            ⏸️ 已冻结
          </span>
          <span v-else-if="row.slaDeadline">{{ formatDateTime(row.slaDeadline) }}</span>
          <span v-else>—</span>
        </template>

        <!-- 报修人列直接是 tel: 链接：现场一键拨号，少一列按钮；号码直接可读（可能要换机拨打） -->
        <template #col-applicantName="{ row }">
          <a :href="'tel:' + row.applicantPhone" class="tel-link" :title="`拨打 ${row.applicantName} ${row.applicantPhone}`">
            <el-icon :size="14"><Phone /></el-icon>
            <span>{{ row.applicantName }}</span>
            <span class="tel-number">{{ row.applicantPhone }}</span>
          </a>
        </template>

        <!-- 仅窄档卡片：故障描述 + 挂起原因横幅（宽档表格放不下，靠 title 提示） -->
        <template #col-description="{ row }">
          <div class="fault-desc" :title="row.description">{{ row.description }}</div>
          <div v-if="row.status === 'SUSPENDED' && row.dispatchReason" class="suspend-banner">
            <!-- 警戒走**填充**而不是颜色面积（§3.3 / §4.6 G4 口径）：
                 9px 实心点 + 中性字 + 发丝线边框。原先这里是 `WarningFilled` 图标
                 + 琥珀底 + 琥珀边 + 琥珀字——四个颜色维度在说同一件事，而事情只有一件。 -->
            <StatusRing variant="dot" />
            <span>{{ row.dispatchReason }}</span>
          </div>
        </template>

        <!-- 操作列：主操作是按钮，挂起/转派是次级动作（窄档卡片里三个并排，宽档靠 link） -->
        <template #col-actions="{ row }">
          <div class="action-buttons">
            <template v-if="row.status === 'ASSIGNED'">
              <el-button type="primary" size="small" :loading="operatingId === row.id" @click="handleAccept(row)">
                接单出发
              </el-button>
            </template>

            <template v-if="row.status === 'IN_PROGRESS'">
              <el-button type="primary" size="small" @click="goToFinish(row)">完工填报</el-button>
              <el-button type="warning" link size="small" @click="openSuspendModal(row)">挂起</el-button>
              <el-button type="danger" link size="small" @click="openTransferModal(row)">转派</el-button>
            </template>

            <template v-if="row.status === 'SUSPENDED'">
              <el-button type="success" size="small" :loading="operatingId === row.id" @click="handleResume(row)">
                解除挂起
              </el-button>
              <el-button type="danger" link size="small" @click="openTransferModal(row)">转派</el-button>
            </template>

            <!--
              历史档（待验收/已结单/返工待派/已作废）：师傅侧**没有业务动作**，
              但闭环后仍需回看「为什么被差评、流转到哪一步」——给一个只读的「查看」，
              否则这一列在行数最多的历史档里是纯空白（实测 192px/1126px = 17% 死宽，
              窄档还会在每张卡底部留一条无内容的虚线）。
            -->
            <template v-if="isHistoryStatus(row.status)">
              <el-button type="primary" link size="small" @click="openDetail(row)">查看</el-button>
            </template>
          </div>
        </template>
      </ResponsiveTable>
    </el-card>

    <!-- 工单挂起弹窗 -->
    <el-dialog
      v-model="suspendDialogVisible"
      title="申请施工挂起"
      width="min(480px, 94vw)"
      :close-on-click-modal="false"
    >
      <AlertBanner>挂起后 SLA 倒计时冻结，解除挂起时自动顺延时限。</AlertBanner>
      <el-form label-position="top">
        <el-form-item label="挂起原因" required>
          <el-select v-model="suspendReasonTypeCode" style="width: 100%;">
            <el-option
              v-for="reason in suspendReasonOptions"
              :key="reason.value"
              :label="reason.label"
              :value="reason.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="详细说明" required>
          <el-input
            v-model="suspendDetail"
            type="textarea"
            :rows="3"
            placeholder="请输入现场挂起详细说明（如缺少何种具体规格耗材）"
          />
        </el-form-item>
        <el-form-item label="预计恢复时间">
          <el-input v-model="estimatedResumeTime" placeholder="如: 2026-09-08 10:00" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="suspendDialogVisible = false">取消</el-button>
        <el-button type="warning" :loading="operatingId !== null" @click="submitSuspend">确认挂起</el-button>
      </template>
    </el-dialog>

    <!-- 申请转派弹窗 -->
    <el-dialog
      v-model="transferDialogVisible"
      title="现场申请工单转派"
      width="min(480px, 94vw)"
      :close-on-click-modal="false"
    >
      <el-alert
        title="申请转派后，工单将移出您的待办并回退至调度中心重新分派。"
        type="info"
        :closable="false"
        style="margin-bottom: 14px;"
      />
      <el-form label-position="top">
        <el-form-item label="建议工种" required>
          <el-select v-model="recommendSkillCode" style="width: 100%;">
            <el-option
              v-for="skill in skillOptions"
              :key="skill.value"
              :label="skill.label"
              :value="skill.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="转派原因" required>
          <el-input
            v-model="transferReason"
            type="textarea"
            :rows="3"
            placeholder="请详细说明无法处置的现场实际原因（如超出电气范畴需暗管水暖开凿）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="operatingId !== null" @click="submitTransfer">确认申请转派</el-button>
      </template>
    </el-dialog>

    <!--
      历史单回看抽屉（只读）：评价（星级/标签/评语/返工督办）+ 全流程审计时间轴。
      后端零改动——`GET /work-orders/{id}/evaluation-detail` 与 `/{id}/timeline` 的放行集合
      本来就是「报修人本人 ∨ **当前指派师傅** ∨ 调度/平台管理」（`WorkOrderController:58-80`、`:200-206`，
      判定在 Service 的 `assertReadable`）；时间轴直接复用管理端那个 `WorkOrderTimeline.vue`。
    -->
    <el-drawer
      v-model="detailDrawerVisible"
      :title="`工单回看 - ${detailOrder?.orderNo || ''}`"
      size="min(520px, 94vw)"
      direction="rtl"
    >
      <div v-loading="evaluationLoading" class="detail-drawer">
        <div class="drawer-section-title">完工信息</div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="维修总结">{{ evaluation?.repairSummary || '—' }}</el-descriptions-item>
          <el-descriptions-item label="耗材费用">
            {{ evaluation?.materialCost != null ? `¥ ${Number(evaluation.materialCost).toFixed(2)}` : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="完工时间">
            {{ formatDateTime(evaluation?.finishReportedAt) }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="drawer-section-title">服务质检评价</div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="综合评价星级">
            <el-rate v-if="evaluation?.ratingStar" :model-value="evaluation.ratingStar" disabled />
            <span v-else class="muted">尚未评价</span>
          </el-descriptions-item>
          <el-descriptions-item label="确认方式">
            {{ evaluation?.evaluatedAt
              ? (evaluation.isAutoConfirmed === 1 ? '72h 超时自动闭环结单' : '师生主动验收评价')
              : '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="满意标签">
            <template v-if="evaluation?.ratingTags && evaluation.ratingTags.length">
              <el-tag v-for="tag in evaluation.ratingTags" :key="tag" class="tag-gap">{{ tag }}</el-tag>
            </template>
            <span v-else class="muted">—</span>
          </el-descriptions-item>
          <el-descriptions-item label="返工督办">
            {{ evaluation?.reworkFlag === 1 ? '已触发差评返工流程' : '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="用户评语">
            {{ evaluation?.evaluationComment || '未填写评语' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="evaluation?.arbitrationNote" label="主管仲裁说明">
            {{ evaluation.arbitrationNote }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="drawer-section-title">全流程审计时间轴</div>
        <WorkOrderTimeline v-if="detailOrder" :order-id="detailOrder.id" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import AlertBanner from '../../components/AlertBanner.vue';
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Phone,
  Search
} from '@element-plus/icons-vue';
import { httpClient } from '../../stores/auth';
import {
  OrderStatusLabels,
  DispatchOrderItemVO,
  WorkerWorkbenchCountVO,
  WorkerOrderListResultVO,
  WorkOrderEvaluationVO,
  formatDateTime
} from '@campus/shared';
import ResponsiveTable from '../../components/ResponsiveTable.vue';
import type { TableColumn } from '../../components/ResponsiveTable.vue';
import StatusRing from '../../components/StatusRing.vue';
import WorkOrderTimeline from '../../components/WorkOrderTimeline.vue';

const router = useRouter();

/**
 * 「历史记录」档的状态集合——**必须与后端 HISTORY 查询一致**（`WorkOrderService:547`：
 * `PENDING_ACCEPTANCE / COMPLETED / REWORK_PENDING / CANCELLED`）。
 * 这四种状态对师傅**没有业务动作**，所以「操作」列给的是只读的「查看」而不是命令按钮。
 */
const HISTORY_STATUSES: readonly string[] = ['PENDING_ACCEPTANCE', 'COMPLETED', 'REWORK_PENDING', 'CANCELLED'];

function isHistoryStatus(status: string): boolean {
  return HISTORY_STATUSES.includes(status);
}

/**
 * 列定义：**宽档表格与窄档卡片共用一份**（`ResponsiveTable`）。
 *
 * 宽档 7 列，合计 **1054px**——「工单号/状态/故障细类/报修位置/承诺截止/报修人/操作」，
 * 目标是把一条记录压成**一行**而不是 250~350px 高的卡片；
 * 故障描述与预约时段标 `cardOnly`：窄档卡片要读全，宽档表格靠列数控宽。
 *
 * ⚠️ **每个宽度都是按内容实测算出来的，不是随手取的**——窄一点点就会**换行或出省略号**，
 * 行高从 42px 涨到 66px、号码被截成 `13800000003...`，白拿回一半密度
 * （实测踩过三次：操作列 166px 让「转派」掉到第二行、承诺截止 130px 让「2026-09-26 22:42」
 * 断成两行、报修人 158px 让手机号末尾出省略号）。所以：
 *   工单号 196（`BX` + 14 位 = 16 字符 ≈125px + 内联「加急」标签 42 + 间距 6 + 单元格内边距 24）
 *   状态 84 / 承诺截止 150
 *   报修人 172（`tel:` 链接 = 图标 14 + 姓名 ≈39 + 号码 ≈73 + 间距 8 + 内边距 24 = 158，
 *              留 14px 余量；**不要压到 158**，实测那一档号码会出省略号）
 *   操作 192（「完工填报」88 + 「挂起」30 + 「转派」30 + 间距 16 + 内边距 24）
 *   故障细类 / 报修位置留 `minWidth` 130，吃剩余空间，超长靠 `showOverflowTooltip`
 *
 * ⚠️ **横向溢出会盖住内容**：`el-table` 装不下就横滚，而 `fixed: 'right'` 的「操作」列会
 * **浮在**被滚动的内容之上——实测 1272 档（内容区 958px）用 1033px 的列集时，操作列正好盖住
 * 「报修人」。所以 `card-below` 必须按「表格真装得下」定：内容区 ≈ 视口 − 312
 * （侧边栏 240 + 页内边距 32 + 卡片内边距 40），1054 + 余量 ⇒ **1400**。
 * **代价**：1366/1280 笔记本会落到卡片档（实测 1272 档卡片 943px 宽，标签+值左对齐，读起来没问题）。
 * 若要把阈值压到 1366，得把「报修人」的号码收回 `title`（列宽 172→100，合计降到 982 ⇒ 阈值 1320）。
 * **改列宽要一起复核这两个数（列宽合计、card-below）。**
 */
const columns: TableColumn<DispatchOrderItemVO>[] = [
  { key: 'orderNo', label: '工单号', width: 196, card: 'title' },
  {
    key: 'status',
    label: '状态',
    width: 84,
    align: 'center',
    card: 'tag',
    cardTag: (row) => ({
      text: OrderStatusLabels[row.status as keyof typeof OrderStatusLabels] || row.status,
      // §5 的 V7 修订：窄档卡片与宽档表格**共用同一份状态色值**（`ResponsiveTable` 的 ring 档
      // 会把它渲染成 9px 圆环，见该组件 `CardTag.type` 的注释）
      type: 'ring',
      status: row.status
    })
  },
  { key: 'categoryFullName', label: '故障细类', minWidth: 130, showOverflowTooltip: true },
  { key: 'description', label: '故障描述', minWidth: 170, cardOnly: true },
  { key: 'spaceFullPath', label: '报修位置', minWidth: 130, showOverflowTooltip: true },
  { key: 'slaDeadline', label: '承诺截止', width: 150 },
  { key: 'appointmentPeriod', label: '预约时段', width: 130, cardOnly: true },
  { key: 'applicantName', label: '报修人', width: 172 },
  { key: 'actions', label: '操作', width: 192, align: 'center', fixed: 'right', card: 'action' }
];

const activeTab = ref<'ASSIGNED' | 'IN_PROGRESS' | 'HISTORY'>('ASSIGNED');
const keyword = ref('');
const loading = ref(false);
const operatingId = ref<number | null>(null);

const orders = ref<DispatchOrderItemVO[]>([]);
const counts = ref<WorkerWorkbenchCountVO>({
  assignedCount: 0,
  inProgressCount: 0,
  historyCount: 0
});

// 挂起弹窗相关状态
const suspendDialogVisible = ref(false);
const activeOrderForAction = ref<DispatchOrderItemVO | null>(null);
const suspendReasonTypeCode = ref('MATERIAL_SHORTAGE');
const suspendDetail = ref('');
const estimatedResumeTime = ref('');

const suspendReasonOptions = [
  { label: '现场缺料 (需采购备件)', value: 'MATERIAL_SHORTAGE' },
  { label: '外协阻滞 (需外部供应商协同)', value: 'OUTSOURCING_BLOCK' },
  { label: '报修人改期 (住户现场不在)', value: 'USER_TIME_CHANGE' },
  { label: '其他现场特殊情况', value: 'OTHER' }
];

// 转派弹窗相关状态
const transferDialogVisible = ref(false);
const recommendSkillCode = ref('PLUMBER');
const transferReason = ref('');

const skillOptions = [
  { label: '水暖管道 (PLUMBER)', value: 'PLUMBER' },
  { label: '电工电气 (ELECTRICIAN)', value: 'ELECTRICIAN' },
  { label: '暖通空调 (HVAC)', value: 'HVAC' },
  { label: '门窗锁具 (LOCKSMITH)', value: 'LOCKSMITH' },
  { label: '弱电网络 (NETWORK)', value: 'NETWORK' },
  { label: '综合维修 (GENERAL)', value: 'GENERAL' }
];

// 历史单回看抽屉（只读）
const detailDrawerVisible = ref(false);
const detailOrder = ref<DispatchOrderItemVO | null>(null);
const evaluation = ref<WorkOrderEvaluationVO | null>(null);
const evaluationLoading = ref(false);

/**
 * 打开历史单回看抽屉。
 *
 * `evaluation-detail` 对「没有评价记录」的单**不会报错**——Service 走 `else` 分支返回
 * `ratingTags/repairImages = []` 且评价字段全 `null`（`WorkOrderService:1103-1106`），
 * 所以已作废、待验收这类单也能安全打开，界面显示「尚未评价」即可。
 */
async function openDetail(row: DispatchOrderItemVO) {
  detailOrder.value = row;
  evaluation.value = null;
  detailDrawerVisible.value = true;
  evaluationLoading.value = true;
  try {
    const res = await httpClient.get<WorkOrderEvaluationVO>(`/work-orders/${row.id}/evaluation-detail`);
    if (res.code === 200 && res.data) {
      evaluation.value = res.data;
    }
  } catch (err: any) {
    ElMessage.error(err.message || '获取工单评价详情失败');
  } finally {
    evaluationLoading.value = false;
  }
}

// 站内通知统一由外壳顶栏承载，本页不再自带通知入口

onMounted(() => {
  fetchOrders();
});

function handleTabChange() {
  orders.value = [];
  fetchOrders();
}

async function fetchOrders() {
  loading.value = true;
  try {
    const res = await httpClient.get<WorkerOrderListResultVO>('/work-orders/worker-pool', {
      params: {
        tab: activeTab.value,
        keyword: keyword.value || undefined,
        page: 1,
        size: 50
      }
    });

    if (res.code === 200 && res.data) {
      orders.value = res.data.orders.records || [];
      counts.value = res.data.counts || counts.value;
    } else {
      ElMessage.error(res.message || '获取工单失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '网络异常');
  } finally {
    loading.value = false;
  }
}

// 师傅业务动作 0: 前往完工填报台
function goToFinish(order: DispatchOrderItemVO) {
  router.push(`/worker/finish/${order.id}`);
}

// 师傅业务动作 1: 接单出发
async function handleAccept(order: DispatchOrderItemVO) {
  try {
    await ElMessageBox.confirm(
      `确认接单【${order.orderNo}】并立即出发前往现场吗？系统将记录出发时间戳。`,
      '接单确认',
      { type: 'info' }
    );
  } catch {
    return;
  }

  operatingId.value = order.id;
  try {
    const res = await httpClient.post<null>(`/work-orders/${order.id}/accept`);
    if (res.code === 200) {
      ElMessage.success('已成功接单出发！');
      fetchOrders();
    } else {
      ElMessage.error(res.message || '接单失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '接单异常');
  } finally {
    operatingId.value = null;
  }
}

// 师傅业务动作 2: 工单挂起
function openSuspendModal(order: DispatchOrderItemVO) {
  activeOrderForAction.value = order;
  suspendDetail.value = '';
  estimatedResumeTime.value = '';
  suspendDialogVisible.value = true;
}

async function submitSuspend() {
  if (!suspendDetail.value.trim()) {
    ElMessage.warning('请输入挂起详细说明');
    return;
  }

  operatingId.value = activeOrderForAction.value?.id ?? null;
  try {
    const res = await httpClient.post<null>(
      `/work-orders/${activeOrderForAction.value?.id}/suspend`,
      {
        suspendReasonType: suspendReasonTypeCode.value,
        reasonDetail: suspendDetail.value.trim(),
        estimatedResumeTime: estimatedResumeTime.value.trim() || undefined
      }
    );

    if (res.code === 200) {
      ElMessage.success('工单已挂起，SLA倒计时已暂停');
      suspendDialogVisible.value = false;
      fetchOrders();
    } else {
      ElMessage.error(res.message || '挂起操作失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '挂起异常');
  } finally {
    operatingId.value = null;
  }
}

// 师傅业务动作 3: 解除挂起
async function handleResume(order: DispatchOrderItemVO) {
  try {
    await ElMessageBox.confirm(
      `确认恢复工单【${order.orderNo}】的现场施工吗？挂起耗时将自动顺延SLA时限。`,
      '解除挂起确认',
      { type: 'info' }
    );
  } catch {
    return;
  }

  operatingId.value = order.id;
  try {
    const res = await httpClient.post<null>(`/work-orders/${order.id}/resume`);
    if (res.code === 200) {
      ElMessage.success('已解除挂起，恢复施工');
      fetchOrders();
    } else {
      ElMessage.error(res.message || '恢复施工失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '解除挂起异常');
  } finally {
    operatingId.value = null;
  }
}

// 师傅业务动作 4: 申请转派
function openTransferModal(order: DispatchOrderItemVO) {
  activeOrderForAction.value = order;
  transferReason.value = '';
  transferDialogVisible.value = true;
}

async function submitTransfer() {
  if (!transferReason.value.trim()) {
    ElMessage.warning('请输入转派原因说明');
    return;
  }

  operatingId.value = activeOrderForAction.value?.id ?? null;
  try {
    const res = await httpClient.post<null>(
      `/work-orders/${activeOrderForAction.value?.id}/transfer-apply`,
      {
        transferReason: transferReason.value.trim(),
        recommendSkillCode: recommendSkillCode.value
      }
    );

    if (res.code === 200) {
      ElMessage.success('申请转派成功，已回退调度中心');
      transferDialogVisible.value = false;
      fetchOrders();
    } else {
      ElMessage.error(res.message || '申请转派失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '转派异常');
  } finally {
    operatingId.value = null;
  }
}
</script>

<style scoped>
/*
 * 视觉语言：方向 D（口径见 docs/20261012-1330-…-visual-language-d-design.md §3、§5 的 V1~V15）。
 * 根节点 16px 内边距、内容装进 el-card shadow="never"、宽档表格 / 窄档卡片共用一份列定义
 * （components/ResponsiveTable.vue）、**状态一律 StatusRing 圆环**（V7）。
 * 颜色只取 styles/tokens.css 的令牌：品牌蓝 #1677FF 已退场（§4.2），彩色只服务状态语义
 * ——而状态色相只活在圆环的 2px 描边上，一个色值字面量都不该出现在本文件里。
 */
.worker-workbench {
  padding: 16px;
}

.page-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.tab-badge {
  margin-left: 6px;
  vertical-align: middle;
}

.search-container {
  margin-bottom: 12px;
}

/* ---------- 历史单回看抽屉 ---------- */

.detail-drawer {
  min-height: 120px;
}

/* 与完工页 .section-title 同口径（600 字重），抽屉里没有 el-card #header 可用 */
.drawer-section-title {
  font-weight: var(--fw-medium);
  color: var(--ink);
  margin: 16px 0 10px;
}

.drawer-section-title:first-child {
  margin-top: 0;
}

.muted {
  color: var(--faint);
}

.tag-gap {
  margin-right: 6px;
}

/* ---------- 列插槽（宽档表格单元格 + 窄档卡片正文共用同一份模板） ---------- */

.order-no-wrap {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.order-no {
  font-weight: var(--fw-medium);
  color: var(--ink);
  font-size: 13px;
}

/* 挂起期间 SLA 冻结：显示冻结说明而不是一个会误导人的截止时间。
   **不再叠一层琥珀色**——同一行的状态列已经有 `--h4` 圆环在说「已挂起」，
   这里再用颜色复述一遍就是「一个状态两种表达」（V9 的由来）。 */
.sla-frozen {
  color: var(--muted);
  font-weight: var(--fw-medium);
}

/* 报修人 = 一键拨号（设计文档 §8.3 的 tel: 语义，收进内容列省掉一列按钮）。
   可点 → 走 EP 主色令牌（§4.2：品牌蓝退场后由 `--el-color-primary` 承担「可交互」这一档） */
.tel-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--el-color-primary);
  text-decoration: none;
  white-space: nowrap;
}

.tel-link:hover {
  text-decoration: underline;
}

/* 号码比姓名弱一档：姓名是识别信息，号码是可读但不抢视线的辅助信息 */
.tel-number {
  font-size: var(--fs-base);
  color: var(--faint);
}

/* 仅窄档卡片：故障描述块 */
.fault-desc {
  color: var(--muted);
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* G4 口径（§4.6）：提示条**不用图像、不用彩底彩边**——
   实心点 + 中性字 + 发丝线边框。与进销存的低库存横幅是同一个形状（跨页一致）。 */
.suspend-banner {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-base);
  color: var(--ink);
  background: transparent;
  border: var(--hairline) solid var(--line);
  border-radius: 0;
  padding: 6px 10px;
  margin-top: 8px;
}

.action-buttons {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex-wrap: wrap;
}

/*
 * ⚠️ EP 给相邻 `.el-button` 默认加 `margin-left: 12px`，与本行的 `gap: 8px` **叠加成 20px**——
 * 实测「完工填报 + 挂起 + 转派」因此超出 168px 可用宽，「转派」被挤到第二行，行高从 42px 涨到 66px。
 * 统一交给 flex gap，必须清掉 EP 的那个 margin。
 */
.action-buttons .el-button + .el-button {
  margin-left: 0;
}

/* 窄档：操作按钮撑到 44px 触控高度，现场单手可点 */
@media (max-width: 1023px) {
  .action-buttons .el-button {
    min-height: 40px;
    padding-inline: 16px;
  }
}
</style>
