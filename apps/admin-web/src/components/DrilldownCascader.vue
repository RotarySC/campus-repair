<template>
  <div class="dd-cascader">
    <el-popover
      v-model:visible="open"
      trigger="click"
      placement="bottom-start"
      :width="panelWidth"
      :offset="6"
      :show-arrow="false"
      :disabled="disabled"
      :popper-style="{ padding: '0' }"
      :trigger-keys="[]"
    >
      <template #reference>
        <div class="dd-anchor">
          <!--
            触发器刻意用 div[role=combobox] 而不是 <button>：button 的原生 Enter 激活语义对本控件是多余的
            （Enter 的语义是"展开/下钻/提交"，不是"激活按钮"）。
            配套还必须给 el-popover 传 :trigger-keys="[]" —— EP 的 tooltip trigger 自带 onKeydown，
            默认 triggerKeys=['Enter','NumpadEnter','Space'] 会调用 onToggle 把面板切掉，
            表现为"Enter 下钻后约 400ms 面板自己关闭"（EP hideAfter=200ms，实测调用栈
            onClose <- onToggle <- composeEventHandlers.handleEvent）。
          -->
          <div
            ref="triggerRef"
            class="dd-trigger"
            :class="[sizeClass, { 'is-open': open, 'is-disabled': disabled }]"
            role="combobox"
            :tabindex="disabled ? -1 : 0"
            aria-haspopup="listbox"
            :aria-expanded="open"
            :aria-controls="open ? panelId : undefined"
            :aria-activedescendant="open ? activeOptionId : undefined"
            :aria-label="label || undefined"
            :aria-disabled="disabled || undefined"
            @keydown="onTriggerKeydown"
          >
            <span class="dd-trigger__text" :class="{ 'is-placeholder': !hasValue }">
              {{ hasValue ? displayText : placeholder }}
            </span>
          </div>

          <button
            v-if="clearable && hasValue && !disabled"
            type="button"
            class="dd-clear"
            aria-label="清除已选项"
            @click.stop="clearValue"
          >
            <el-icon><CircleClose /></el-icon>
          </button>

          <el-icon class="dd-trigger__arrow" aria-hidden="true"><ArrowDown /></el-icon>
        </div>
      </template>

      <!-- 面板永远只有一列：点带子级的项只下钻，点叶子才提交；面包屑可回退任意层级 -->
      <div class="dd-panel">
        <div class="dd-panel__head">
          <button v-if="crumbs.length" type="button" class="dd-back" aria-label="返回上一级" @click="goUp">
            <el-icon aria-hidden="true"><ArrowLeft /></el-icon>
          </button>

          <nav class="dd-crumbs" aria-label="层级路径">
            <button
              type="button"
              class="dd-crumb"
              :class="{ 'is-current': !crumbs.length }"
              :aria-current="crumbs.length ? undefined : 'true'"
              @click="jumpTo(-1)"
            >
              {{ rootLabel }}
            </button>
            <template v-for="(node, index) in crumbs" :key="String(node[valueKey])">
              <span class="dd-crumb__sep" aria-hidden="true">/</span>
              <button
                type="button"
                class="dd-crumb"
                :class="{ 'is-current': index === crumbs.length - 1 }"
                :aria-current="index === crumbs.length - 1 ? 'true' : undefined"
                @click="jumpTo(index)"
              >
                {{ node[labelKey] }}
              </button>
            </template>
          </nav>
        </div>

        <ul :id="panelId" ref="listRef" class="dd-panel__list" role="listbox" :aria-label="label || placeholder">
          <li v-for="(node, index) in currentLevel" :key="String(node[valueKey])">
            <button
              type="button"
              role="option"
              class="dd-option"
              :class="{ 'is-active': index === activeIndex }"
              :id="optionId(node)"
              :data-value="String(node[valueKey])"
              tabindex="-1"
              :aria-selected="node[valueKey] === selectedId"
              @click="pick(node)"
            >
              <LevelIcon v-if="levelIconKind" :kind="levelIconKind" :level="node.levelType" class="dd-option__icon" />
              <!-- A3：故障分类图标。与 `levelIconKind` 是**两件事**——分类的层级是
                   「大类/二级细类」，不是空间/组织那种层级，所以两者互斥、各管各的。
                   取键：分类名（`labelKey`，默认 name）优先，工种码兜底。 -->
              <CategoryIcon
                v-else-if="categoryIcon"
                :name="node[labelKey]"
                :skill="node.workerSkillCode"
                class="dd-option__icon"
              />
              <span class="dd-option__label">{{ node[labelKey] }}</span>
              <el-icon v-if="hasChildren(node)" class="dd-option__more" aria-hidden="true"><ArrowRight /></el-icon>
              <el-icon v-else-if="node[valueKey] === selectedId" class="dd-option__check" aria-hidden="true">
                <Check />
              </el-icon>
            </button>
          </li>

          <li v-if="!currentLevel.length" class="dd-empty">{{ emptyText }}</li>
        </ul>
      </div>
    </el-popover>
  </div>
