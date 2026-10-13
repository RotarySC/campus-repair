<template>
  <div class="suggestion-list" role="list">
    <article
      v-for="(candidate, index) in candidates"
      :key="candidate.categoryId"
      class="suggestion-card"
      role="listitem"
    >
      <div class="card-head">
        <!-- A3：分类图标。候选是**细类**，所以按名取；取不到回落到 candidate.skillCode。 -->
        <CategoryIcon :name="candidate.categoryName" :skill="candidate.skillCode" />
        <span class="category-name">{{ candidate.categoryName }}</span>
        <!--
          ⚠️ 2026-10-13（C 组 #6）：`el-tag`（success / warning / info 三档）→ `MetaChip`，
          **三档全中性**（用户裁定）。
          置信度是**建议的一条属性**（这条建议有多可信），既不是工单状态、也不要求读者做任何事 ——
          而「高」是"好"不是"正常"，给它配色会让这一档顺带借走 `--h7 已结单` 的绿。
          色相退场之后，**三档的区分完全由中文标签承担**（`ASSISTANT_CONFIDENCE_LABELS`）。
        -->
        <MetaChip>{{ ASSISTANT_CONFIDENCE_LABELS[candidate.confidence] }}</MetaChip>
        <MetaChip v-if="index === 0">首选</MetaChip>
      </div>

      <p class="category-path">{{ candidate.categoryPath }}</p>

      <p class="meta-line">
        <span v-if="candidate.skillCode">维修工种：{{ skillLabel(candidate.skillCode) }}</span>
        <span v-if="candidate.slaHours">承诺时效：{{ candidate.slaHours }} 小时</span>
      </p>

      <p v-if="candidate.matchedKeywords.length" class="matched-line">
        命中：{{ candidate.matchedKeywords.join('、') }}
      </p>

      <div class="card-actions">
        <el-button type="primary" size="small" @click="emit('apply', candidate)">采用并提单</el-button>
      </div>
    </article>
  </div>
</template>

<script setup lang="ts">
import MetaChip from '../MetaChip.vue';
import {
  ASSISTANT_CONFIDENCE_LABELS,
  WorkerSkillLabels,
  type AssistantCandidate
} from '@campus/shared';
import CategoryIcon from '../CategoryIcon.vue';

/**
 * 助手候选卡片列表：**抽屉与提单页内联推荐共用同一个组件**。
 * 纯展示 + 一个 `apply` 事件，不自己发请求、不自己跳路由——由调用方决定「采用」之后做什么。
 */
defineProps<{ candidates: AssistantCandidate[] }>();

const emit = defineEmits<{ apply: [candidate: AssistantCandidate] }>();

/*
 * ⚠️ 2026-10-13（C 组 #6）：原先这里有一个 `confidenceTagType(confidence)`，
 * 把三档置信度映射成 `el-tag` 的 success/warning/info。置信度改成三档全中性（`MetaChip`）之后
 * 它就没有调用点了，**一并删掉** —— 留着一个"把置信度投影成语义色"的函数，
 * 下一个人会以为那是一种被承认的用法。
 */

/**
 * 工种码 → 中文标签。
 *
 * <p>卡片是**给人看的**，不能把 `PLUMBER` 这种字典码直接端出去——全站其它地方
 * （工作台、调度中心、空间字典）都走 `WorkerSkillLabels`，这里也不能例外。
 * 查不到时回落原码而不是留空：宁可露出一个陌生的码，也不要让「维修工种：」后面什么都没有。
 */
function skillLabel(code: string): string {
  return WorkerSkillLabels[code] ?? code;
}
</script>

<style scoped>
.suggestion-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.suggestion-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
  background: var(--el-fill-color-blank);
}
.card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.category-name {
  font-weight: 600;
  font-size: 15px;
}
.category-path,
.meta-line,
.matched-line {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  word-break: break-all;
}
.matched-line {
  color: var(--el-color-primary);
}
.card-actions {
  margin-top: 10px;
}
</style>
