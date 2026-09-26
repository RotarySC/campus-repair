<template>
  <div v-if="enabled" class="assistant-root">
    <!-- 悬浮气泡：全角色可见；助手关闭（sys_config.assistant_enabled=0）时整块不渲染 -->
    <button
      type="button"
      class="assistant-bubble"
      :aria-label="open ? '收起智能助手' : '打开智能助手'"
      :aria-expanded="open"
      aria-controls="assistant-drawer-panel"
      title="智能助手"
      @click="open = true"
    >
      <el-icon :size="20"><ChatDotRound /></el-icon>
      <span class="bubble-text">助手</span>
    </button>

    <el-drawer
      v-model="open"
      title="智能助手"
      :size="drawerSize"
      direction="rtl"
      class="assistant-drawer"
      @open="onFirstOpen"
    >
      <div id="assistant-drawer-panel" class="assistant-body">
        <!-- ① 故障判断：只对报修人（其他职能不提单，给它们推荐没有意义） -->
        <section v-if="isReporter" class="block">
          <h3 class="block-title">不知道选哪个故障类型？</h3>
          <p class="block-hint">用大白话描述现象，我来帮你判断该选哪个细类、对应哪个工种、多久能修好。</p>
          <el-input
            v-model="description"
            type="textarea"
            :rows="3"
            maxlength="300"
            show-word-limit
            placeholder="例如：卫生间水龙头一直滴水，关不紧"
            @keydown.enter.ctrl.prevent="onSuggest"
          />
          <div class="block-actions">
            <el-button type="primary" :loading="suggesting" @click="onSuggest">帮我判断</el-button>
            <span class="block-hint">Ctrl + Enter 快速判断</span>
          </div>

          <!--
            提问后输入框会清空——把问题留在框里不符合直觉（用户以为已经问过了）。
            问题不丢：落进「最近询问」，一键重问；单条可删、也可整体清空。
          -->
          <div v-if="history.length" class="history">
            <div class="history-head">
              <span class="history-title">最近询问</span>
              <el-button link size="small" @click="clearHistory">清空</el-button>
            </div>
            <div class="history-list">
              <div v-for="item in history" :key="item" class="history-chip-wrap">
                <button
                  type="button"
                  class="history-chip"
                  :title="`再次判断：${item}`"
                  :aria-label="`再次判断：${item}`"
                  @click="reuseHistory(item)"
                >
                  <span class="history-chip-text">{{ item }}</span>
                </button>
                <button
                  type="button"
                  class="history-chip-remove"
                  :title="`删除这条记录：${item}`"
                  :aria-label="`删除这条记录：${item}`"
                  @click="removeHistory(item)"
                >
                  <el-icon :size="10"><Close /></el-icon>
                </button>
              </div>
            </div>
          </div>

          <el-alert
            v-if="suggestError"
            class="inline-alert"
            type="info"
            show-icon
            :closable="false"
            :title="suggestError"
          />
          <el-alert
            v-else-if="suggestion?.degradedReason"
            class="inline-alert"
            type="info"
            show-icon
            :closable="false"
            :title="ASSISTANT_DEGRADED_LABELS[suggestion.degradedReason]"
          />

          <SuggestionCardList
            v-if="suggestion && suggestion.candidates.length"
            :candidates="suggestion.candidates"
            @apply="applyCandidate"
          />

          <div v-else-if="suggestion && suggestion.noMatch" class="no-match">
            <p class="block-hint">没听明白是什么故障。可以直接从大类里挑一个，或者补充一点现象（哪里、什么声音、什么时候开始的）。</p>
            <div class="fallback-tags">
              <el-tag
                v-for="item in suggestion.fallbackCategories"
                :key="item.id"
                class="fallback-tag"
                effect="plain"
                @click="goSubmit()"
              >
                {{ item.name }}
              </el-tag>
            </div>
          </div>

          <!-- ② 提交前自助排查：先自救，省一趟上门 -->
          <div v-if="selfHelpTips.length" class="self-help">
            <h4 class="self-help-title">提交前可以先试试（若已解决就不必报修）</h4>
            <ol class="self-help-list">
              <li v-for="(step, index) in selfHelpTips" :key="index">{{ step }}</li>
            </ol>
          </div>
        </section>

        <!-- ③ 我的工单速查：只对报修人 -->
        <section v-if="isReporter" class="block">
          <h3 class="block-title">我的工单进度</h3>
          <div v-if="progress" class="progress-card">
            <p class="progress-line">
              <span class="progress-no">{{ progress.orderNo }}</span>
              <el-tag size="small" effect="light">{{ progress.statusLabel }}</el-tag>
            </p>
            <p class="progress-next">{{ progress.nextStep }}</p>
            <p class="progress-meta">{{ progress.categoryName }} · {{ progress.spaceFullPath }}</p>
            <p v-if="progress.workerName" class="progress-meta">维修师傅：{{ progress.workerName }}</p>
            <el-button size="small" @click="goOrderDetail(progress.orderId)">查看工单详情</el-button>
          </div>
          <div v-else class="progress-card">
            <p class="progress-meta">还没有报修记录。</p>
            <el-button size="small" type="primary" @click="goSubmit()">去提交报修</el-button>
          </div>
        </section>

        <!-- ④ 快捷入口：与侧边栏导航同源，按职能取并集 -->
        <section class="block">
          <h3 class="block-title">快捷入口</h3>
          <div class="quick-grid">
            <button
              v-for="item in quickLinks"
              :key="item.path"
              type="button"
              class="quick-item"
              @click="goTo(item.path)"
            >
              <el-icon :size="18"><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
            </button>
            <!-- 「通知中心」是**动作项**、不是路由项：导航清单里没有它，但设计文档 §4 要求
                 快捷入口 = 导航并集 **+** 这一项。走 emit 让外壳打开那个全局唯一的站内消息抽屉 -->
            <button type="button" class="quick-item" @click="openNotificationCenter">
              <el-icon :size="18"><Bell /></el-icon>
              <span>通知中心</span>
            </button>
          </div>
        </section>

        <!-- ⑤ 制度问答：全角色。**标题用口径词**——`CONTEXT.md` 的「制度问答」条目明确把
             「常见问题」列进 _Avoid_（它会被读成"这个系统的 FAQ"，而实际是制度口径问答） -->
        <section class="block">
          <h3 class="block-title">制度问答</h3>
          <el-collapse v-if="faqItems.length">
            <el-collapse-item
              v-for="(item, index) in faqItems"
              :key="index"
              :title="item.question"
              :name="index"
            >
              <p class="faq-answer">{{ item.answer }}</p>
            </el-collapse-item>
          </el-collapse>
          <p v-else class="block-hint">暂时无法加载制度问答。</p>
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Bell, ChatDotRound, Close } from '@element-plus/icons-vue';
import {
  ASSISTANT_DEGRADED_LABELS,
  type AssistantCandidate,
  type AssistantFaqItem,
  type AssistantOrderProgress,
  type AssistantStatus,
  type AssistantSuggestion
} from '@campus/shared';
import { httpClient, useAuthStore } from '../stores/auth';
import { requestAssistantSuggestion } from '../api/assistant';
import { useIdentity } from '../composables/useIdentity';
import { useViewport } from '../composables/useViewport';
import { visibleNavItems } from '../constants/navItems';
import SuggestionCardList from './assistant/SuggestionCardList.vue';

