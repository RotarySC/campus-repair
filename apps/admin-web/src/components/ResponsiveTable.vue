<template>
  <div class="responsive-list" :class="{ 'is-card-mode': isCardMode }">
    <!-- 宽档：保持既有 el-table 交互（固定列/行点击等全部不变） -->
    <div v-if="!isCardMode" class="rl-table-wrapper">
      <el-table
        v-loading="loading"
        :data="items"
        :empty-text="emptyText"
        border
        stripe
        style="width: 100%;"
        :row-class-name="clickable ? 'rl-clickable-row' : ''"
        @row-click="onRowClick"
      >
        <el-table-column
          v-for="col in tableColumns"
          :key="col.key || col.label"
          :prop="col.key"
          :label="col.label"
          :width="col.width"
          :min-width="col.minWidth"
          :align="col.align"
          :fixed="col.fixed"
          :show-overflow-tooltip="col.showOverflowTooltip"
        >
          <template v-if="isSlotted(col)" #default="scope">
            <slot :name="`col-${col.key}`" v-bind="scope" />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 窄档：改为卡片列表，一屏可读全部字段，不再依赖表格横向滚动 -->
    <div v-else v-loading="loading" class="rl-cards">
      <el-empty v-if="!items.length && !loading" :description="emptyText" />
      <article
        v-for="(item, index) in items"
        :key="itemKey(item, index)"
        class="rl-card"
        :class="{ 'rl-card--clickable': clickable }"
        @click="onRowClick(item)"
      >
        <header class="rl-card__head">
          <div class="rl-card__title">
            <span v-if="ranked" class="rl-card__rank" :class="`is-${index + 1}`">{{ index + 1 }}</span>
            <span class="rl-card__title-text">
              <template v-if="titleColumn && isSlotted(titleColumn)">
                <slot :name="`col-${titleColumn.key}`" :row="item" :$index="index" />
              </template>
              <template v-else>{{ titleColumn ? display(item, titleColumn) : '—' }}</template>
            </span>
            <span
              v-for="tagCol in tagColumns"
              :key="tagCol.key || tagCol.label"
              class="rl-card__tag"
              :class="`is-${cardTag(item, tagCol).type || 'info'}`"
            >
              {{ cardTag(item, tagCol).text }}
            </span>
          </div>
          <el-icon v-if="clickable" class="rl-card__arrow"><ArrowRight /></el-icon>
        </header>

        <dl v-if="visibleBodyColumns(item).length" class="rl-card__rows">
          <div v-for="col in visibleBodyColumns(item)" :key="col.key || col.label" class="rl-card__row">
            <dt>{{ col.label }}</dt>
            <dd>
              <template v-if="isSlotted(col)">
                <slot :name="`col-${col.key}`" :row="item" :$index="index" />
              </template>
              <template v-else>{{ display(item, col) }}</template>
            </dd>
          </div>
        </dl>

        <footer v-if="actionColumns.length || $slots.cardFooter" class="rl-card__foot">
          <slot name="cardFooter" :row="item" :$index="index" />
          <template v-for="col in actionColumns" :key="col.key || col.label">
            <slot :name="`col-${col.key}`" :row="item" :$index="index" />
          </template>
        </footer>
      </article>
    </div>
  </div>
</template>

<script setup lang="ts" generic="T extends Record<string, any>">
import { computed, useSlots } from 'vue';
import { ArrowRight } from '@element-plus/icons-vue';
import { formatDate, formatDateTime } from '@campus/shared';
import { useViewport } from '../composables/useViewport';

/** 卡片标题行里的小标签配色（与 el-tag 的语义色一致） */
export type CardTagType = 'primary' | 'success' | 'warning' | 'danger' | 'info';

export interface CardTag {
  text: string;
  type?: CardTagType;
}

/** 正文字段在卡片上的呈现方式；自定义渲染请改用同名具名插槽 `col-<key>` */
export type CardValueType = 'text' | 'money' | 'datetime' | 'date' | 'boolean';

export interface TableColumn<T = any> {
  /** 取数字段名；操作列等无对应字段时可省 */
  key?: string;
  label: string;
  width?: string | number;
  minWidth?: string | number;
  align?: 'left' | 'center' | 'right';
  fixed?: boolean | 'left' | 'right';
  showOverflowTooltip?: boolean;
  /** 卡片正文的取值格式（缺省按原样输出，空值渲染为 `—`） */
  valueType?: CardValueType;
  /**
   * 窄档卡片布局角色：
   * - 'title'：卡片主标题（每表一个，建议用单号/名称）；
   * - 'tag'：标题行右侧的语义小标签（状态、紧急度等，可多个）；
   * - 'action'：卡片底部操作区（按钮/链接，等价于表格的固定操作列）；
   * - 缺省：卡片正文的「标签 + 值」行。
   */
  card?: 'title' | 'tag' | 'action';
  /** card='tag' 时按数据取值给出标签文案与配色 */
  cardTag?: (row: T) => CardTag;
  /** 值为空时不渲染该正文行（如尚未派单的技师列） */
  cardHideEmpty?: boolean;
  /**
   * **只在窄档卡片正文出现，宽档表格不占列**。
   * 给「窄档需要看、宽档放不下」的长文本字段用（如故障描述、预约时段）——
   * 卡片模式本来就是"把一条记录读全"，而宽档表格要靠列数控制宽度。
   */
  cardOnly?: boolean;
}

