<template>
  <div class="responsive-list" :class="{ 'is-card-mode': isCardMode }">
    <!-- 宽档：保持既有 el-table 交互（固定列/行点击等全部不变） -->
    <div v-if="!isCardMode" class="rl-table-wrapper">
      <!--
        无 `border`、无 `stripe`：§3.4 实测腾讯云的行是 `border-bottom: 0px`、表头白底无格线。
        竖线与斑马纹是「表格噪声」，D 把它们去掉，层级改用发丝线（见下方 .rl-table-wrapper 样式）。
      -->
      <el-table
        v-loading="loading"
        :data="items"
        :empty-text="emptyText"
        style="width: 100%;"
        :row-class-name="clickable ? 'rl-clickable-row' : ''"
        @row-click="onRowClick"
      >
        <template #empty>
          <EmptyState :kind="emptyKind" :description="emptyText" />
        </template>
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
          <!--
            ⚠️ `col.valueType` 必须在这里也生效——**这是 2026-10-12 修掉的一个真实缺陷**。
            原先只有 `isSlotted(col)` 这一条判据，于是 `valueType` 只喂给了窄档卡片的 `display()`，
            宽档表格直接渲染字段原值：实测「提单时间」显示成 `2026-10-10T10:54:58.685822`
            （带 T、带微秒，还会撑成两行），「耗材费」显示成 `0` / `53`（没有 ¥）。
            **几何断言全绿**——没有任何一条断言在看「这一格显示的是什么」；
            是看截图时才发现的（见 .scratch/check-valuetype.mjs，现已变成断言）。
            插槽优先：同时给了插槽与 `valueType` 时以插槽为准（各页的自定义格式更具体）。
          -->
          <template v-if="isSlotted(col) || col.valueType" #default="scope">
            <slot v-if="isSlotted(col)" :name="`col-${col.key}`" v-bind="scope" />
            <template v-else>{{ display(scope.row, col) }}</template>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 窄档：改为卡片列表，一屏可读全部字段，不再依赖表格横向滚动 -->
    <div v-else v-loading="loading" class="rl-cards">
      <EmptyState v-if="!items.length && !loading" :kind="emptyKind" :description="emptyText" />
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
            <!-- 卡片形态与表格形态**共用同一份状态色值**（§4.3 的契约变更），
                 `type: 'ring'` 的标签渲染成 9px 圆环，其余仍是语义小胶囊（非状态类标签仍走 el-tag 风格） -->
            <template v-for="tag in cardTags(item)" :key="tag.key">
              <StatusRing
                v-if="tag.type === 'ring'"
                class="rl-card__ring"
                :status="tag.status"
                :label="tag.text"
                :variant="tag.variant"
                :tone="tag.tone"
              />
              <span v-else class="rl-card__tag" :class="`is-${tag.type || 'info'}`">{{ tag.text }}</span>
            </template>
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
import { type EmptyArtKind } from './emptyArt';
import EmptyState from './EmptyState.vue';
import { ArrowRight } from '@element-plus/icons-vue';
import { formatDate, formatDateTime } from '@campus/shared';
import { useViewport } from '../composables/useViewport';
import StatusRing from './StatusRing.vue';

/**
 * 卡片标题行里的小标签配色。
 * `'ring'` 是视觉语言方向 D 新增的一档（§4.3 契约变更）：渲染成 9px 状态圆环而不是彩色胶囊，
 * 色相由 `CardTag.status` 推导——**卡片形态与表格形态共用同一份色值**。
 */
export type CardTagType = 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'ring';