</template>

<script lang="ts">
// 模块级自增序号：给每个实例一份稳定且不冲突的 DOM id（aria-activedescendant 需要）
let uidSeq = 0;
</script>

<script setup lang="ts" generic="Id extends string | number = number">
/**
 * 单列逐级下钻选择器（替代 el-cascader 的多列并排展开）。
 *
 * 为什么不用 el-cascader：它把每一级渲染成一列并排铺开，窗口一窄就被右边界裁掉；
 * 且 `checkStrictly` 会给每个节点加单选圈，而本项目的订单只可能挂在叶子节点上
 * （后端按 `spaceId`/`categoryId` 精确匹配，提单页也只允许选叶子），中间层可选
 * 反而是「点了校区查出 0 条」的陷阱。这里统一为：选中带子级的项只下钻、点叶子才提交。
 *
 * v-model 与 el-cascader 的 `emitPath` 形态一致（id 路径数组），三处调用方的
 * `findIdPath` 回显、重置与「取末位 id」逻辑无需改动。
 */
import { computed, nextTick, ref, watch } from 'vue';
import { ArrowDown, ArrowLeft, ArrowRight, Check, CircleClose } from '@element-plus/icons-vue';
import { useFormItem, useFormSize } from 'element-plus';
import LevelIcon from './LevelIcon.vue';
import CategoryIcon from './CategoryIcon.vue';

interface DrillNode {
  [key: string]: any;
}

const props = withDefaults(
  defineProps<{
    /** 选中节点的 id 路径（叶子路径），空数组表示未选 */
    modelValue: Id[];
    /** 树形选项，默认按 id / name / children 取值 */
    options: DrillNode[];
    valueKey?: string;
    labelKey?: string;
    childrenKey?: string;
    placeholder?: string;
    /** 面包屑最前面的根级标签 */
    rootLabel?: string;
    clearable?: boolean;
    disabled?: boolean;
    /** 无障碍名称（如「空间位置」），同时作为 listbox 的可访问名 */
    label?: string;
    emptyText?: string;
    /** 面板宽度：默认窄屏下不越界 */
    panelWidth?: string;
    /** 触发器里各级名称的连接符；默认 ` / `（空间/分类用），单位字典传空格以与库中 department 文案一致 */
    separator?: string;
    /**
     * 选项行前显示**层级图标**（规范 §4.6 的 A4）。
     *
     * ⚠️ **默认关闭**，因为它是**树相关的**，而本组件是通用的：5 个既有调用方里，
     * 空间选择器要 `'space'`、单位字典要 `'org'`、故障分类**两个都不是**
     * （分类的层级是 大类/二级细类，属 A3 的分类图标，不是层级图标）。
     * 不传就一行不渲染，既有调用方行为**逐字节不变**。
     *
     * 层级取自**节点自己的 `levelType`**，而不是"当前下钻到第几层"：
     * 单位字典的根「所属单位」与「2024级」都是 `level_type = 1`
     * （见 `CONTEXT.md`「所属单位」条：两者必须能分开念），按下钻深度推会算错一级。
     */
    levelIconKind?: 'org' | 'space';
    /**
     * 选项行前显示**故障分类图标**（规范 §4.6 的 A3）。
     *
     * 与 `levelIconKind` **互斥**（模板里是 `v-if` / `v-else-if`，层级图标优先）：
     * 空间树与组织树的「层级」是 `level_type`，故障分类的「层级」是大类/二级细类，
     * 两套词表、两种图标，同一个面板不可能同时是两者。
     *
     * 取键规则在 `CategoryIcon` 里：**分类名优先、工种码兜底**，
     * 所以用户新建的细类（名字不在 12 个图标表里）仍然靠 `worker_skill_code` 拿到图标。
     *
     * **默认 `false`**，既有调用方行为逐字节不变。
     */
    categoryIcon?: boolean;
  }>(),
  {
    valueKey: 'id',
    labelKey: 'name',
    childrenKey: 'children',
    placeholder: '请选择',
    rootLabel: '全部',
    clearable: true,
    disabled: false,
    label: '',
    emptyText: '暂无可选项',
    panelWidth: 'min(360px, 92vw)',
    separator: ' / ',
    levelIconKind: undefined,
    categoryIcon: false
  }
);

