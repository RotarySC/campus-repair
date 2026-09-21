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
          </div>
        </section>

        <!-- ⑤ 制度问答：全角色 -->
        <section class="block">
          <h3 class="block-title">常见问题</h3>
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
          <p v-else class="block-hint">暂时无法加载常见问题。</p>
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ChatDotRound } from '@element-plus/icons-vue';
import {
  ASSISTANT_DEGRADED_LABELS,
  type AssistantCandidate,
  type AssistantFaqItem,
  type AssistantOrderProgress,
  type AssistantStatus,
  type AssistantSuggestion
} from '@campus/shared';
import { httpClient, useAuthStore } from '../stores/auth';
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
 * 2. **快捷入口与侧边栏同源**（`visibleNavItems`），避免两处清单漂移；
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

const isReporter = computed(() => identity.isReporter.value);
const drawerSize = computed(() => (isNarrowPhone.value ? '100%' : '420px'));
const quickLinks = computed(() =>
  visibleNavItems({ has: identity.has, isAdminSide: identity.isAdminSide.value })
);
const selfHelpTips = computed(() => suggestion.value?.selfHelpTips ?? []);

onMounted(async () => {
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
  const text = description.value.trim();
  if (text.length < 2) {
    suggestError.value = '请至少输入 2 个字描述故障现象。';
    suggestion.value = null;
    return;
  }
  suggesting.value = true;
  suggestError.value = '';
  try {
    const res = await httpClient.post<AssistantSuggestion>('/assistant/suggest', { description: text });
    if (res.code === 200 && res.data) {
      suggestion.value = res.data;
    } else {
      suggestion.value = null;
      suggestError.value = res.message || '暂时判断不了，请直接选择故障分类。';
    }
  } catch (_) {
    suggestion.value = null;
    suggestError.value = '助手暂时不可用，不影响您正常提交报修。';
  } finally {
    suggesting.value = false;
  }
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

// 通知中心是外壳里的抽屉（不是路由），走事件而不是跳转
defineExpose({ notifyCenter: () => emit('open-notifications') });
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