export interface CardTag {
  text: string;
  type?: CardTagType;
  /** `type: 'ring'` 时为必填：工单状态码，圆环色相由它推导（§4.3）。其他档位忽略。 */
  status?: string;
  /**
   * §3.3 的标记语义，**只对 `type: 'ring'` 有效**：`'dot'` 实心＝标记，缺省 `'ring'` 空心＝状态。
   * 给「加急」这类**不是工单状态、但需要注意**的标记用 —— 它没有状态码。
   */
  variant?: 'ring' | 'dot';
  /**
   * §3.3 的**两档**，同样**只对 `type: 'ring'` 且 `variant: 'dot'` 有效**：
   * `'alert'`（缺省）＝警戒·红·呼吸；`'muted'` ＝提示·中性·静止。
   *
   * ⚠️ 加这一项的原因：C 组落地时「需改密」的**卡片形态**需要与**表格形态**用同一个提示点。
   * 没有它的话，窄档就只能退回 `is-warning` 那种彩色胶囊 —— 于是同一个值在两种形态下两种长相，
   * 那正是这批要消灭的东西。
   */
  tone?: 'alert' | 'muted';
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
  /**
   * 取值格式（`datetime` / `money` / `date` / `boolean`）。
   * **宽档表格与窄档卡片都走它**——两档共用一个格式化出口，否则会出现
   * 「卡片上是 `2026-10-10 10:54`、表格里是 `2026-10-10T10:54:58.685822`」这种同一份数据的两种长相
   * （2026-10-12 实测过，见模板里那段注释与 `.scratch/check-valuetype.mjs`）。
   * 缺省按原样输出，空值渲染为 `—`。
   */
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
    /** 空态插画种类（G2）。默认「无数据」 */
    emptyKind?: EmptyArtKind;
    /** 整卡可点击（师生「我的工单」用它进详情） */
    clickable?: boolean;
    /** 列表项主键字段；缺省回退到下标 */
    itemKey?: string;
    /** 榜单类列表：卡片标题前显示序号徽标（第 1 名加重、其余中性） */
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
    emptyKind: 'no-data',
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

/** 标题行的标签集合。每个标签算一次 `cardTag()`——此前模板里调了两遍（文案一遍、配色一遍）。 */
function cardTags(item: T): Array<CardTag & { key: string }> {
  return tagColumns.map((col) => ({
    ...(col.cardTag ? col.cardTag(item) : { text: display(item, col) }),
    key: col.key || col.label
  }));
}

function onRowClick(row: T) {
  if (props.clickable) emit('row-click', row);
}
</script>

<style scoped>
/* ---------- 宽档：§3.4 实测口径 ----------
   表头**白底**由 tokens.css 的 `--el-table-header-bg-color` 全局提供（§4.2 指定在 :root 覆盖）；
   此处补的是「表头/行的内边距、发丝线、行不打分隔线」这三条**结构**口径（§4.4：落在本组件）。 */
.rl-table-wrapper {
  width: 100%;
  overflow-x: auto;
}

.rl-table-wrapper :deep(.el-table) {
  font-size: var(--fs-base);
}

.rl-table-wrapper :deep(.el-table th.el-table__cell) {
  padding: 13px 10px;
  border-radius: 0;
  /* 0.667px = 1.5× DPR 下的一个物理像素发丝线（§3.4 刻意值，不是笔误） */
  border-bottom: var(--hairline) solid var(--line);
  /* §3.5：层级靠字号 + 颜色，不靠加粗 */
  font-weight: var(--fw-normal);
  color: var(--muted);
}

/*
  §3.4 的 `13px 10px` / `14px 10px` 是**整个单元格**的度量。EP 把水平内边距放在内层
  `.cell` 上（默认 12px），若不归零就会与外层叠加成 22px——那既不是实测值，
  也会把「状态列只有 100px 宽」这种列挤到换行。归零后总水平内边距正好是 10px。
*/
.rl-table-wrapper :deep(.el-table .cell) {
  padding-left: 0;
  padding-right: 0;
}

.rl-table-wrapper :deep(.el-table td.el-table__cell) {
  padding: 14px 10px;
  /* 实测腾讯云 `border-bottom: 0px` —— 行不打分隔线 */
  border-bottom: 0;
}

/* EP 在 inner-wrapper 上画了一条 1px 外框底线，一并收成发丝线同色 */
.rl-table-wrapper :deep(.el-table__inner-wrapper::before) {
  height: var(--hairline);
  background-color: var(--line);
}

/* ---------- 窄档卡片 ---------- */
.rl-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 80px;
}

.rl-card {
  /* V15：圆角 0–2px、**禁止自造阴影**（原先 8px 圆角 + 0 1px 2px 投影） */
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  background: var(--surface);
  padding: 12px 14px;
}

.rl-card--clickable {
  cursor: pointer;
}

.rl-card--clickable:active {
  background: var(--canvas);
}

.rl-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding-bottom: 10px;
  border-bottom: 1px dashed var(--line);
}

.rl-card__title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

/*
  序号徽标：原先第 1/2/3 名各配一种彩色（#F5222D / #FA8C16 / #FADB14）。
  §5 的 V4 修订把口径收成「**彩色只服务状态语义**」，名次不是状态 ⇒ 退到中性阶梯。
  名次层级改用中性色的深浅（第 1 名 --ink，其余 --muted），不再新增色相。
*/
.rl-card__rank {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--muted);
  color: var(--surface);
  font-size: var(--fs-base);
  /* §3.5：600 是字重上限 */
  font-weight: var(--fw-medium);
  line-height: 20px;
  text-align: center;
}

.rl-card__rank.is-1 {
  background: var(--ink);
}

.rl-card__title-text {
  font-weight: var(--fw-medium);
  font-size: 14px;
  color: var(--ink);
  word-break: break-all;
}

.rl-card__ring {
  flex-shrink: 0;
}

/*
  非状态类标签（条线、库存等）仍是小胶囊；配色改为**引用 EP 自己的语义色变量**，
  不再抄一份近似 hex——这样它们会跟着 EP 主题一起变，且本文件不出现任何色值字面量。

  ⚠️ **`is-info` 的口径是「中性」**，2026-10-13 起它被要求与 `MetaChip` **逐值一致**
  （透明底 + 发丝线 + `--faint` 字）。改之前它是 `--muted` 字 + `--canvas` 底 + `1px` 边 ——
  于是「同一个中性」在窄档卡片和宽档表格（用 `MetaChip`）是**两种长相**，
  而这正是这批在消灭的那类漂移。C 组落地后它是**卡片形态唯一的中性档**，所以必须与 MetaChip 同源。
*/
.rl-card__tag {
  flex-shrink: 0;
  font-size: var(--fs-base);
  line-height: 18px;
  padding: 0 6px;
  border-radius: var(--radius);
  border: 1px solid transparent;
}

.rl-card__tag.is-primary {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-8);
}
.rl-card__tag.is-success {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
  border-color: var(--el-color-success-light-8);
}
.rl-card__tag.is-warning {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-color: var(--el-color-warning-light-8);
}
.rl-card__tag.is-danger {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border-color: var(--el-color-danger-light-8);
}
.rl-card__tag.is-info {
  /* 与 `components/MetaChip.vue` 逐值一致 —— 全仓「分类/属性的中性表达」只有一种长相 */
  color: var(--faint);
  background: transparent;
  border-color: var(--line);
  border-width: var(--hairline);
}

.rl-card__arrow {
  flex-shrink: 0;
  color: var(--faint);
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
  color: var(--muted);
}

.rl-card__row dd {
  flex: 1;
  min-width: 0;
  margin: 0;
  color: var(--ink);
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
  border-top: 1px dashed var(--line);
}
</style>