const emit = defineEmits<{
  (e: 'update:modelValue', value: Id[]): void;
  (e: 'change', value: Id[]): void;
}>();

const uid = `dd-cascader-${++uidSeq}`;
const panelId = `${uid}-panel`;
const optionId = (node: DrillNode) => `${uid}-opt-${String(node[props.valueKey])}`;

/** 与相邻的 el-input / el-select 保持同一档尺寸（报表页的表单是 small） */
const size = useFormSize();
const sizeClass = computed(() => {
  const value = size.value;
  return value === 'small' || value === 'large' ? `is-${value}` : '';
});

/** 让外层 el-form-item 的 `trigger: 'change'` 规则能即时校验/清除错误 */
const { formItem } = useFormItem();

const open = ref(false);
const crumbs = ref<DrillNode[]>([]);
const activeIndex = ref(0);
const pendingFocus = ref<'selected' | 'first' | 'last'>('selected');
const triggerRef = ref<HTMLDivElement | null>(null);
const listRef = ref<HTMLUListElement | null>(null);

const childrenOf = (node?: DrillNode | null): DrillNode[] => {
  const list = node ? node[props.childrenKey] : props.options;
  return Array.isArray(list) ? list : [];
};
const hasChildren = (node: DrillNode) => childrenOf(node).length > 0;

/** 把 id 路径还原成节点路径（选项异步加载完成前的未解析项会被跳过） */
const selectedNodes = computed<DrillNode[]>(() => {
  const path: DrillNode[] = [];
  let level: DrillNode[] = props.options || [];
  for (const id of props.modelValue || []) {
    const hit = level.find((node) => node[props.valueKey] === id);
    if (!hit) break;
    path.push(hit);
    level = childrenOf(hit);
  }
  return path;
});

const selectedId = computed<Id | undefined>(
  () => (props.modelValue || [])[(props.modelValue || []).length - 1]
);
const hasValue = computed(() => (props.modelValue || []).length > 0);
const displayText = computed(() => {
  const text = selectedNodes.value
    .map((node) => String(node[props.labelKey] ?? ''))
    .filter(Boolean)
    .join(props.separator);
  // 选项还没加载完却已有选中值：先显示原始 id，避免"输入框看着是空的但表单有值"
  return text || String(selectedId.value ?? '');
});

const currentLevel = computed<DrillNode[]>(() => childrenOf(crumbs.value[crumbs.value.length - 1]));
const activeOptionId = computed(() => {
  const node = currentLevel.value[activeIndex.value];
  return node ? optionId(node) : undefined;
});

watch(open, (visible) => {
  if (visible) initLevel();
});

/** 打开时定位到当前选中项所在层级（相当于"已选省 → 显示市列表"） */
function initLevel() {
  const path = selectedNodes.value;
  const last = path[path.length - 1];
  crumbs.value = last && hasChildren(last) ? [...path] : path.slice(0, -1);

  const focus = pendingFocus.value;
  pendingFocus.value = 'selected';

  const list = currentLevel.value;
  let index = list.findIndex((node) => node[props.valueKey] === last?.[props.valueKey]);
  if (index < 0 || focus === 'first') index = 0;
  else if (focus === 'last') index = list.length - 1;
  setActive(index);
}