/**
 * 智能助手：右下悬浮气泡 + 抽屉。
 *
 * 三条实现约定：
 * 1. **内容按职能裁剪**——推荐与进度只对报修人渲染，**也只在报修人才发请求**
 *    （接口侧同样是 `hasRole('REPORTER')`，前端不能靠"隐藏"当作权限）；
 * 2. **快捷入口与侧边栏同源**（`visibleNavItems`）**再加一个「通知中心」动作项**——前者防两处
 *    清单漂移，后者是设计文档 §4 明确要有的；它是动作（开外壳的抽屉）不是路由，所以不在那份清单里；
 * 3. **助手永远不阻断业务**——状态拿不到就整块不渲染，推荐失败只在卡片位置给一行提示，
 *    不弹全局错误、不影响提单页。
 */
const emit = defineEmits<{ 'open-notifications': [] }>();

const router = useRouter();
const authStore = useAuthStore();
const identity = useIdentity();
const { isNarrowPhone } = useViewport();

const open = ref(false);
const enabled = ref(false);
const description = ref('');
const suggesting = ref(false);
const suggestError = ref('');
const suggestion = ref<AssistantSuggestion | null>(null);
const progress = ref<AssistantOrderProgress | null>(null);
const faqItems = ref<AssistantFaqItem[]>([]);
const loadedOnce = ref(false);

