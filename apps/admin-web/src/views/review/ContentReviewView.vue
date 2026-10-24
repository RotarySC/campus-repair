<template>
  <div class="content-review">
    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- ==================== ① 待复核队列 ==================== -->
      <el-tab-pane label="待复核队列" name="pending">
        <div class="tab-toolbar">
          <div class="search-row">
            <el-input
              v-model="pendingQuery.keyword"
              placeholder="搜索工单号 / 公告标题"
              clearable
              style="width: 220px;"
              @clear="searchPending"
              @keyup.enter="searchPending"
            />
            <el-select v-model="pendingQuery.bizType" style="width: 130px;" @change="searchPending">
              <el-option label="全部类型" value="" />
              <el-option label="报修工单" value="ORDER" />
              <el-option label="维修公告" value="ANNOUNCEMENT" />
            </el-select>
            <el-button type="primary" icon="Search" @click="searchPending">查询</el-button>
            <el-button icon="Refresh" @click="loadPending">刷新</el-button>
          </div>
          <span class="tab-hint">共 {{ pendingTotal }} 条待复核</span>
        </div>

        <p class="tab-note">
          初筛命中的正文与公告先落到这里，人工复核只做两个结论：通过（放行并补派单）或驳回并填写理由
          （报修人修改后可重提，轮次 +1 再入队列）。队列不发出站内信，靠刷新与角标获知。
        </p>

        <ResponsiveTable
          class="review-table"
          :items="pendingList"
          :columns="pendingColumns"
          :loading="pendingLoading"
          empty-text="暂无待复核内容，初筛队列已清空"
          clickable
          @row-click="openDetail"
        >
          <template #col-bizNo="{ row }">
            <span class="cell-strong">{{ bizNoText(row) }}</span>
          </template>
          <template #col-hitRules="{ row }">
            <!-- 命中分类按 `|` 拆成多个 chip：文字规则与图片抽检可以叠加（`AD:广告导流|IMAGE:图片抽检`） -->
            <span v-if="hitRulesOf(row).length" class="rule-row">
              <MetaChip v-for="rule in hitRulesOf(row)" :key="`${rule.code}-${rule.detail}`">
                {{ rule.label }}
              </MetaChip>
            </span>
            <span v-else class="text-faint">—</span>
          </template>
          <template #col-submitRound="{ row }">第 {{ row.submitRound }} 轮</template>
          <template #col-actions="{ row }">
            <el-button link type="primary" size="small" @click.stop="openDetail(row)">复核</el-button>
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
              placeholder="搜索工单号 / 公告标题"
              clearable
              style="width: 220px;"
              @clear="searchRecords"
              @keyup.enter="searchRecords"
            />
            <el-select v-model="recordsQuery.bizType" style="width: 130px;" @change="searchRecords">
              <el-option label="全部类型" value="" />
              <el-option label="报修工单" value="ORDER" />
              <el-option label="维修公告" value="ANNOUNCEMENT" />
            </el-select>
            <el-select v-model="recordsQuery.outcome" style="width: 130px;" @change="searchRecords">
              <el-option label="全部结论" value="ALL" />
              <el-option label="待复核" value="PENDING" />
              <el-option label="已通过" value="APPROVED" />
              <el-option label="已驳回" value="REJECTED" />
            </el-select>
            <el-button type="primary" icon="Search" @click="searchRecords">查询</el-button>
          </div>
          <span class="tab-hint">共 {{ recordsTotal }} 条审核记录</span>
        </div>

        <p class="tab-note">
          审核轨迹按提交轮次 append-only：同一张工单重提会多一行、旧行结论保留，
          所以「上一轮为什么被驳回、这一轮改了什么」可以逐轮追溯。
        </p>

        <ResponsiveTable
          class="review-table"
          :items="recordsList"
          :columns="recordsColumns"
          :loading="recordsLoading"
          empty-text="暂无审核记录"
          clickable
          @row-click="openDetail"
        >
          <template #col-bizNo="{ row }">
            <span class="cell-strong">{{ bizNoText(row) }}</span>
          </template>
          <template #col-outcome="{ row }">
            <MetaChip>{{ outcomeText(row.outcome) }}</MetaChip>
          </template>
          <template #col-submitRound="{ row }">第 {{ row.submitRound }} 轮</template>
          <template #col-hitRules="{ row }">
            <span v-if="hitRulesOf(row).length" class="rule-row">
              <MetaChip v-for="rule in hitRulesOf(row)" :key="`${rule.code}-${rule.detail}`">
                {{ rule.label }}
              </MetaChip>
            </span>
            <span v-else class="text-faint">—</span>
          </template>
          <template #col-reviewReason="{ row }">
            <span :class="{ 'text-faint': !row.reviewReason }">{{ row.reviewReason || '—' }}</span>
          </template>
          <template #col-actions="{ row }">
            <el-button link type="primary" size="small" @click.stop="openDetail(row)">查看</el-button>
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

      <!-- ==================== ③ 敏感词表（仅平台管理） ==================== -->
      <el-tab-pane v-if="canMaintainWords" label="敏感词表" name="words">
        <div class="tab-toolbar">
          <div class="search-row">
            <el-select v-model="wordQuery.category" style="width: 140px;" @change="searchWords">
              <el-option label="全部分类" value="" />
              <el-option
                v-for="category in SENSITIVE_WORD_OPTIONS"
                :key="category"
                :label="sensitiveCategoryLabel(category)"
                :value="category"
              />
            </el-select>
            <el-select v-model="wordQuery.enabled" style="width: 120px;" @change="searchWords">
              <el-option label="全部状态" value="" />
              <el-option label="启用" value="1" />
              <el-option label="停用" value="0" />
            </el-select>
            <el-button type="primary" icon="Search" @click="searchWords">查询</el-button>
          </div>
          <el-button type="primary" icon="Plus" @click="openWordDialog()">新增敏感词</el-button>
        </div>

        <p class="tab-note">
          词表是初筛的数据来源，属系统参数性质，因此只对平台管理开放。
          停用只改开关、不删行——历史审核行里的命中记录要能回指到同一个词。
        </p>

        <ResponsiveTable
          class="review-table"
          :items="wordList"
          :columns="wordColumns"
          :loading="wordLoading"
          empty-text="暂无敏感词，初筛只走确定性规则"
        >
          <template #col-word="{ row }">
            <span class="cell-strong">{{ row.word }}</span>
          </template>
          <template #col-category="{ row }">
            <MetaChip>{{ sensitiveCategoryLabel(row.category) }}</MetaChip>
          </template>
          <template #col-enabled="{ row }">
            <div class="status-cell">
              <el-switch
                :model-value="row.enabled === 1"
                aria-label="敏感词启用状态"
                @change="(val: boolean) => toggleWord(row, val)"
              />
              <span class="status-text">{{ row.enabled === 1 ? '启用' : '停用' }}</span>
            </div>
          </template>
          <template #col-actions="{ row }">
            <div class="row-actions">
              <el-button link type="primary" size="small" @click="openWordDialog(row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="removeWord(row)">删除</el-button>
            </div>
          </template>
        </ResponsiveTable>

        <div class="pagination-row">
          <el-pagination
            v-model:current-page="wordQuery.page"
            v-model:page-size="wordQuery.size"
            :total="wordTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="loadWords"
            @current-change="loadWords"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- ==================== 详情抽屉：正文快照 / 附件 / 命中规则 / 历史轮次 ==================== -->
    <el-drawer
      v-model="detailVisible"
      :title="detailTitle"
      size="min(600px, 94vw)"
      direction="rtl"
      @closed="resetDetail"
    >
      <div v-loading="detailLoading" class="detail-body">
        <el-descriptions v-if="detail" :column="1" border size="small">
          <el-descriptions-item label="审核对象">
            {{ bizTypeText(detail.bizType) }}
          </el-descriptions-item>
          <el-descriptions-item label="工单号 / 标题">{{ bizNoText(detail) }}</el-descriptions-item>
          <el-descriptions-item label="报修人 / 作者">{{ detail.applicantName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="空间路径">{{ detail.spaceFullPath || '—' }}</el-descriptions-item>
          <el-descriptions-item label="故障分类">{{ detail.categoryFullName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="提交轮次">第 {{ detail.submitRound }} 轮</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ formatDateTime(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="当前结论">
            <MetaChip>{{ outcomeText(detail.outcome) }}</MetaChip>
            <span v-if="detail.outcome !== 'PENDING'" class="conclusion-meta">
              {{ detail.reviewerName || '—' }} · {{ formatDateTime(detail.reviewedAt) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.reviewReason" label="审核说明">
            {{ detail.reviewReason }}
          </el-descriptions-item>
        </el-descriptions>

        <section class="drawer-block">
          <h3 class="block-title">正文快照（提交当时）</h3>
          <p class="snapshot">{{ detail?.contentSnapshot || '—' }}</p>
        </section>

        <section class="drawer-block">
          <h3 class="block-title">附件</h3>
          <div v-if="detailImages.length" class="image-row">
            <el-image
              v-for="(img, idx) in detailImages"
              :key="img"
              :src="img"
              :preview-src-list="detailImages"
              :initial-index="idx"
              fit="cover"
              preview-teleported
              class="thumb"
            />
          </div>
          <p v-else class="text-faint">本次提交没有图片附件</p>
        </section>

        <section class="drawer-block">
          <h3 class="block-title">命中规则</h3>
          <div v-if="detailHitRules.length" class="rule-row">
            <MetaChip v-for="rule in detailHitRules" :key="`${rule.code}-${rule.detail}`">
              {{ rule.label }}
              <!-- 后端目前把「分类码:分类中文名」两段写成同值，只在真的不同时补冒号，避免「广告导流：广告导流」 -->
              <template v-if="rule.detail && rule.detail !== rule.label">：{{ rule.detail }}</template>
            </MetaChip>
          </div>
          <p v-else class="text-faint">未记录命中规则（历史数据或人工入队）</p>
        </section>

        <section class="drawer-block">
          <h3 class="block-title">历史轮次</h3>
          <el-timeline v-if="historyRounds.length" class="history-timeline">
            <el-timeline-item
              v-for="round in historyRounds"
              :key="round.id"
              :timestamp="formatDateTime(round.createdAt)"
              placement="top"
            >
              <div class="history-item">
                <div class="history-head">
                  <span class="cell-strong">第 {{ round.submitRound }} 轮</span>
                  <MetaChip>{{ outcomeText(round.outcome) }}</MetaChip>
                </div>
                <p class="history-snapshot">{{ round.contentSnapshot }}</p>
                <p v-if="round.hitRules" class="history-meta">命中：{{ hitRulesText(round.hitRules) }}</p>
                <p v-if="round.reviewReason" class="history-meta">审核说明：{{ round.reviewReason }}</p>
                <p class="history-meta text-faint">
                  {{ round.reviewerName || '尚未复核' }} · {{ formatDateTime(round.reviewedAt) }}
                </p>
              </div>
            </el-timeline-item>
          </el-timeline>
          <EmptyState v-else description="该内容只有本次提交记录" :size="72" />
        </section>

        <!-- 决策按钮放在正文 / 附件 / 命中规则 / 历史轮次之后：复核的前提是先读提交物 -->
        <div v-if="detail && detail.outcome === 'PENDING'" class="drawer-actions">
          <el-button type="danger" @click="openReject(detail)">驳回并填写理由</el-button>
          <el-button type="primary" @click="openApprove(detail)">通过</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 通过：备注可选 -->
    <el-dialog
      v-model="approveVisible"
      title="复核通过"
      width="min(480px, 94vw)"
      :close-on-click-modal="false"
    >
      <p class="dialog-note">
        通过后内容放行：工单进入派单池并补发派单事件；公告转为「已通过」，可继续发布。
      </p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="复核备注（可选，将写入审核轨迹）">
          <el-input
            v-model="approveReason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="例如：人工核读全文，仅为报修人自己的联系方式，无导流意图"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="approveVisible = false">取消</el-button>
        <el-button type="primary" :loading="approving" @click="submitApprove">确认通过</el-button>
      </template>
    </el-dialog>

    <!-- 驳回：理由必填 ≤500 -->
    <el-dialog
      v-model="rejectVisible"
      title="驳回内容"
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
        <el-form-item label="驳回理由（必填，将展示给报修人 / 公告作者）" prop="reason">
          <el-input
            v-model="rejectForm.reason"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="例如：正文含广告导流内容，请删除联系方式后重新提交"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="rejecting" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 敏感词新增 / 编辑 -->
    <el-dialog
      v-model="wordDialogVisible"
      :title="wordForm.id ? '编辑敏感词' : '新增敏感词'"
      width="min(460px, 94vw)"
      @closed="wordFormRef?.clearValidate()"
    >
      <el-form
        ref="wordFormRef"
        :model="wordForm"
        :rules="wordRules"
        label-position="top"
        @submit.prevent
      >
        <el-form-item label="敏感词（≤64 字，全局唯一）" prop="word">
          <el-input v-model="wordForm.word" maxlength="64" show-word-limit placeholder="例如：加微信" />
        </el-form-item>
        <el-form-item label="命中分类" prop="category">
          <el-select v-model="wordForm.category" style="width: 100%;">
            <el-option
              v-for="category in SENSITIVE_WORD_OPTIONS"
              :key="category"
              :label="sensitiveCategoryLabel(category)"
              :value="category"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch
            :model-value="wordForm.enabled === 1"
            aria-label="敏感词启用状态"
            @change="(val: boolean) => (wordForm.enabled = val ? 1 : 0)"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="wordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="wordSaving" @click="submitWord">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import {
  CONTENT_BIZ_TYPE_LABELS,
  REVIEW_OUTCOME_LABELS,
  SENSITIVE_WORD_CATEGORIES,
  formatDateTime,
  parseHitRules,
  parseImageUrls,
  sensitiveCategoryLabel
} from '@campus/shared';
import type {
  ContentBizType,
  ContentReviewDetailVO,
  ContentReviewVO,
  PageResult,
  ReviewOutcome,
  SensitiveWordCategory,
  SensitiveWordVO
} from '@campus/shared';
import { httpClient } from '../../stores/auth';
import { useIdentity } from '../../composables/useIdentity';
import ResponsiveTable from '../../components/ResponsiveTable.vue';
import MetaChip from '../../components/MetaChip.vue';
import EmptyState from '../../components/EmptyState.vue';
import type { TableColumn } from '../../components/ResponsiveTable.vue';

/** 驳回理由上限（与后端 `review_reason VARCHAR(500)` 对齐） */
const REJECT_REASON_MAX = 500;

/**
 * 词表维护页可选的分类：**只有四类**。
 * `IMAGE`（图片抽检）也在命中标签表里，但它不由词表维护，绝不能出现在这个下拉里。
 */
const SENSITIVE_WORD_OPTIONS = SENSITIVE_WORD_CATEGORIES;

/** 敏感词表只对平台管理开放。判据走**职能**（`roleCodes`），**不用路由**——
 *  路由对调度也放行（他要用队列与公告），词表只是这一页里的一个 tab。 */
const { has } = useIdentity();
const canMaintainWords = computed(() => has('PLATFORM_ADMIN'));

const activeTab = ref<'pending' | 'records' | 'words'>('pending');

function bizNoText(row: ContentReviewVO): string {
  if (row.bizNo) return row.bizNo;
  return row.bizType === 'ANNOUNCEMENT' ? `公告 #${row.bizId}` : `工单 #${row.bizId}`;
}

function bizTypeText(value: ContentBizType | string): string {
  return CONTENT_BIZ_TYPE_LABELS[value as ContentBizType] || String(value);
}

function outcomeText(value: ReviewOutcome | string): string {
  return REVIEW_OUTCOME_LABELS[value as ReviewOutcome] || String(value);
}

/**
 * 命中分类标签：后端只给「分类码 + 分类中文名」，词表原文不出后端。
 * 中文名与未知码回退统一走 `sensitiveCategoryLabel`（未知码显示原码，不显示空白）。
 */
function hitRulesOf(row: ContentReviewVO) {
  return parseHitRules(row.hitRules);
}

/** `hit_rules` 原始串对读者不友好，历史轮次里压成「广告导流 · 图片抽检」 */
function hitRulesText(raw?: string | null): string {
  return parseHitRules(raw)
    .map((rule) => rule.label)
    .join(' · ');
}

/* ==================== ① 待复核队列 ==================== */
const pendingLoading = ref(false);
const pendingList = ref<ContentReviewVO[]>([]);
const pendingTotal = ref(0);
const pendingQuery = reactive({ page: 1, size: 10, keyword: '', bizType: '' });

const pendingColumns: TableColumn<ContentReviewVO>[] = [
  { key: 'bizNo', label: '工单号 / 公告标题', minWidth: 155, card: 'title' },
  {
    key: 'bizType',
    label: '类型',
    width: 100,
    card: 'tag',
    cardTag: (row) => ({ text: bizTypeText(row.bizType) })
  },
  { key: 'applicantName', label: '报修人 / 作者', width: 115 },
  { key: 'spaceFullPath', label: '空间路径', minWidth: 170, showOverflowTooltip: true },
  { key: 'categoryFullName', label: '故障分类', minWidth: 130 },
  { key: 'hitRules', label: '命中分类', minWidth: 170 },
  { key: 'submitRound', label: '轮次', width: 80, align: 'center' },
  { key: 'createdAt', label: '提交时间', width: 140, valueType: 'datetime' },
  { key: 'actions', label: '操作', width: 90, align: 'center', card: 'action' }
];

async function loadPending() {
  pendingLoading.value = true;
  try {
    const res = await httpClient.get<PageResult<ContentReviewVO>>('/content-reviews', {
      params: {
        // 队列固定取待复核；空串表示不按类型过滤
        outcome: 'PENDING',
        bizType: pendingQuery.bizType || undefined,
        keyword: pendingQuery.keyword || undefined,
        page: pendingQuery.page,
        size: pendingQuery.size
      }
    });
    if (res.code === 200) {
      pendingList.value = res.data?.records || [];
      pendingTotal.value = res.data?.total || 0;
    } else {
      ElMessage.error(res.message || '加载待复核队列失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '加载待复核队列失败');
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
const recordsList = ref<ContentReviewVO[]>([]);
const recordsTotal = ref(0);
/** 记录 tab 是否已被打开过：没打开过就不必在复核动作后白跑一次请求 */
const recordsLoaded = ref(false);
const recordsQuery = reactive({ page: 1, size: 10, keyword: '', bizType: '', outcome: 'ALL' });

const recordsColumns: TableColumn<ContentReviewVO>[] = [
  { key: 'bizNo', label: '工单号 / 公告标题', minWidth: 150, card: 'title' },
  {
    key: 'bizType',
    label: '类型',
    width: 100,
    card: 'tag',
    cardTag: (row) => ({ text: bizTypeText(row.bizType) })
  },
  {
    key: 'outcome',
    label: '结论',
    width: 95,
    card: 'tag',
    cardTag: (row) => ({ text: outcomeText(row.outcome) })
  },
  { key: 'submitRound', label: '轮次', width: 70, align: 'center' },
  { key: 'hitRules', label: '命中分类', minWidth: 165 },
  { key: 'reviewReason', label: '审核说明', minWidth: 180, showOverflowTooltip: true },
  { key: 'reviewerName', label: '审核人', width: 105 },
  { key: 'reviewedAt', label: '审核时间', width: 140, valueType: 'datetime' },
  { key: 'createdAt', label: '提交时间', width: 140, valueType: 'datetime', cardOnly: true },
  { key: 'actions', label: '操作', width: 80, align: 'center', card: 'action' }
];

async function loadRecords() {
  recordsLoading.value = true;
  try {
    const res = await httpClient.get<PageResult<ContentReviewVO>>('/content-reviews', {
      params: {
        // 后端口径：outcome 省略/空 = 只查待复核；ALL = 全部；其余按字面量过滤。
        // 「全部结论」必须显式传 ALL，省略会被当成「只要待复核」。
        outcome: recordsQuery.outcome,
        bizType: recordsQuery.bizType || undefined,
        keyword: recordsQuery.keyword || undefined,
        page: recordsQuery.page,
        size: recordsQuery.size
      }
    });
    if (res.code === 200) {
      recordsList.value = res.data?.records || [];
      recordsTotal.value = res.data?.total || 0;
      recordsLoaded.value = true;
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

/* ==================== ③ 敏感词表 ==================== */
const wordLoading = ref(false);
const wordList = ref<SensitiveWordVO[]>([]);
const wordTotal = ref(0);
const wordQuery = reactive({ page: 1, size: 10, category: '', enabled: '' });

const wordColumns: TableColumn<SensitiveWordVO>[] = [
  { key: 'word', label: '敏感词', minWidth: 140, card: 'title' },
  {
    key: 'category',
    label: '命中分类',
    width: 130,
    card: 'tag',
    cardTag: (row) => ({ text: sensitiveCategoryLabel(row.category) })
  },
  { key: 'enabled', label: '状态', width: 130, align: 'center' },
  { key: 'createdAt', label: '创建时间', width: 140, valueType: 'datetime' },
  { key: 'actions', label: '操作', width: 130, align: 'center', card: 'action' }
];

async function loadWords() {
  if (!canMaintainWords.value) return;
  wordLoading.value = true;
  try {
    const res = await httpClient.get<PageResult<SensitiveWordVO>>('/content-rules/words', {
      params: {
        category: wordQuery.category || undefined,
        // 空串 = 不按启停筛选；后端按 0/1 过滤
        enabled: wordQuery.enabled === '' ? undefined : Number(wordQuery.enabled),
        page: wordQuery.page,
        size: wordQuery.size
      }
    });
    if (res.code === 200) {
      wordList.value = res.data?.records || [];
      wordTotal.value = res.data?.total || 0;
    } else {
      ElMessage.error(res.message || '加载敏感词表失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '加载敏感词表失败');
  } finally {
    wordLoading.value = false;
  }
}

function searchWords() {
  wordQuery.page = 1;
  loadWords();
}

const wordDialogVisible = ref(false);
const wordSaving = ref(false);
const wordFormRef = ref<FormInstance>();
const wordForm = reactive<{ id: number | null; word: string; category: SensitiveWordCategory; enabled: number }>({
  id: null,
  word: '',
  category: 'AD',
  enabled: 1
});

const wordRules: FormRules = {
  word: [
    { required: true, message: '请输入敏感词', trigger: 'blur' },
    { max: 64, message: '敏感词不超过 64 个字符', trigger: 'blur' }
  ],
  category: [{ required: true, message: '请选择命中分类', trigger: 'change' }]
};

function openWordDialog(row?: SensitiveWordVO) {
  wordForm.id = row?.id ?? null;
  wordForm.word = row?.word ?? '';
  wordForm.category = row?.category ?? 'AD';
  wordForm.enabled = row?.enabled ?? 1;
  wordDialogVisible.value = true;
}

async function submitWord() {
  const valid = await wordFormRef.value?.validate().catch(() => false);
  if (!valid) return;

  wordSaving.value = true;
  try {
    const payload = {
      word: wordForm.word.trim(),
      category: wordForm.category,
      enabled: wordForm.enabled
    };
    const res = wordForm.id
      ? await httpClient.put<null>(`/content-rules/words/${wordForm.id}`, payload)
      : await httpClient.post<null>('/content-rules/words', payload);
    if (res.code === 200) {
      ElMessage.success(wordForm.id ? '敏感词已更新' : '敏感词已新增，初筛词表已刷新');
      wordDialogVisible.value = false;
      await loadWords();
    } else {
      ElMessage.error(res.message || '保存敏感词失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '保存敏感词失败');
  } finally {
    wordSaving.value = false;
  }
}

/**
 * 启停：`PUT /content-rules/words/{id}` 的契约是「改词 / 改类 / 启停」共用同一端点，
 * 所以这里**回传整行**（word + category + enabled），不赌后端支持部分更新。
 */
async function toggleWord(row: SensitiveWordVO, enabled: boolean) {
  const next = enabled ? 1 : 0;
  try {
    const res = await httpClient.put<null>(`/content-rules/words/${row.id}`, {
      word: row.word,
      category: row.category,
      enabled: next
    });
    if (res.code === 200) {
      row.enabled = next;
      ElMessage.success(next === 1 ? '已启用，后续初筛立即生效' : '已停用，该词不再参与初筛');
    } else {
      ElMessage.error(res.message || '更新敏感词状态失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '更新敏感词状态失败');
  }
}

async function removeWord(row: SensitiveWordVO) {
  try {
    await ElMessageBox.confirm(
      `确认删除敏感词【${row.word}】？删除后历史审核行里的命中记录仍保留，但该词不再参与初筛。`,
      '删除敏感词',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    );
  } catch {
    return;
  }
  try {
    const res = await httpClient.delete<null>(`/content-rules/words/${row.id}`);
    if (res.code === 200) {
      ElMessage.success('敏感词已删除');
      if (wordList.value.length === 1 && wordQuery.page > 1) wordQuery.page -= 1;
      await loadWords();
    } else {
      ElMessage.error(res.message || '删除敏感词失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '删除敏感词失败');
  }
}

/* ==================== 详情抽屉 ==================== */
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<ContentReviewDetailVO | null>(null);
/** 抽屉打开瞬间先用队列行占位，详情接口回来后再换成完整对象（含历史轮次） */
const detailSeed = ref<ContentReviewVO | null>(null);

const detailTitle = computed(() => {
  const row = detail.value ?? detailSeed.value;
  return row ? `内容复核 - ${bizNoText(row)}` : '内容复核';
});

/**
 * 附件缩略图地址。
 *
 * 后端上传接口回的是**相对路径** `/uploads/{yyyyMMdd}/{uuid}.jpg`，由后端静态资源映射提供、
 * Vite dev server 也把 `/uploads` 代理到同一后端，所以这里原样交给 `el-image` 即可——
 * 与「我的工单」详情、完工填报两处既有展示**同一基址、同一种写法**（远端 URL 也照样能用）。
 */
const detailImages = computed(() => parseImageUrls(detail.value?.attachments));
const detailHitRules = computed(() => parseHitRules(detail.value?.hitRules));
/** 历史轮次按提交轮次升序；后端可能返回空数组（只有一轮） */
const historyRounds = computed(() =>
  [...(detail.value?.history ?? [])].sort((a, b) => a.submitRound - b.submitRound)
);

/** 关闭抽屉时清空占位与详情，避免下一次打开先闪一眼上一单的内容 */
function resetDetail() {
  detail.value = null;
  detailSeed.value = null;
}

async function openDetail(row: ContentReviewVO) {
  detailSeed.value = row;
  detail.value = row;
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    const res = await httpClient.get<ContentReviewDetailVO>(`/content-reviews/${row.id}`);
    if (res.code === 200 && res.data) {
      detail.value = res.data;
    } else {
      ElMessage.error(res.message || '加载审核详情失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '加载审核详情失败');
  } finally {
    detailLoading.value = false;
  }
}

/* ==================== 复核动作 ==================== */
const approveVisible = ref(false);
const approving = ref(false);
const approveReason = ref('');
const approveTarget = ref<ContentReviewVO | null>(null);

function openApprove(row: ContentReviewVO) {
  approveTarget.value = row;
  approveReason.value = '';
  approveVisible.value = true;
}

async function submitApprove() {
  if (!approveTarget.value) return;
  approving.value = true;
  try {
    const res = await httpClient.post<null>(`/content-reviews/${approveTarget.value.id}/approve`, {
      reason: approveReason.value.trim() || undefined
    });
    if (res.code === 200) {
      ElMessage.success('复核通过，内容已放行');
      approveVisible.value = false;
      await afterDecision();
    } else if (res.code === 2007) {
      // 2007 REVIEW_ALREADY_DECIDED：并发/重复点击，另一处已经决策过
      ElMessage.warning(res.message || '该审核记录已被处理，已刷新队列');
      approveVisible.value = false;
      await afterDecision();
    } else {
      ElMessage.error(res.message || '复核通过失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '复核通过失败');
  } finally {
    approving.value = false;
  }
}

const rejectVisible = ref(false);
const rejecting = ref(false);
const rejectTarget = ref<ContentReviewVO | null>(null);
const rejectFormRef = ref<FormInstance>();
const rejectForm = reactive({ reason: '' });

const rejectRules: FormRules = {
  reason: [
    { required: true, message: '驳回理由必填，报修人需要看到原因', trigger: 'blur' },
    { max: REJECT_REASON_MAX, message: `驳回理由不能超过 ${REJECT_REASON_MAX} 个字符`, trigger: 'blur' }
  ]
};

function openReject(row: ContentReviewVO) {
  rejectTarget.value = row;
  rejectForm.reason = '';
  rejectVisible.value = true;
}

async function submitReject() {
  const valid = await rejectFormRef.value?.validate().catch(() => false);
  if (!valid || !rejectTarget.value) return;

  rejecting.value = true;
  try {
    const res = await httpClient.post<null>(`/content-reviews/${rejectTarget.value.id}/reject`, {
      reason: rejectForm.reason.trim()
    });
    if (res.code === 200) {
      ElMessage.success('已驳回，理由会展示给报修人 / 公告作者');
      rejectVisible.value = false;
      await afterDecision();
    } else if (res.code === 2007) {
      ElMessage.warning(res.message || '该审核记录已被处理，已刷新队列');
      rejectVisible.value = false;
      await afterDecision();
    } else {
      ElMessage.error(res.message || '驳回失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '驳回失败');
  } finally {
    rejecting.value = false;
  }
}

/** 决策后的统一收尾：关抽屉、刷新队列；记录 tab 打开过才刷新它 */
async function afterDecision() {
  detailVisible.value = false;
  detail.value = null;
  detailSeed.value = null;
  await loadPending();
  if (recordsLoaded.value) await loadRecords();
}

/* ==================== 生命周期 ==================== */
function onTabChange(name: string | number) {
  if (name === 'pending') {
    loadPending();
  } else if (name === 'records') {
    loadRecords();
  } else if (name === 'words') {
    loadWords();
  }
}

onMounted(loadPending);
</script>

<style scoped>
.content-review {
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
  line-height: 1.7;
  color: var(--faint);
}

.review-table {
  display: block;
  margin-top: 14px;
}

.cell-strong {
  font-weight: var(--fw-medium);
  color: var(--ink);
}

.rule-row {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
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

/* ---------- 详情抽屉 ---------- */
.detail-body {
  min-height: 120px;
}

.drawer-block {
  margin-top: 20px;
}

.block-title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: var(--fw-medium);
  color: var(--ink);
}

.conclusion-meta {
  margin-inline-start: var(--mark-gap);
  font-size: var(--fs-base);
  color: var(--faint);
}

.snapshot {
  margin: 0;
  padding: 10px 12px;
  border: var(--hairline) solid var(--line);
  border-radius: var(--radius);
  background-color: var(--canvas);
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink);
  white-space: pre-wrap;
  word-break: break-word;
}

.image-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.thumb {
  width: 92px;
  height: 92px;
  border: var(--hairline) solid var(--line);
  border-radius: var(--radius);
}

.history-item {
  padding: 10px 12px;
  border: var(--hairline) solid var(--line);
  border-radius: var(--radius);
}

.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 13px;
}

.history-snapshot {
  margin: 0 0 6px;
  font-size: var(--fs-base);
  line-height: 1.7;
  color: var(--muted);
  white-space: pre-wrap;
  word-break: break-word;
}

.history-meta {
  margin: 2px 0 0;
  font-size: var(--fs-base);
  color: var(--muted);
}

/* 时间轴节点**必须中性化**：EP 缺省取 `--el-color-primary`（蓝），
   而这个节点只是每一步的占位符，不承载状态语义。与入库流水时间轴同一做法。 */
.history-timeline :deep(.el-timeline-item__node) {
  background-color: var(--faint);
}

.history-timeline :deep(.el-timeline-item__tail) {
  border-left-color: var(--line);
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 20px;
  padding-top: 14px;
  border-top: var(--hairline) solid var(--line);
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