function setActive(index: number) {
  const length = currentLevel.value.length;
  activeIndex.value = length ? Math.max(0, Math.min(index, length - 1)) : 0;
  nextTick(() => {
    const node = currentLevel.value[activeIndex.value];
    if (!node) return;
    listRef.value
      ?.querySelector<HTMLElement>(`[data-value="${String(node[props.valueKey])}"]`)
      ?.scrollIntoView({ block: 'nearest' });
  });
}

function indexOfNode(node?: DrillNode) {
  if (!node) return 0;
  const index = currentLevel.value.findIndex((item) => item[props.valueKey] === node[props.valueKey]);
  return index < 0 ? 0 : index;
}

/** 面包屑跳级：index 为 -1 表示回到根层 */
function jumpTo(index: number) {
  const leaving = index >= 0 ? crumbs.value[index] : crumbs.value[0];
  crumbs.value = index >= 0 ? crumbs.value.slice(0, index) : [];
  setActive(indexOfNode(leaving));
}

function goUp() {
  if (!crumbs.value.length) return;
  const leaving = crumbs.value[crumbs.value.length - 1];
  crumbs.value = crumbs.value.slice(0, -1);
  setActive(indexOfNode(leaving));
}

function pick(node: DrillNode) {
  if (hasChildren(node)) {
    crumbs.value = [...crumbs.value, node];
    setActive(0);
    return;
  }

  commit([...crumbs.value, node].map((item) => item[props.valueKey] as Id));
}

function clearValue() {
  commit([] as Id[]);
}

/** 先更新 v-model 再抛 change：调用方的 change 处理器一直读的是已同步的绑定量 */
function commit(ids: Id[]) {
  emit('update:modelValue', ids);
  emit('change', ids);
  // 让外层 el-form-item 的 trigger:'change' 规则即时校验（与 el-cascader 行为一致）
  const pending = formItem?.validate?.('change');
  if (pending) pending.catch(() => undefined);
  closePanel();
}

function openWith(focus: 'selected' | 'first' | 'last') {
  pendingFocus.value = focus;
  open.value = true;
}

function closePanel() {
  open.value = false;
  triggerRef.value?.focus();
}

function onTriggerKeydown(event: KeyboardEvent) {
  const { key } = event;

  if (key === 'Escape') {
    if (open.value) {
      event.preventDefault();
      closePanel();
    }
    return;
  }

  if (key === 'Tab') {
    open.value = false;
    return;
  }

  if (key === 'ArrowDown' || key === 'ArrowUp') {
    event.preventDefault();
    if (!open.value) openWith(key === 'ArrowDown' ? 'first' : 'last');
    else setActive(activeIndex.value + (key === 'ArrowDown' ? 1 : -1));
    return;
  }

  if (key === 'Home' || key === 'End') {
    if (!open.value) return;
    event.preventDefault();
    setActive(key === 'Home' ? 0 : currentLevel.value.length - 1);
    return;
  }

  if (key === 'ArrowLeft' || key === 'Backspace') {
    if (open.value && crumbs.value.length) {
      event.preventDefault();
      goUp();
    }
    return;
  }

  if (key === 'Enter' || key === ' ') {
    // 触发器是 div，没有原生激活：关闭态由这里展开、展开态激活当前高亮项
    event.preventDefault();
    if (!open.value) {
      openWith('selected');
      return;
    }
    const node = currentLevel.value[activeIndex.value];
    if (node) pick(node);
  }
}
</script>

<style scoped>
.dd-cascader {
  width: 100%;
}

.dd-anchor {
  position: relative;
  display: flex;
  align-items: center;
  width: 100%;
}

.dd-trigger {
  display: flex;
  align-items: center;
  width: 100%;
  height: var(--el-component-size);
  padding: 0 32px 0 11px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background-color: var(--el-fill-color-blank);
  color: var(--el-text-color-regular);
  font-family: inherit;
  font-size: var(--el-font-size-base);
  line-height: 1;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--el-transition-duration-fast);
}

.dd-trigger.is-small {
  height: var(--el-component-size-small);
  padding: 0 28px 0 8px;
  font-size: var(--el-font-size-extra-small);
}

.dd-trigger.is-large {
  height: var(--el-component-size-large);
  font-size: var(--el-font-size-medium);
}

.dd-trigger:hover:not(.is-disabled) {
  border-color: var(--el-border-color-hover);
}

.dd-trigger.is-open,
.dd-trigger:focus-visible {
  border-color: var(--el-color-primary);
}