/**
 * 「最近询问」：提问后输入框清空，问题落在这里，一键重问。
 *
 * 三条约定：
 * 1. **按账号分键**（`…_<userId>`）——同一台机器上换个账号登录不会看到别人的提问；
 * 2. **只在浏览器本地**（localStorage）——提问是自由文本，不上服务端，也就没有"清不掉"的问题；
 * 3. **读写全程 try/catch**——隐私模式/配额满/缓存写坏时，历史退化成本次会话内可用，
 *    但**绝不因为历史记录把助手搞崩**（与"助手整体不可用时提单页照常工作"同一条铁律）。
 *
 * 形态是**换行 chip 云**（不是整行列表）：每条只占一个胶囊，长描述截断、原文进 `title`，
 * 于是 8 条也只占两三行，不会把候选卡片挤下去；单条可删（悬停/聚焦才露出徽标）。
 */
const HISTORY_STORAGE_PREFIX = 'CAMPUS_REPAIR_ASSISTANT_HISTORY';
/** 最多留几条：再多多半是想不起来的旧问题，反而把候选挤下去 */
const HISTORY_LIMIT = 8;

const history = ref<string[]>([]);

const isReporter = computed(() => identity.isReporter.value);
const drawerSize = computed(() => (isNarrowPhone.value ? '100%' : '420px'));
const quickLinks = computed(() =>
  visibleNavItems({ has: identity.has, isAdminSide: identity.isAdminSide.value })
);
const selfHelpTips = computed(() => suggestion.value?.selfHelpTips ?? []);

function historyKey(): string {
  return `${HISTORY_STORAGE_PREFIX}_${authStore.userInfo?.id ?? 'anonymous'}`;
}

function loadHistory() {
  try {
    const raw = localStorage.getItem(historyKey());
    const parsed = raw ? JSON.parse(raw) : [];
    history.value = Array.isArray(parsed) ? parsed.filter((item) => typeof item === 'string') : [];
  } catch (_) {
    history.value = [];
  }
}

function persistHistory() {
  try {
    localStorage.setItem(historyKey(), JSON.stringify(history.value));
  } catch (_) {
    // 存不下就只留内存：本轮仍然可用，只是刷新后丢历史
  }
}

/** 记一条提问：同一条问题不重复堆积，命中已有条目就提到最前 */
function rememberQuestion(text: string) {
  history.value = [text, ...history.value.filter((item) => item !== text)].slice(0, HISTORY_LIMIT);
  persistHistory();
}

function clearHistory() {
  history.value = [];
  try {
    localStorage.removeItem(historyKey());
  } catch (_) {
    // 同上：清不掉也不影响助手
  }
}

/**
 * 删掉单条询问。
 *
 * <p>删空时**连键一起删**，与 {@link clearHistory} 保持同一状态（`localStorage` 里不留空数组），
 * 否则探针/运维读缓存时会看到「键还在但没内容」这种第三种状态。
 */
function removeHistory(text: string) {
  history.value = history.value.filter((item) => item !== text);
  if (history.value.length === 0) {
    clearHistory();
  } else {
    persistHistory();
  }
}

