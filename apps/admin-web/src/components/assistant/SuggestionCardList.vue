<template>
  <div class="suggestion-list" role="list">
    <article
      v-for="(candidate, index) in candidates"
      :key="candidate.categoryId"
      class="suggestion-card"
      role="listitem"
    >
      <div class="card-head">
        <span class="category-name">{{ candidate.categoryName }}</span>
        <el-tag size="small" :type="confidenceTagType(candidate.confidence)" effect="light">
          {{ ASSISTANT_CONFIDENCE_LABELS[candidate.confidence] }}
        </el-tag>
        <el-tag v-if="index === 0" size="small" type="primary" effect="plain">首选</el-tag>
      </div>

      <p class="category-path">{{ candidate.categoryPath }}</p>

      <p class="meta-line">
        <span v-if="candidate.skillCode">维修工种：{{ candidate.skillCode }}</span>
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
import {
  ASSISTANT_CONFIDENCE_LABELS,
  type AssistantCandidate,
  type AssistantConfidence
} from '@campus/shared';

/**
 * 助手候选卡片列表：**抽屉与提单页内联推荐共用同一个组件**。
 * 纯展示 + 一个 `apply` 事件，不自己发请求、不自己跳路由——由调用方决定「采用」之后做什么。
 */
defineProps<{ candidates: AssistantCandidate[] }>();

const emit = defineEmits<{ apply: [candidate: AssistantCandidate] }>();

function confidenceTagType(confidence: AssistantConfidence): 'success' | 'warning' | 'info' {
  if (confidence === 'HIGH') return 'success';
  if (confidence === 'MEDIUM') return 'warning';
  return 'info';
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