.dd-trigger:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.dd-trigger.is-disabled {
  background-color: var(--el-disabled-bg-color);
  border-color: var(--el-disabled-border-color);
  color: var(--el-disabled-text-color);
  cursor: not-allowed;
  pointer-events: none;
}

.dd-trigger__text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.dd-trigger__text.is-placeholder {
  color: var(--el-text-color-placeholder);
}

.dd-trigger__arrow {
  position: absolute;
  right: 9px;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  pointer-events: none;
}

.dd-clear {
  position: absolute;
  right: 26px;
  display: flex;
  align-items: center;
  padding: 2px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--el-text-color-placeholder);
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
}

.dd-clear:hover {
  color: var(--el-text-color-secondary);
}

.dd-clear:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.dd-panel {
  display: flex;
  flex-direction: column;
  max-height: min(320px, 56vh);
}

.dd-panel__head {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
  padding: 4px 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.dd-back {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  padding: 2px;
  border: 0;
  border-radius: var(--el-border-radius-base);
  background: transparent;
  color: var(--el-text-color-secondary);
  cursor: pointer;
}

.dd-back:hover {
  color: var(--el-color-primary);
}

.dd-back:focus-visible,
.dd-crumb:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}

.dd-crumbs {
  display: flex;
  align-items: center;
  min-width: 0;
  overflow-x: auto;
  white-space: nowrap;
}

.dd-crumb {
  padding: 2px 4px;
  border: 0;
  border-radius: var(--el-border-radius-base);
  background: transparent;
  color: var(--el-color-primary);
  font-family: inherit;
  font-size: var(--el-font-size-extra-small);
  white-space: nowrap;
  cursor: pointer;
}

.dd-crumb.is-current {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.dd-crumb__sep {
  color: var(--el-text-color-placeholder);
  font-size: var(--el-font-size-extra-small);
}

.dd-panel__list {
  flex: 1;
  margin: 0;
  padding: 4px;
  overflow-y: auto;
  list-style: none;
}

.dd-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-height: 40px;
  padding: 0 10px;
  border: 0;
  border-radius: var(--el-border-radius-base);
  background: transparent;
  color: var(--el-text-color-regular);
  font-family: inherit;
  font-size: var(--el-font-size-small);
  text-align: left;
  cursor: pointer;
}

.dd-option:hover {
  background-color: var(--el-fill-color-light);
}

/* aria-activedescendant 的可见指示：底色 + 左侧主色条，不依赖颜色单一通道 */
.dd-option.is-active {
  background-color: var(--el-fill-color-light);
  box-shadow: inset 2px 0 0 var(--el-color-primary);
}

.dd-option[aria-selected='true'] {
  color: var(--el-color-primary);
  font-weight: 600;
}

/*
 * 标签必须**吃掉剩余宽度**。
 *
 * 🐞 这里原来是空白的，而 `.dd-option` 是 `justify-content: space-between`。
 * 两子元素（标签 + 箭头）时它恰好正确：标签靠左、箭头靠右。
 * **A4 加了第三个子元素（层级图标）之后，`space-between` 把中间那个也平均分配了**——
 * 名次还是在左边，但标签被推到了行的**正中间**，图标与标签之间空出约 150px。
 * A4 的探针只数了「几行有图标」，**没看过一眼面板**，所以没发现；
 * 是这一轮看分类面板的实拍图才看出来的（第 5 次「看图上才发现」）。
 * `flex: 1 1 auto` 让标签吸收自由空间，两子元素与三子元素两种情况**都**正确，
 * 且两子元素时的几何与改动前逐像素相同。
 */
.dd-option__label {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 层级图标（A4）：16px、继承行内颜色，所以选中态会跟着变主色 */
.dd-option__icon {
  flex: none;
  color: var(--muted);
}
.dd-option[aria-selected='true'] .dd-option__icon {
  color: var(--el-color-primary);
}

.dd-option__more {
  flex-shrink: 0;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.dd-option__check {
  flex-shrink: 0;
  color: var(--el-color-primary);
  font-size: 12px;
}

.dd-empty {
  padding: 18px 0;
  color: var(--el-text-color-placeholder);
  font-size: var(--el-font-size-extra-small);
  text-align: center;
}
</style>