onMounted(async () => {
  loadHistory();
  try {
    const res = await httpClient.get<AssistantStatus>('/assistant/status');
    enabled.value = res.code === 200 && !!res.data?.enabled;
  } catch (_) {
    // 拿不到状态就当助手不存在：绝不因为助手把页面搞崩
    enabled.value = false;
  }
});

/**
 * 首次打开抽屉时取数据（FAQ 与进度都不必在页面加载时就发）。
 *
 * 挂在 `@open` 而**不是** `@opened`：后者要等抽屉的过渡动画跑完才触发，而隐藏标签页里
 * `requestAnimationFrame` 是暂停的、过渡永远不会结束 —— 于是事件永不触发、FAQ 一直空着
 * （本轮真机探针实测：同一份代码时而 8 条时而 0 条）。`@open` 在开始打开时就触发，与过渡无关。
 */
async function onFirstOpen() {
  if (loadedOnce.value) return;
  loadedOnce.value = true;
  await Promise.all([loadFaq(), isReporter.value ? loadProgress() : Promise.resolve()]);
}

async function loadFaq() {
  try {
    const res = await httpClient.get<AssistantFaqItem[]>('/assistant/faq');
    if (res.code === 200) faqItems.value = res.data || [];
  } catch (_) {
    faqItems.value = [];
  }
}

async function loadProgress() {
  try {
    const res = await httpClient.get<AssistantOrderProgress | null>('/assistant/my-progress');
    progress.value = res.code === 200 ? res.data : null;
  } catch (_) {
    progress.value = null;
  }
}

async function onSuggest() {
  // 请求在飞时不再受理（按钮本身 loading 会禁用，但 Ctrl+Enter 与历史条目是另一条路）
  if (suggesting.value) return;
  const text = description.value.trim();
  if (text.length < 2) {
    suggestError.value = '请至少输入 2 个字描述故障现象。';
    suggestion.value = null;
    return;
  }
  // 先记历史再清空输入框。**顺序是有意的**：清空在前会让"失败后想改一个字重问"没处下手，
  // 而失败时历史里已经有原文，一键即可重问；成功时框子本来就该是空的。
  rememberQuestion(text);
  description.value = '';

  suggesting.value = true;
  suggestError.value = '';
  try {
    const outcome = await requestAssistantSuggestion(text);
    if (outcome.ok) {
      suggestion.value = outcome.suggestion;
    } else {
      suggestion.value = null;
      suggestError.value =
        outcome.reason === 'rejected'
          ? outcome.message || '暂时判断不了，请直接选择故障分类。'
          : '助手暂时不可用，不影响您正常提交报修。';
    }
  } finally {
    // 请求出口保证不抛异常，这里仍然收口一次：`suggesting` 卡住会让按钮永久禁用
    suggesting.value = false;
  }
}

/**
 * 点历史条目 = **直接重问**（不是填回输入框再让用户点一次）。
 *
 * 这样"问题不丢"才真的省事：`onSuggest` 会把这条提到历史最前、再清空输入框，
 * 于是点完的状态与手输一次完全一致。规则引擎的排序是确定的（§3.3），
 * 所以同一句话重问拿到的候选顺序与上次一致——这条交互才站得住。
 */
async function reuseHistory(text: string) {
  description.value = text;
  await onSuggest();
}

/** 「采用」= 带着细类跳到提单页预填（绝不自动提交、绝不改用户已填的东西） */
function applyCandidate(candidate: AssistantCandidate) {
  goSubmit(candidate.categoryId);
}

function goSubmit(categoryId?: number) {
  open.value = false;
  const query: Record<string, string> = {};
  if (categoryId) query.categoryId = String(categoryId);
  const defaultSpaceId = authStore.userInfo?.defaultSpaceId;
  if (defaultSpaceId) query.spaceId = String(defaultSpaceId);
  router.push({ path: '/student/submit', query });
}

function goTo(path: string) {
  open.value = false;
  router.push(path);
}

