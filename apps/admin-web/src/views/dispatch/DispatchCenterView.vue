<template>
  <div class="dispatch-console">
    <!-- 主工作区：双栏分屏工作台（页面级导航已上移到 AdminLayout 固定侧边栏） -->
    <div class="split-workbench">
      <!-- 左栏 55% 待办调度池 -->
      <section class="left-pane">
        <!-- 待办分类快捷过滤 Tab -->
        <div class="pane-toolbar">
          <div class="filter-tabs">
            <div
              class="tab-pill"
              :class="{ active: currentCategory === 'ALL' }"
              @click="handleCategoryChange('ALL')"
            >
              全部待办
              <span class="badge">{{ counts.allCount }}</span>
            </div>
            <div
              class="tab-pill"
              :class="{ active: currentCategory === 'DISPATCH_FAIL' }"
              @click="handleCategoryChange('DISPATCH_FAIL')"
            >
              派单失败
              <span class="badge warning-badge">{{ counts.dispatchFailCount }}</span>
            </div>
            <div
              class="tab-pill"
              :class="{ active: currentCategory === 'TRANSFER_APPLY' }"
              @click="handleCategoryChange('TRANSFER_APPLY')"
            >
              师傅转派
              <span class="badge info-badge">{{ counts.transferApplyCount }}</span>
            </div>
            <div
              class="tab-pill"
              :class="{ active: currentCategory === 'ASSIGNED' }"
              @click="handleCategoryChange('ASSIGNED')"
            >
              已指派可改派
              <span class="badge">{{ counts.assignedCount || 0 }}</span>
            </div>
            <div
              class="tab-pill"
              :class="{ active: currentCategory === 'URGENT' }"
              @click="handleCategoryChange('URGENT')"
            >
              加急异常单
              <span class="badge urgent-badge">{{ counts.urgentCount }}</span>
            </div>
            <div
              class="tab-pill"
              :class="{ active: currentCategory === 'REWORK' }"
              @click="handleCategoryChange('REWORK')"
            >
              🚨 差评返工待派
              <span class="badge rework-badge">{{ counts.reworkCount }}</span>
            </div>
          </div>

          <div class="pane-actions">
            <div class="pane-search">
              <el-input
                v-model="searchKeyword"
                placeholder="搜索单号 / 报修人 / 故障描述..."
                clearable
                :prefix-icon="Search"
                size="small"
                @change="fetchOrders"
              />
            </div>
            <MetaChip class="live-tag">
              <span class="dot live-pulse"></span>
              实时调度作业中
            </MetaChip>
            <el-button
              :icon="Refresh"
              :loading="loadingOrders"
              circle
              title="刷新调度数据"
              aria-label="刷新调度数据"
              @click="refreshAll"
            />
          </div>
        </div>

        <!-- 待办工单卡片列表 -->
        <div class="order-list-scroll" v-loading="loadingOrders">
          <EmptyState v-if="orders.length === 0" description="调度池目前无待办工单，运行平稳" />

          <div
            v-for="order in orders"
            :key="order.id"
            class="order-card"
            :class="{ selected: selectedOrder?.id === order.id }"
            @click="selectOrder(order)"
          >
            <div class="card-top">
              <div class="order-no-wrap">
                <span class="order-no">{{ order.orderNo }}</span>
                <!--
                  ⚡ 三种标记换成 §3.3 的标记语言（与工单报表 / 我的工单 / 工作台**同一个形状**）：
                  · 「返工单」的实心红胶囊**删掉**——同一张卡上的状态圆环已经写着「返工待派」
                    （色相 `--h6`），再加一个实心红底标签就是「同一件事两处颜色」；
                  · 「加急」的橙字胶囊 → **实心圆点**（实心＝警戒），它没有工单状态码，
                    色相恒为 `--alert`；
                  · 状态标签 `el-tag type="info"` → **空心圆环**（V7）。
                -->
                <StatusRing v-if="order.urgencyLevel === 2" variant="dot" label="加急" />
                <StatusRing :status="order.status" />
              </div>
              <span class="order-time">{{ formatDateTime(order.createdAt) }}</span>
            </div>

            <div class="card-category">
              <MetaChip>
                {{ WorkerSkillLabels[order.workerSkillCode] || order.workerSkillCode }}
              </MetaChip>
              <span class="cat-name">{{ order.categoryFullName }}</span>
            </div>

            <div class="card-loc">
              <el-icon class="loc-icon"><Location /></el-icon>
              <span class="loc-text" :title="order.spaceFullPath">{{ order.spaceFullPath }}</span>
            </div>

            <div class="card-desc">
              {{ order.description }}
            </div>

            <div class="card-user-meta">
              <span>报修人: {{ order.applicantName }} ({{ order.applicantPhone }})</span>
              <span>期望时段: {{ order.appointmentPeriod }}</span>
            </div>

            <!--
              待办流转备注横幅。
              ⚠️ 2026-10-13（三档）：**点去掉了**，只留横幅（发丝线边框 + 中性字）。
              理由是实测出来的 —— 这个 `dispatchReason` 是「**最近一条流转备注**」
              （`WorkOrderService` 的 `lastLog.getReasonOrRemark()`），
              演示库上取到的真实取值里，"师生验收通过，评价 5 星""智能匹配成功""系统自动核销"
              这类**正向/中性**占了绝大多数，只有"师生评价 1 星"一条是真警戒。
              给一个 11/12 时候并不紧急的字段配红点，等于把"最近发生了什么"读成"出事了"。

              而且**它去掉也不丢信息**：横幅本身的发丝线边框已经在说"这里有一条备注"；
              这条备注的性质与 `order.status` 高度相关，而**卡片顶部的状态环已经在显示 status 了**。
            -->
            <div v-if="order.dispatchReason" class="card-reason-banner">
              <span>{{ order.dispatchReason }}</span>
            </div>

            <div class="card-footer-actions">
              <span class="dispatch-type-tip" v-if="order.workerName">
                原指派: <strong>{{ order.workerName }}</strong>
              </span>
              <div class="action-btn-group">
                <!--
                  §24 执行顺序 1（中性化）：返工仲裁原 `warning`、时间轴原 `info`，两者**都不是状态**。
                  「要小心」由 `openArbitrateModal` 的仲裁弹窗承担；时间轴是纯只读跳转，染色没有语义。
                -->
                <el-button
                  v-if="order.status === 'REWORK_PENDING'"
                  size="small"
                  @click.stop="openArbitrateModal(order)"
                >
                  返工仲裁
                </el-button>
                <el-button
                  link
                  size="small"
                  @click.stop="openTimelineModal(order)"
                >
                  时间轴
                </el-button>
                <!-- 「作废工单」**保留 `danger`**：登记在案的例外（B1），判据见 color-controls.spec.ts 的 isDestructive。 -->
                <el-button
                  type="danger"
                  link
                  size="small"
                  @click.stop="openCancelModal(order)"
                >
                  作废工单
                </el-button>
                <el-button
                  type="primary"
                  size="small"
                  @click.stop="selectOrder(order)"
                >
                  {{ selectedOrder?.id === order.id ? '当前选中' : '选择调度' }}
                </el-button>
              </div>
            </div>
          </div>
        </div>

        <div class="pagination-footer" v-if="totalOrders > 0">
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :total="totalOrders"
            layout="prev, pager, next, total"
            size="small"
            @current-change="fetchOrders"
          />
        </div>
      </section>

      <!-- 右栏 45% 师傅实时负荷与指派联动看板 -->
      <section class="right-pane">
        <div class="board-header">
          <div class="board-title-wrap">
            <span class="board-title">师傅负荷与秒级调度看板</span>
            <div class="board-rule-tags" v-if="selectedOrder">
              <span class="board-rule-tag">
                工种需求: <strong>{{ WorkerSkillLabels[selectedOrder.workerSkillCode] || selectedOrder.workerSkillCode }}</strong>
              </span>
              <span class="board-rule-tag" v-if="selectedOrder.campusName">
                所属校区: <strong>{{ selectedOrder.campusName }}</strong>
              </span>
            </div>
            <span class="board-rule-tag empty" v-else>
              请先在左侧选择待办工单进行智能联动
            </span>
          </div>

          <div class="board-filters">
            <el-checkbox v-model="onlyOnDuty" @change="fetchWorkers">仅看在岗</el-checkbox>
            <el-input
              v-model="workerKeyword"
              placeholder="师傅姓名/电话"
              clearable
              size="small"
              class="worker-search-input"
              @change="fetchWorkers"
            />
          </div>
        </div>

        <div class="worker-grid-scroll" v-loading="loadingWorkers">
          <EmptyState v-if="workers.length === 0" kind="no-result" description="未检索到符合条件的维修师傅" />

          <div class="worker-grid" v-else>
            <div
              v-for="worker in workers"
              :key="worker.id"
              class="worker-card"
              :class="{
                overloaded: worker.isOverloaded,
                offduty: worker.onDutyStatus !== 1
              }"
            >
              <div class="worker-top">
                <div class="worker-name-info">
                  <div class="avatar-box">{{ worker.realName.substring(0, 1) }}</div>
                  <div>
                    <div class="name-line">
                      <span class="real-name">{{ worker.realName }}</span>
                      <!--
                        ⚠️ 2026-10-13（C 组 #1）：`el-tag`（success/info）→ `MetaChip`。
                        「在岗 / 休息」**看起来像状态，其实是属性** —— 它的取值不随任何生命周期推进，
                        只是这条师傅记录的一个字段。§3.3 的空心环是留给**工单生命周期**的，
                        属性既不该借它，也不该借「警戒」那一档。
                        （"这个人现在不可派"由 `.worker-card.offduty` 的压暗承担，不靠颜色。）
                      -->
                      <MetaChip>{{ worker.onDutyStatus === 1 ? '在岗' : '休息' }}</MetaChip>
                    </div>
                    <span class="worker-skill">{{ WorkerSkillLabels[worker.skillCode] || worker.skillCode }}</span>
                  </div>
                </div>

                <div class="rating-box">
                  <span class="star-icon">★</span>
                  <span class="star-score">{{ Number(worker.ratingAvg).toFixed(1) }}</span>
                </div>
              </div>

              <!-- 负荷进度条指示器 -->
              <div class="load-section">
                <div class="load-text-bar">
                  <span>实时在修负荷</span>
                  <strong :class="getLoadToneClass(worker)">{{ worker.currentLoad }} / {{ worker.maxLoad }}</strong>
                </div>
                <el-progress
                  :percentage="worker.loadRate"
                  :status="getProgressBarStatus(worker)"
                  :stroke-width="10"
                  :striped="worker.isOverloaded"
                  :striped-flow="worker.isOverloaded"
                />
              </div>

              <!-- 战绩统计小卡片 -->
              <div class="worker-kpi-row">
                <div class="kpi-item">
                  <span class="kpi-label">今日完工</span>
                  <strong class="kpi-val">{{ worker.todayCompletedCount }} 单</strong>
                </div>
                <div class="kpi-divider"></div>
                <div class="kpi-item">
                  <span class="kpi-label">均修耗时</span>
                  <strong class="kpi-val">{{ worker.avgRepairHours }} h</strong>
                </div>
                <div class="kpi-divider"></div>
                <div class="kpi-item">
                  <span class="kpi-label">历史总量</span>
                  <strong class="kpi-val">{{ worker.totalCompletedCount }} 单</strong>
                </div>
              </div>

              <div class="worker-action-bar">
                <div class="phone-tip">
                  <el-icon><Phone /></el-icon>
                  <span>{{ worker.phone }}</span>
                </div>

                <el-button
                  type="primary"
                  size="default"
                  :disabled="!canDispatch(worker)"
                  :loading="dispatchingId === worker.userId"
                  @click="handleDispatchToWorker(worker)"
                >
                  {{ getDispatchButtonText(worker) }}
                </el-button>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>

    <!-- 作废工单核实弹窗 -->
    <el-dialog
      v-model="cancelModalVisible"
      title="工单核实作废确认"
      width="460px"
      :close-on-click-modal="false"
    >
      <AlertBanner>作废操作不可逆，工单生命周期将终止为 CANCELLED，同时若占用师傅名额将自动释放。</AlertBanner>
      <el-form label-position="top">
        <el-form-item label="当前作废工单">
          <strong>{{ cancelingOrder?.orderNo }} - {{ cancelingOrder?.categoryFullName }}</strong>
        </el-form-item>
        <el-form-item label="作废取消原因说明 (必填)">
          <el-input
            v-model="cancelReasonInput"
            type="textarea"
            :rows="3"
            placeholder="请客观详细录入核实作废原因，如：电话核实为学生恶作剧/已由宿管现场协助排除无需维修等..."
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="cancelModalVisible = false">取消</el-button>
        <el-button type="danger" :loading="canceling" @click="submitCancelOrder">确认作废</el-button>
      </template>
    </el-dialog>

    <!-- 主管返工仲裁弹窗 -->
    <el-dialog
      v-model="arbitrateModalVisible"
      title="后勤主管差评返工仲裁督办"
      width="540px"
      :close-on-click-modal="false"
    >
      <!--
        ⚠️ 2026-10-13：`el-alert type="error"` → **`AlertBanner`**（§4.6 G4 口径），
        与同页另外两处警示条、以及全仓其余位置同一个形状。

        这一处是**棘轮的一处盲区**：`color-controls.spec.ts` 的 `SEMANTIC` 原先是
        `success|warning|danger|info|primary` —— 那是 **`el-button` 的语汇**；
        而 `el-alert` 用的档位是 **`error`**，所以 `type="error"` **一直没被计入**。
        本轮把 `error` 补进口径（见该 spec 的注释），并把两处一并改掉。
      -->
      <AlertBanner>
        该工单因师生打出 ≤2 星差评转入返工督办。主管可决策退回原师傅限期整改，或指定资深技师二次上门。
      </AlertBanner>

      <el-form label-position="top">
        <el-form-item label="返工工单">
          <strong>{{ arbitratingOrder?.orderNo }} - {{ arbitratingOrder?.categoryFullName }}</strong>
          <div class="arbitrate-meta">
            原指派师傅: {{ arbitratingOrder?.workerName || '未知' }} | 申请人: {{ arbitratingOrder?.applicantName }}
          </div>
        </el-form-item>

        <el-form-item label="仲裁决策方案">
          <el-radio-group v-model="arbitrationDecision">
            <el-radio label="RETRY_ORIGINAL_WORKER">退回原师傅限期整改</el-radio>
            <el-radio label="REASSIGN_NEW_WORKER">换派资深技师二次上门</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="arbitrationDecision === 'REASSIGN_NEW_WORKER'" label="选择重派维修师傅 (必选)">
          <el-select
            v-model="selectedNewWorkerId"
            placeholder="请选择改派的目标技师"
            style="width: 100%;"
            filterable
          >
            <el-option
              v-for="w in workers"
              :key="w.userId"
              :label="`${w.realName} (${WorkerSkillLabels[w.skillCode] || w.skillCode} - 当前负荷: ${w.currentLoad}/${w.maxLoad})`"
              :value="w.userId"
              :disabled="w.currentLoad >= w.maxLoad || w.onDutyStatus !== 1"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="主管督办处理说明 (必填)">
          <el-input
            v-model="arbitrationNoteInput"
            type="textarea"
            :rows="3"
            placeholder="请客观录入回访核实结论与现场督办要求，如：回访确认角阀微渗，已联系高级技师带加厚配件二次上门彻底更换..."
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="arbitrateModalVisible = false">取消</el-button>
        <el-button type="primary" :loading="arbitrating" @click="submitArbitrateRework">确认仲裁指派</el-button>
      </template>
    </el-dialog>

    <!-- 工单全生命周期流转审计时间轴抽屉 -->
    <el-drawer
      v-model="timelineDrawerVisible"
      :title="`工单流转审计追溯链 - ${timelineOrder?.orderNo || ''}`"
      size="520px"
      direction="rtl"
    >
      <div v-if="timelineOrder" style="margin-bottom: 16px;">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="报修单号">{{ timelineOrder.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="报修空间">{{ timelineOrder.spaceFullPath }}</el-descriptions-item>
          <el-descriptions-item label="故障类型">{{ timelineOrder.categoryFullName }}</el-descriptions-item>
          <el-descriptions-item label="当前状态">
            <el-tag size="small">{{ OrderStatusLabels[timelineOrder.status] || timelineOrder.status }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <WorkOrderTimeline v-if="timelineOrder" :order-id="timelineOrder.id" />
    </el-drawer>

  </div>
</template>

<script setup lang="ts">
import AlertBanner from '../../components/AlertBanner.vue';
import MetaChip from '../../components/MetaChip.vue';
import EmptyState from '../../components/EmptyState.vue';
import { ref, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Refresh,
  Search,
  Location,
  Phone
} from '@element-plus/icons-vue';
import { httpClient } from '../../stores/auth';
import WorkOrderTimeline from '../../components/WorkOrderTimeline.vue';
import StatusRing from '../../components/StatusRing.vue';
import {
  OrderStatusEnum,
  OrderStatusLabels,
  WorkerSkillLabels,
  DispatchOrderItemVO,
  DispatchPoolCategoryCountVO,
  DispatchPoolResultVO,
  WorkerBoardVO,
  formatDateTime
} from '@campus/shared';

const route = useRoute();
const router = useRouter();

// 左栏工单池数据状态
const currentCategory = ref('ALL');
const searchKeyword = ref('');
const currentPage = ref(1);
const pageSize = ref(20);
const totalOrders = ref(0);
const loadingOrders = ref(false);
const orders = ref<DispatchOrderItemVO[]>([]);
const selectedOrder = ref<DispatchOrderItemVO | null>(null);

const counts = ref<DispatchPoolCategoryCountVO>({
  allCount: 0,
  dispatchFailCount: 0,
  transferApplyCount: 0,
  assignedCount: 0,
  urgentCount: 0,
  reworkCount: 0
});

// 右栏师傅看板数据状态
const loadingWorkers = ref(false);
const workers = ref<WorkerBoardVO[]>([]);
const onlyOnDuty = ref(false);
const workerKeyword = ref('');
const dispatchingId = ref<number | null>(null);

// 作废弹窗状态
const cancelModalVisible = ref(false);
const canceling = ref(false);
const cancelingOrder = ref<DispatchOrderItemVO | null>(null);
const cancelReasonInput = ref('');

// 时间轴抽屉状态
const timelineDrawerVisible = ref(false);
const timelineOrder = ref<DispatchOrderItemVO | null>(null);

function openTimelineModal(order: DispatchOrderItemVO) {
  timelineOrder.value = order;
  timelineDrawerVisible.value = true;
}

async function handleSelectOrderFromNotif(orderId: number) {
  try {
    const res = await httpClient.get<DispatchOrderItemVO>(`/work-orders/${orderId}`);
    if (res.code === 200 && res.data) {
      timelineOrder.value = res.data;
      timelineDrawerVisible.value = true;
    }
  } catch (e) {
    // fallback
  }
}

// 全局通知抽屉（AdminLayout）点击“关联工单”后，携带 orderId 跳转到本页，这里自动打开审计时间轴
watch(
  () => route.query.orderId,
  (orderId) => {
    if (!orderId) return;
    handleSelectOrderFromNotif(Number(orderId));
    router.replace({ path: route.path, query: {} });
  },
  { immediate: true }
);

onMounted(() => {
  refreshAll();
});

function refreshAll() {
  fetchOrders();
  fetchWorkers();
}

async function fetchOrders() {
  loadingOrders.value = true;
  try {
    const res = await httpClient.get<DispatchPoolResultVO>('/work-orders/dispatch-pool', {
      params: {
        page: currentPage.value,
        size: pageSize.value,
        categoryFilter: currentCategory.value,
        keyword: searchKeyword.value
      }
    });

    if (res.code === 200 && res.data) {
      orders.value = res.data.orders.records || [];
      totalOrders.value = res.data.orders.total || 0;
      counts.value = res.data.counts || counts.value;

      // 如果之前选中的工单依然在当前列表中，保持选中；否则默认选中第一项
      if (orders.value.length > 0) {
        const found = orders.value.find(o => o.id === selectedOrder.value?.id);
        if (found) {
          selectedOrder.value = found;
        } else {
          selectOrder(orders.value[0]);
        }
      } else {
        selectedOrder.value = null;
      }
    }
  } catch (err: any) {
    ElMessage.error(err.message || '获取调度池列表失败');
  } finally {
    loadingOrders.value = false;
  }
}

async function fetchWorkers() {
  loadingWorkers.value = true;
  try {
    const params: any = {
      onlyOnDuty: onlyOnDuty.value ? true : undefined,
      keyword: workerKeyword.value || undefined
    };

    // 如果左侧选中了工单，联动按工种与校区双重匹配过滤
    if (selectedOrder.value) {
      if (selectedOrder.value.workerSkillCode) {
        params.skillCode = selectedOrder.value.workerSkillCode;
      }
      if (selectedOrder.value.campusId) {
        params.campusId = selectedOrder.value.campusId;
      }
    }

    const res = await httpClient.get<WorkerBoardVO[]>('/workers/dispatch-board', {
      params
    });

    if (res.code === 200 && res.data) {
      workers.value = res.data;
    }
  } catch (err: any) {
    ElMessage.error(err.message || '获取师傅负荷看板失败');
  } finally {
    loadingWorkers.value = false;
  }
}

function handleCategoryChange(cat: string) {
  currentCategory.value = cat;
  currentPage.value = 1;
  fetchOrders();
}

function selectOrder(order: DispatchOrderItemVO) {
  selectedOrder.value = order;
  // 联动刷新右侧师傅列表
  fetchWorkers();
}

function canDispatch(worker: WorkerBoardVO): boolean {
  if (!selectedOrder.value) return false;
  if (worker.onDutyStatus !== 1) return false;
  if (worker.isOverloaded) return false;
  return true;
}

function getDispatchButtonText(worker: WorkerBoardVO): string {
  if (worker.onDutyStatus !== 1) return '师傅休息中';
  if (worker.isOverloaded) return '已满载';
  if (selectedOrder.value?.status === OrderStatusEnum.REWORK_PENDING) {
    if (selectedOrder.value.workerId === worker.userId) {
      return '指派原师傅整改';
    }
    return '换派此技师整改';
  }
  if (selectedOrder.value?.workerId === worker.userId) {
    return '当前已指派该师傅';
  }
  return '指派给该师傅';
}

async function handleDispatchToWorker(worker: WorkerBoardVO) {
  if (!selectedOrder.value) {
    ElMessage.warning('请先在左侧选择要分派的待办工单');
    return;
  }

  // 秒级一键直接指派，无需反复弹窗打断，由按钮 loading 与顶部 ElMessage 承接即时反馈
  dispatchingId.value = worker.userId;
  try {
    const res = await httpClient.post<null>(`/work-orders/${selectedOrder.value.id}/dispatch`, {
      workerId: worker.userId,
      dispatchRemark: `后勤调度中心指派【${worker.realName}】处理`
    });

    if (res.code === 200) {
      ElMessage.success(`工单 ${selectedOrder.value.orderNo} 已秒级指派给 ${worker.realName} 师傅！`);
      refreshAll();
    } else {
      ElMessage.error(res.message || '人工指派失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '网络调度异常');
  } finally {
    dispatchingId.value = null;
  }
}

// 仲裁弹窗相关状态
const arbitrateModalVisible = ref(false);
const arbitratingOrder = ref<DispatchOrderItemVO | null>(null);
const arbitrationDecision = ref<'RETRY_ORIGINAL_WORKER' | 'REASSIGN_NEW_WORKER'>('RETRY_ORIGINAL_WORKER');
const selectedNewWorkerId = ref<number | null>(null);
const arbitrationNoteInput = ref('');
const arbitrating = ref(false);

function openArbitrateModal(order: DispatchOrderItemVO) {
  arbitratingOrder.value = order;
  arbitrationDecision.value = 'RETRY_ORIGINAL_WORKER';
  selectedNewWorkerId.value = null;
  arbitrationNoteInput.value = '';
  arbitrateModalVisible.value = true;
}

async function submitArbitrateRework() {
  if (!arbitratingOrder.value) return;
  if (arbitrationDecision.value === 'REASSIGN_NEW_WORKER' && !selectedNewWorkerId.value) {
    ElMessage.warning('换派模式下必须选择目标维修师傅');
    return;
  }
  if (!arbitrationNoteInput.value.trim()) {
    ElMessage.warning('主管督办说明不能为空');
    return;
  }

  arbitrating.value = true;
  try {
    const payload = {
      arbitrationDecision: arbitrationDecision.value,
      targetWorkerId: arbitrationDecision.value === 'REASSIGN_NEW_WORKER' ? selectedNewWorkerId.value : undefined,
      arbitrationNote: arbitrationNoteInput.value.trim()
    };

    const res = await httpClient.post<null>(
      `/work-orders/${arbitratingOrder.value.id}/arbitrate-rework`,
      payload
    );

    if (res.code === 200) {
      ElMessage.success(`工单 ${arbitratingOrder.value.orderNo} 仲裁督办完成，已重新指派并推进`);
      arbitrateModalVisible.value = false;
      refreshAll();
    } else {
      ElMessage.error(res.message || '仲裁操作失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '网络请求异常');
  } finally {
    arbitrating.value = false;
  }
}

function openCancelModal(order: DispatchOrderItemVO) {
  cancelingOrder.value = order;
  cancelReasonInput.value = '';
  cancelModalVisible.value = true;
}

async function submitCancelOrder() {
  if (!cancelingOrder.value) return;
  if (!cancelReasonInput.value.trim()) {
    ElMessage.warning('作废原因说明不能为空');
    return;
  }

  canceling.value = true;
  try {
    const res = await httpClient.post<null>(`/work-orders/${cancelingOrder.value.id}/cancel`, {
      cancelReason: cancelReasonInput.value.trim()
    });

    if (res.code === 200) {
      ElMessage.success(`工单 ${cancelingOrder.value.orderNo} 已核实作废完成`);
      cancelModalVisible.value = false;
      refreshAll();
    } else {
      ElMessage.error(res.message || '作废操作失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '网络异常');
  } finally {
    canceling.value = false;
  }
}

/**
 * 负荷率的**中性档位**（原先返回 `color-red` / `color-orange` / `color-green` 三个颜色类名）。
 *
 * 严重度已经由旁边 `el-progress` 的 `:status`（`exception` / `warning` / `success`）表达，
 * 那是 EP 组件自身的语义色，属规范 §12.5 登记的「非状态类彩色控件」策略，本批不动。
 * 同一件事**不该有两处颜色**，所以这里只保留中性层级，把三个档位收成两档。
 */
function getLoadToneClass(worker: WorkerBoardVO): string {
  return worker.isOverloaded || worker.loadRate >= 70 ? 'load-tone-alert' : 'load-tone-normal';
}

function getProgressBarStatus(worker: WorkerBoardVO): string {
  if (worker.isOverloaded) return 'exception';
  if (worker.loadRate >= 70) return 'warning';
  return 'success';
}
</script>

<style scoped>
.dispatch-console {
  display: flex;
  flex-direction: column;
  /* 外壳顶部条固定 56px，本页工作台占满剩余视口且自身不滚动 */
  height: calc(100vh - var(--topbar-h, 56px));
  background-color: var(--canvas);
  overflow: hidden;
}

.pane-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.pane-search {
  flex: 1;
  min-width: 0;
}

.live-tag {
  display: flex;
  align-items: center;
  gap: 6px;
}

/* 「实时刷新中」指示点。**不再是绿色**——「实时」不是工单状态，
   而 §3.2 的 8 个色相只服务状态语义，给它绿色等于凭空添第 9 个色相。
   呼吸动效保留（它表达的是「活着」，不是「属于哪一类」），光晕一并中性。 */
.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background-color: var(--faint);
}

.live-pulse {
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(0, 0, 0, 0.25); }
  70% { transform: scale(1.1); box-shadow: 0 0 0 6px rgba(0, 0, 0, 0); }
  100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(0, 0, 0, 0); }
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 双栏分屏 */
.split-workbench {
  flex: 1;
  display: flex;
  overflow: hidden;
  gap: 12px;
  padding: 12px;
}

/* 左栏 55% 待办调度池 */
.left-pane {
  flex: 0 0 55%;
  background-color: var(--surface);
  border-radius: var(--radius-card);
  /* §3.5 / V15：禁止自造阴影（原有一条 `0 1px 3px rgba(0,0,0,.05)`），层级用发丝线建立 */
  border: var(--hairline) solid var(--line);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.pane-toolbar {
  padding: 14px 16px;
  border-bottom: var(--hairline) solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.filter-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* 筛选胶囊：圆角由 16px 收到 --radius（§3.5 圆角 0–2px；实测腾讯云没有一个 ≥8px 的圆角）。 */
.tab-pill {
  padding: 6px 12px;
  border-radius: var(--radius);
  background-color: var(--canvas);
  color: var(--muted);
  font-size: 13px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  /* 刻意不写 `transition: all`——它会把宽高/圆角这些**几何**也纳入过渡，是隐性地雷 */
  transition: background-color 0.2s, color 0.2s;
  user-select: none;
}

.tab-pill:hover {
  background-color: var(--canvas);
  color: var(--el-color-primary);
}

/* 选中态走 §4.2 统一后的主色令牌（品牌蓝 #1677FF 已退场）；
   字重回 400——§3.5：层级靠字号 + 颜色建立，不靠加粗 */
.tab-pill.active {
  background-color: var(--el-color-primary);
  color: var(--surface);
  font-weight: var(--fw-normal);
}

.badge {
  background-color: rgba(0, 0, 0, 0.08);
  padding: 1px 6px;
  border-radius: var(--radius);
  font-size: 11px;
}

.tab-pill.active .badge {
  background-color: rgba(255, 255, 255, 0.3);
  color: var(--surface);
  /* 覆盖下面那组中性徽标的发丝线边框：在选中态的蓝底上留一条灰边很难看 */
  border-color: transparent;
}

/*
 * 四个计数徽标**全部中性化**（原先四组「浅底 + 同色文字」：橙 / 蓝 / 红 / 深红底白字）。
 * 理由有两条，第二条更要紧：
 * ① 它们只是「这一档里有几条」，是**数量**而不是状态——按 V4「彩色只服务状态语义」，
 *    数量不该是彩色的；
 * ② **同一个色相只服务一种语义**。若把「返工」单独染红，红色在**这一页**就同时表示
 *    「加急」（`.urgent-badge`）、「返工」（`.rework-badge`）、「负荷超载」（`.color-red`）——
 *    一个色相三个意思，正是 D 要消灭的东西。
 */
.warning-badge,
.info-badge,
.urgent-badge,
.rework-badge {
  background-color: transparent;
  color: var(--muted);
  border: var(--hairline) solid var(--line);
}

.order-list-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.order-card {
  border: var(--hairline) solid var(--line);
  border-radius: var(--radius-card);
  padding: 12px 14px;
  background-color: var(--surface);
  cursor: pointer;
  transition: border-color 0.2s cubic-bezier(0.4, 0, 0.2, 1), background-color 0.2s;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* 悬停：只改边框色，**去掉自造阴影**（V15） */
.order-card:hover {
  border-color: var(--el-color-primary);
}

/* 选中：边框 + 极浅的主色底（`light-9` 是令牌层给的派生档，不是手调出来的色） */
.order-card.selected {
  border-color: var(--el-color-primary);
  background-color: var(--el-color-primary-light-9);
}

/*
 * ⚠️ `.rework-border` 与 `.urgent-border`（`border-left: 4px solid` 红/橙）**已删除**。
 * 这是与流转时间轴同一处病：**用色块面积表达状态**，而 D 把状态收进 9px 圆环。
 * · 「返工待派」是**工单状态**——它的色相现在只由卡片上的空心圆环说（`--h6`），
 *   再配一条 4px 红边就是同一件事的第二处颜色；
 * · 「加急」不是状态，改用**实心圆点**（实心＝警戒），与工单报表/我的工单/工作台同形。
 */

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 8px;
}

.order-no-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.order-no {
  font-weight: var(--fw-medium);
  color: var(--ink);
  font-size: 14px;
}

.order-time {
  font-size: var(--fs-base);
  color: var(--faint);
}

.card-category {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cat-name {
  font-size: 13px;
  color: var(--ink);
}

.card-loc {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-base);
  color: var(--muted);
}

.loc-icon {
  color: var(--faint);
}

.loc-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-desc {
  font-size: 13px;
  color: var(--muted);
  line-height: 1.4;
  background-color: var(--canvas);
  padding: 6px 8px;
  border-radius: var(--radius);
}

.card-user-meta {
  display: flex;
  justify-content: space-between;
  font-size: var(--fs-base);
  color: var(--muted);
}

/* G4 口径（§4.6）：提示条**不用彩底彩边**——发丝线边框 + 中性字。
   ⚠️ 2026-10-13 起**不再带实心点**：这条横幅读的是 `dispatchReason`（= 最近一条流转备注），
   实测取值里正向/中性（5 星通过、智能匹配成功、系统自动核销）占绝大多数，
   只有"师生评价 1 星"是真警戒 —— 给一个 11/12 不紧急的字段配红点等于把"发生了什么"读成"出事了"。
   与师傅工作台的挂起原因条形状一致（跨页一致）。 */
.card-reason-banner {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-base);
  color: var(--ink);
  background-color: transparent;
  border: var(--hairline) solid var(--line);
  border-radius: 0;
  padding: 4px 8px;
}

.card-footer-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 6px;
  border-top: var(--hairline) dashed var(--line);
}

.dispatch-type-tip {
  font-size: var(--fs-base);
  color: var(--muted);
}

/* 返工仲裁弹窗里的元信息行（原先是一段内联 `style="color:#E6A23C"`） */
.arbitrate-meta {
  font-size: 13px;
  color: var(--muted);
  margin-top: 4px;
}

.pagination-footer {
  padding: 8px 16px;
  border-top: var(--hairline) solid var(--line);
  display: flex;
  justify-content: flex-end;
}

/* 右栏 45% 师傅看板 */
.right-pane {
  flex: 1;
  background-color: var(--surface);
  border-radius: var(--radius-card);
  border: var(--hairline) solid var(--line);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.board-header {
  padding: 14px 16px;
  border-bottom: var(--hairline) solid var(--line);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.board-title-wrap {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.board-title {
  font-size: 15px;
  font-weight: var(--fw-medium);
  color: var(--ink);
}

/* 指派规则说明条：**信息**而不是状态 → 中性小胶囊（原先是主色浅底 + 主色文字） */
.board-rule-tag {
  font-size: var(--fs-base);
  color: var(--muted);
  background-color: transparent;
  border: var(--hairline) solid var(--line);
  padding: 2px 6px;
  border-radius: var(--radius);
  display: inline-block;
  width: fit-content;
}

.board-rule-tag.empty {
  color: var(--faint);
  border-color: var(--line);
}

.board-filters {
  display: flex;
  align-items: center;
  gap: 12px;
}

.worker-grid-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 14px 16px;
}

.worker-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 12px;
}

.worker-card {
  border: var(--hairline) solid var(--line);
  border-radius: var(--radius-card);
  padding: 12px;
  background-color: var(--surface);
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: border-color 0.2s;
}

.worker-card:hover {
  border-color: var(--el-color-primary);
}

/*
 * 超载卡片：原先整张卡染浅红底 + 红边（`#FFF1F0` / `#FFA39E`）。
 * **去掉色块**——严重度已经由这张卡里 `el-progress` 的 `:status="exception"` 表达
 * （EP 组件自身的语义色，属 §12.5 登记的 53 处策略），同一件事不该有两处颜色。
 * 需要更醒目的「哪几个师傅超载」时，正解是 §3.3 的**实心圆点**，不是把整张卡染红。
 */
.worker-card.overloaded {
  background-color: var(--surface);
  border-color: var(--line);
}

.worker-card.offduty {
  opacity: 0.65;
  background-color: var(--canvas);
}

.worker-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.worker-name-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 头像首字母底：与个人中心同口径——**中性**（V4：彩色只服务状态语义，而这只是装饰） */
.avatar-box {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background-color: var(--ink);
  color: var(--surface);
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 15px;
  font-weight: var(--fw-medium);
}

.name-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.real-name {
  font-weight: var(--fw-medium);
  color: var(--ink);
  font-size: 14px;
}

.worker-skill {
  font-size: var(--fs-base);
  color: var(--muted);
}

/* 评分小胶囊：**去掉琥珀底色与边框**，靠文字与 ★ 字形表达（原先浅黄底 + 黄边 + 黄星 + 深黄分） */
.rating-box {
  display: flex;
  align-items: center;
  gap: 2px;
  background-color: transparent;
  border: var(--hairline) solid var(--line);
  padding: 2px 6px;
  border-radius: var(--radius);
}

.star-icon {
  color: var(--faint);
  font-size: 13px;
}

.star-score {
  font-weight: var(--fw-medium);
  color: var(--ink);
  font-size: var(--fs-base);
}

.load-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.load-text-bar {
  display: flex;
  justify-content: space-between;
  font-size: var(--fs-base);
  color: var(--muted);
}

/*
 * 负荷率的两个**中性档位**（原名 `color-green` / `color-orange` / `color-red`，
 * 三个颜色类已收成两档，类名也一并改了——留一个叫 `color-red` 却不是红的类，
 * 就是下一个「名字与事实不符」的坑）。
 * 严重度由旁边 `el-progress` 的 `:status` 表达（见 `getLoadToneClass` 的注释）。
 */
.load-tone-normal {
  color: var(--muted);
}

.load-tone-alert {
  color: var(--ink);
  font-weight: var(--fw-medium);
}

.worker-kpi-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background-color: var(--canvas);
  border-radius: var(--radius);
  padding: 6px 10px;
}

.kpi-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.kpi-label {
  font-size: 11px;
  color: var(--faint);
}

.kpi-val {
  font-size: var(--fs-base);
  color: var(--ink);
}

.kpi-divider {
  width: 1px;
  height: 18px;
  background-color: var(--line);
}

.worker-action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 4px;
}

.phone-tip {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-base);
  color: var(--muted);
}

.worker-search-input {
  width: 150px;
  flex: 1 1 130px;
  min-width: 0;
}

/* ---------- 窄屏堆叠（≤1023px）：双栏改上下布局，页面自然滚动 ---------- */
@media (max-width: 1023px) {
  .dispatch-console {
    height: auto;
    min-height: calc(100vh - var(--topbar-h, 56px));
    overflow: visible;
  }

  .split-workbench {
    flex-direction: column;
    overflow: visible;
  }

  .left-pane,
  .right-pane {
    flex: 1 1 auto;
    overflow: visible;
  }

  /* 待办池保留内部滚动并限高，避免超长列表把页面无限拉高 */
  .order-list-scroll {
    max-height: 65vh;
  }

  .worker-grid-scroll {
    overflow-y: visible;
  }
}
</style>