const props = withDefaults(
  defineProps<{
    items: T[];
    columns: TableColumn<T>[];
    loading?: boolean;
    emptyText?: string;
    /** 整卡可点击（师生「我的工单」用它进详情） */
    clickable?: boolean;
    /** 列表项主键字段；缺省回退到下标 */
    itemKey?: string;
    /** 榜单类列表：卡片标题前显示序号徽标（第 1~3 名高亮） */
    ranked?: boolean;
    /**
     * 切换为卡片布局的**视口宽度阈值（px）**：`viewportWidth < cardBelow` 即用卡片。
     * 默认 **768**——仓库既有 6 处调用方的行为**完全不变**。
     *
     * 为什么需要它：现场作业页（师傅工作台）的主要设备是平板，768~1023 档下内容区只有
     * ~710~960px，多列表格必然横向滚动、操作按钮被推出视口；那一档就该用卡片。
     * 桌面管理页保持默认 768 即可（平板档也看表格）。
     */
    cardBelow?: number;
  }>(),
  {
    loading: false,
    emptyText: '暂无数据',
    clickable: false,
    itemKey: 'id',
    ranked: false,
    cardBelow: 768
  }
);

const emit = defineEmits<{ (e: 'row-click', row: T): void }>();
const { viewportWidth } = useViewport();
const slots = useSlots();

/** 卡片模式判据（唯一的）：用 `cardBelow` 而不是写死 `isNarrowPhone` */
const isCardMode = computed(() => viewportWidth.value < props.cardBelow);

const isSlotted = (col?: TableColumn<T>) => !!col?.key && !!slots[`col-${col.key}`];

/** 宽档表格的列 = 全部列 − `cardOnly` 列；窄档卡片正文仍用 `bodyColumns`（含 `cardOnly`） */
const tableColumns = computed(() => props.columns.filter((c) => !c.cardOnly));

const titleColumn: TableColumn<T> | undefined =
  props.columns.find((c) => c.card === 'title') ?? props.columns[0];
const tagColumns = props.columns.filter((c) => c.card === 'tag');
const actionColumns = props.columns.filter((c) => c.card === 'action');
const bodyColumns = props.columns.filter((c) => !c.card);

function itemKey(item: T, index: number): string | number {
  const value = props.itemKey ? item[props.itemKey] : undefined;
  return value ?? index;
}

/** 取值 → 格式化为卡片文本；null/undefined/空串统一渲染为 `—` */
function display(item: T, col: TableColumn<T>): string {
  const raw = col.key ? item[col.key] : undefined;
  if (raw === null || raw === undefined || raw === '') return '—';
  switch (col.valueType) {
    case 'money':
      return `¥${Number(raw || 0).toFixed(2)}`;
    // 时间走 shared 的唯一实现（切片、无时区重解释；理由见 utils/format.ts 的文件头注释）
    case 'datetime':
      return formatDateTime(String(raw));
    case 'date':
      return formatDate(String(raw));
    case 'boolean':
      return raw ? '是' : '否';
    default:
      return String(raw);
  }
}

function isEmpty(item: T, col: TableColumn<T>): boolean {
  if (isSlotted(col)) return false;
  const raw = col.key ? item[col.key] : undefined;
  return raw === null || raw === undefined || raw === '';
}

const visibleBodyColumns = (item: T) =>
  bodyColumns.filter((col) => !(col.cardHideEmpty && isEmpty(item, col)));

function cardTag(item: T, col: TableColumn<T>): CardTag {
  return col.cardTag ? col.cardTag(item) : { text: display(item, col) };
}

function onRowClick(row: T) {
  if (props.clickable) emit('row-click', row);
}
</script>

<style scoped>
.rl-table-wrapper {
  width: 100%;
  overflow-x: auto;
}

.rl-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 80px;
}

.rl-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #fff;
  padding: 12px 14px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.rl-card--clickable {
  cursor: pointer;
}

.rl-card--clickable:active {
  background: #f5f7fa;
}

.rl-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding-bottom: 10px;
  border-bottom: 1px dashed #ebeef5;
}

.rl-card__title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.rl-card__rank {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #e5e7eb;
  color: #4b5563;
  font-size: 12px;
  font-weight: 700;
  line-height: 20px;
  text-align: center;
}

.rl-card__rank.is-1 {
  background: #f5222d;
  color: #fff;
}
.rl-card__rank.is-2 {
  background: #fa8c16;
  color: #fff;
}
.rl-card__rank.is-3 {
  background: #fadb14;
  color: #7c5c00;
}

.rl-card__title-text {
  font-weight: 600;
  font-size: 14px;
  color: #1f2937;
  word-break: break-all;
}

.rl-card__tag {
  flex-shrink: 0;
  font-size: 12px;
  line-height: 18px;
  padding: 0 6px;
  border-radius: 4px;
  border: 1px solid transparent;
}

.rl-card__tag.is-primary {
  color: #1677ff;
  background: #e8f3ff;
  border-color: #b9dbff;
}
.rl-card__tag.is-success {
  color: #15803d;
  background: #eafaf0;
  border-color: #b7ebc6;
}
.rl-card__tag.is-warning {
  color: #b45309;
  background: #fff7e6;
  border-color: #ffd591;
}
.rl-card__tag.is-danger {
  color: #b91c1c;
  background: #fff1f0;
  border-color: #ffa39e;
}
.rl-card__tag.is-info {
  color: #6b7280;
  background: #f3f4f6;
  border-color: #e5e7eb;
}

.rl-card__arrow {
  flex-shrink: 0;
  color: #c0c4cc;
  margin-top: 2px;
}

.rl-card__rows {
  margin: 10px 0 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.rl-card__row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 13px;
  line-height: 1.5;
}

.rl-card__row dt {
  flex: 0 0 84px;
  color: #909399;
}

.rl-card__row dd {
  flex: 1;
  min-width: 0;
  margin: 0;
  color: #303133;
  word-break: break-all;
}

.rl-card__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #ebeef5;
}
</style>