function goOrderDetail(orderId: number) {
  open.value = false;
  router.push(`/student/orders/${orderId}`);
}

/**
 * 打开站内消息抽屉。
 *
 * <p>那个抽屉全站只有一个（在 `AdminLayout` 里），助手不自己再开一个——只把意图转出去。
 * 快捷入口那一项与 {@link notifyCenter} 走的是同一条路，所以两者不会各开一个抽屉。
 */
function openNotificationCenter() {
  emit('open-notifications');
}

defineExpose({ notifyCenter: openNotificationCenter });
</script>

<style scoped>
.assistant-root {
  /* 低于 Element Plus 抽屉/弹层（2000+），避免盖住通知中心等浮层 */
  position: relative;
  z-index: 1500;
}
.assistant-bubble {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 1500;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  border: none;
  border-radius: 999px;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.18);
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.assistant-bubble:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.22);
}
.assistant-bubble:focus-visible {
  outline: 2px solid var(--el-color-primary-light-3);
  outline-offset: 2px;
}

.assistant-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.block-title {
  margin: 0 0 6px;
  font-size: 15px;
}
.block-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.block-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}
.inline-alert {
  margin-top: 10px;
}

/* 「最近询问」：换行 chip 云。次要入口，不该把候选卡片挤出视野。 */
.history {
  margin-top: 12px;
}
.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}
.history-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.history-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  /* 首行 chip 的删除徽标会向上探出 5px，留点余量免得压住标题行 */
  padding-top: 5px;
}
.history-chip-wrap {
  position: relative;
  display: inline-flex;
  max-width: 100%;
}
.history-chip {
  max-width: 100%;
  padding: 6px 12px;
  border: none;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.5;
  text-align: left;
  cursor: pointer;
}
.history-chip:hover {
  color: var(--el-color-primary);
}
.history-chip:focus-visible {
  outline: 2px solid var(--el-color-primary-light-3);
  outline-offset: 1px;
}
.history-chip-text {
  display: block;
  /* 长描述必须截断：不然一个 chip 就把整行占满，"换行 chip" 的形状就没了。
     完整原文在 title 里（同时也是「再次判断」的说明）。 */
  max-width: 11em;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
/*
 * 单条删除徽标：默认藏着，只有指针/键盘落在该条上才露出。
 * **刻意不加 transition**——后台标签里主线程过渡会卡住，探针量不到终态
 * （`AGENTS.md` §2.2 记过这条：宽度/transform 的过渡在隐藏标签页不推进）。
 */
.history-chip-remove {
  position: absolute;
  top: -5px;
  right: -5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: var(--el-text-color-placeholder);
  color: #fff;
  cursor: pointer;
  opacity: 0;
}
.history-chip-wrap:hover .history-chip-remove,
.history-chip-wrap:focus-within .history-chip-remove {
  opacity: 1;
}
.history-chip-remove:focus-visible {
  outline: 2px solid var(--el-color-primary-light-3);
  outline-offset: 1px;
}
.no-match {
  margin-top: 12px;
}
.fallback-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.fallback-tag {
  cursor: pointer;
}

.self-help {
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.self-help-title {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
}
.self-help-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
}

.progress-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
}
.progress-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 0;
}
.progress-no {
  font-weight: 600;
}
.progress-next {
  margin: 8px 0 4px;
  font-size: 13px;
  color: var(--el-color-primary);
}
.progress-meta {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.quick-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
  font-size: 13px;
  color: var(--el-text-color-primary);
  cursor: pointer;
  text-align: left;
}
.quick-item:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}
.quick-item:focus-visible {
  outline: 2px solid var(--el-color-primary-light-3);
  outline-offset: 1px;
}

.faq-answer {
  margin: 0;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
}

/* 窄档：气泡只留图标，避免压住页面内容 */
@media (max-width: 767px) {
  .assistant-bubble {
    right: 16px;
    bottom: 16px;
    padding: 12px;
  }
  .bubble-text {
    display: none;
  }
  .quick-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
