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
              class="tab-pill rework-pill"
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
            <el-tag effect="plain" type="primary" class="live-tag">
              <span class="dot live-pulse"></span>
              实时调度作业中
            </el-tag>
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
          <el-empty v-if="orders.length === 0" description="调度池目前无待办工单，运行平稳" />

          <div
            v-for="order in orders"
            :key="order.id"
            class="order-card"
            :class="{
              selected: selectedOrder?.id === order.id,
              'rework-border': order.status === 'REWORK_PENDING',
              'urgent-border': order.urgencyLevel === 2
            }"
            @click="selectOrder(order)"
          >
            <div class="card-top">
              <div class="order-no-wrap">
                <span class="order-no">{{ order.orderNo }}</span>
                <el-tag
                  v-if="order.status === 'REWORK_PENDING'"
                  type="danger"
                  effect="dark"
                  size="small"
                  class="pulse-tag"
                >
                  返工单
                </el-tag>
                <el-tag
                  v-if="order.urgencyLevel === 2"
                  type="danger"
                  effect="plain"
                  size="small"
                >
                  ⚡ 加急
                </el-tag>
                <el-tag size="small" type="info">{{ OrderStatusLabels[order.status] || order.status }}</el-tag>
              </div>
              <span class="order-time">{{ formatTime(order.createdAt) }}</span>
            </div>

            <div class="card-category">
              <el-tag size="small" effect="plain" type="primary">
                {{ WorkerSkillLabels[order.workerSkillCode] || order.workerSkillCode }}
              </el-tag>
              <span class="cat-name">{{ order.categoryFullName }}</span>
            </div>

            <div class="card-loc">
              <el-icon color="#6B7280"><Location /></el-icon>
              <span class="loc-text" :title="order.spaceFullPath">{{ order.spaceFullPath }}</span>
            </div>

            <div class="card-desc">
              {{ order.description }}
            </div>

            <div class="card-user-meta">
              <span>报修人: {{ order.applicantName }} ({{ order.applicantPhone }})</span>
              <span>期望时段: {{ order.appointmentPeriod }}</span>
            </div>

            <!-- 待办流转根因提示横幅 -->
            <div v-if="order.dispatchReason" class="card-reason-banner">
              <el-icon><Warning /></el-icon>
              <span>{{ order.dispatchReason }}</span>
            </div>

            <div class="card-footer-actions">
              <span class="dispatch-type-tip" v-if="order.workerName">
                原指派: <strong>{{ order.workerName }}</strong>
              </span>
              <div class="action-btn-group">
                <el-button
                  v-if="order.status === 'REWORK_PENDING'"
                  type="warning"
                  size="small"
                  @click.stop="openArbitrateModal(order)"
                >
                  返工仲裁
                </el-button>
                <el-button
                  type="info"
                  link
                  size="small"
                  @click.stop="openTimelineModal(order)"
                >
                  时间轴
                </el-button>
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
              style="width: 140px;"
              @change="fetchWorkers"
            />
          </div>
        </div>

        <div class="worker-grid-scroll" v-loading="loadingWorkers">
          <el-empty v-if="workers.length === 0" description="未检索到符合条件的维修师傅" />

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
                      <el-tag
                        size="small"
                        :type="worker.onDutyStatus === 1 ? 'success' : 'info'"
                        effect="light"
                      >
                        {{ worker.onDutyStatus === 1 ? '在岗' : '休息' }}
                      </el-tag>
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
                  <strong :class="getLoadColorClass(worker)">{{ worker.currentLoad }} / {{ worker.maxLoad }}</strong>
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
      <el-alert
        title="作废操作不可逆，工单生命周期将终止为 CANCELLED，同时若占用师傅名额将自动释放。"
        type="warning"
        :closable="false"
        style="margin-bottom: 16px;"
      />
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
      <el-alert
        title="该工单因师生打出 ≤2 星差评转入返工督办。主管可决策退回原师傅限期整改，或指定资深技师二次上门。"
        type="error"
        :closable="false"
        style="margin-bottom: 16px;"
      />

      <el-form label-position="top">
        <el-form-item label="返工工单">
          <strong>{{ arbitratingOrder?.orderNo }} - {{ arbitratingOrder?.categoryFullName }}</strong>
          <div style="font-size: 13px; color: #E6A23C; margin-top: 4px;">
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
import { ref, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Refresh,
  Search,
  Location,
  Warning,
  Phone
} from '@element-plus/icons-vue';
import { httpClient } from '../../stores/auth';
import WorkOrderTimeline from '../../components/WorkOrderTimeline.vue';
import {
  ApiResponse,
  OrderStatusEnum,
  OrderStatusLabels,
  WorkerSkillLabels,
  DispatchOrderItemVO,
  DispatchPoolCategoryCountVO,
  DispatchPoolResultVO,
  WorkerBoardVO
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
    const res = await httpClient.get<any, ApiResponse<DispatchOrderItemVO>>(`/work-orders/${orderId}`);
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
    const res = await httpClient.get<any, ApiResponse<DispatchPoolResultVO>>('/work-orders/dispatch-pool', {
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

    const res = await httpClient.get<any, ApiResponse<WorkerBoardVO[]>>('/workers/dispatch-board', {
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
    const res = await httpClient.post<any, ApiResponse<null>>(`/work-orders/${selectedOrder.value.id}/dispatch`, {
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

    const res = await httpClient.post<any, ApiResponse<null>>(
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
    const res = await httpClient.post<any, ApiResponse<null>>(`/work-orders/${cancelingOrder.value.id}/cancel`, {
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

function getLoadColorClass(worker: WorkerBoardVO): string {
  if (worker.isOverloaded) return 'color-red';
  if (worker.loadRate >= 70) return 'color-orange';
  return 'color-green';
}

function getProgressBarStatus(worker: WorkerBoardVO): string {
  if (worker.isOverloaded) return 'exception';
  if (worker.loadRate >= 70) return 'warning';
  return 'success';
}

function formatTime(isoStr?: string): string {
  if (!isoStr) return '--';
  return isoStr.replace('T', ' ').substring(0, 16);
}
</script>

<style scoped>
.dispatch-console {
  display: flex;
  flex-direction: column;
  /* 外壳顶部条固定 56px，本页工作台占满剩余视口且自身不滚动 */
  height: calc(100vh - var(--topbar-h, 56px));
  background-color: #F0F2F5;
  overflow: hidden;
}

.pane-actions {
  display: flex;
  align-items: center;
  gap: 8px;
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

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #52C41A;
}

.live-pulse {
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(82, 196, 26, 0.7); }
  70% { transform: scale(1.1); box-shadow: 0 0 0 6px rgba(82, 196, 26, 0); }
  100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(82, 196, 26, 0); }
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
  background-color: #FFFFFF;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.pane-toolbar {
  padding: 14px 16px;
  border-bottom: 1px solid #F0F0F0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.filter-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tab-pill {
  padding: 6px 12px;
  border-radius: 16px;
  background-color: #F5F7FA;
  color: #4B5563;
  font-size: 13px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: all 0.2s;
  user-select: none;
}

.tab-pill:hover {
  background-color: #E6F4FF;
  color: #1677FF;
}

.tab-pill.active {
  background-color: #1677FF;
  color: #FFFFFF;
  font-weight: 500;
}

.badge {
  background-color: rgba(0, 0, 0, 0.08);
  padding: 1px 6px;
  border-radius: 10px;
  font-size: 11px;
}

.tab-pill.active .badge {
  background-color: rgba(255, 255, 255, 0.3);
  color: #FFFFFF;
}

.warning-badge {
  background-color: #FFF7E6;
  color: #D46B08;
}

.info-badge {
  background-color: #E6F4FF;
  color: #1677FF;
}

.urgent-badge {
  background-color: #FFF1F0;
  color: #CF1322;
}

.rework-pill {
  border: 1px solid #FFA39E;
  color: #CF1322;
}

.rework-pill.active {
  background-color: #F5222D !important;
  color: #FFFFFF !important;
}

.rework-badge {
  background-color: #CF1322;
  color: #FFFFFF;
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
  border: 1px solid #E5E7EB;
  border-radius: 8px;
  padding: 12px 14px;
  background-color: #FFFFFF;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.order-card:hover {
  border-color: #91CAFF;
  box-shadow: 0 2px 8px rgba(22, 119, 255, 0.08);
}

.order-card.selected {
  border-color: #1677FF;
  background-color: #F0F7FF;
  box-shadow: 0 0 0 1px #1677FF, 0 4px 12px rgba(22, 119, 255, 0.12);
}

.rework-border {
  border-left: 4px solid #F5222D !important;
}

.urgent-border {
  border-left: 4px solid #FA8C16;
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.order-no-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.order-no {
  font-weight: 600;
  color: #111827;
  font-size: 14px;
}

.order-time {
  font-size: 12px;
  color: #9CA3AF;
}

.card-category {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cat-name {
  font-size: 13px;
  font-weight: 500;
  color: #374151;
}

.card-loc {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #4B5563;
}

.loc-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-desc {
  font-size: 13px;
  color: #4B5563;
  line-height: 1.4;
  background-color: #FAFAFA;
  padding: 6px 8px;
  border-radius: 4px;
}

.card-user-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #6B7280;
}

.card-reason-banner {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #D46B08;
  background-color: #FFFBE6;
  border: 1px solid #FFE58F;
  border-radius: 4px;
  padding: 4px 8px;
}

.card-footer-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 6px;
  border-top: 1px dashed #E5E7EB;
}

.dispatch-type-tip {
  font-size: 12px;
  color: #6B7280;
}

.pagination-footer {
  padding: 8px 16px;
  border-top: 1px solid #F0F0F0;
  display: flex;
  justify-content: flex-end;
}

/* 右栏 45% 师傅看板 */
.right-pane {
  flex: 1;
  background-color: #FFFFFF;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.board-header {
  padding: 14px 16px;
  border-bottom: 1px solid #F0F0F0;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.board-title-wrap {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.board-title {
  font-size: 15px;
  font-weight: 600;
  color: #1F2937;
}

.board-rule-tag {
  font-size: 12px;
  color: #1677FF;
  background-color: #E6F4FF;
  padding: 2px 6px;
  border-radius: 4px;
  display: inline-block;
  width: fit-content;
}

.board-rule-tag.empty {
  color: #9CA3AF;
  background-color: #F3F4F6;
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
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.worker-card {
  border: 1px solid #E5E7EB;
  border-radius: 8px;
  padding: 12px;
  background-color: #FFFFFF;
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: all 0.2s;
}

.worker-card:hover {
  border-color: #1677FF;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.worker-card.overloaded {
  background-color: #FFF1F0;
  border-color: #FFA39E;
}

.worker-card.offduty {
  opacity: 0.65;
  background-color: #F9FAFB;
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

.avatar-box {
  width: 36px;
  height: 36px;
  border-radius: 18px;
  background-color: #1677FF;
  color: #FFFFFF;
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 15px;
  font-weight: 600;
}

.name-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.real-name {
  font-weight: 600;
  color: #111827;
  font-size: 14px;
}

.worker-skill {
  font-size: 12px;
  color: #6B7280;
}

.rating-box {
  display: flex;
  align-items: center;
  gap: 2px;
  background-color: #FFFBE6;
  border: 1px solid #FFE58F;
  padding: 2px 6px;
  border-radius: 4px;
}

.star-icon {
  color: #FAAD14;
  font-size: 13px;
}

.star-score {
  font-weight: 600;
  color: #D48806;
  font-size: 12px;
}

.load-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.load-text-bar {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #4B5563;
}

.color-green {
  color: #52C41A;
}

.color-orange {
  color: #FA8C16;
}

.color-red {
  color: #F5222D;
}

.worker-kpi-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background-color: #F9FAFB;
  border-radius: 6px;
  padding: 6px 10px;
}

.kpi-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.kpi-label {
  font-size: 11px;
  color: #9CA3AF;
}

.kpi-val {
  font-size: 12px;
  color: #1F2937;
}

.kpi-divider {
  width: 1px;
  height: 18px;
  background-color: #E5E7EB;
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
  font-size: 12px;
  color: #6B7280;
}
</style>
