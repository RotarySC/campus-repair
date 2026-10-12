<template>
  <!--
    **中性小标签**（视觉语言 D）：发丝线描边 + 弱化文字，零底色。

    ## 它替代的是什么

    全仓有 9 处把**分类/装饰**渲染成了 EP 的语义色 `el-tag`：

      · 工种（水暖管道…）—— 空间字典里是 `warning` 琥珀，调度中心里是 `primary` 蓝
      · 条线身份（行政/后勤/教学/学生）—— `danger` 红 / `warning` 橙 / `primary` 蓝 / `info` 灰
      · 账号职能、层级、助手「首选」、报修/完工实况标签…

    这些**都不是状态**。D 的规矩是「色相只服务状态语义」（§5 的 V4 修订），
    而上面每一个都在借用 `danger`/`warning` 这些**有确定含义**的档去表示
    「这是后勤」「这是二级」—— 属于「一个色相两个意思」。

    ## 为什么不用 `el-tag` 不传 `type`

    因为**`el-tag` 的 `type` 缺省值就是 `primary`**（浅蓝底），不传不等于中性。
    这条在 §18.3 已经吃过一次。想真中性只能换元素 —— 就是这个组件。

    ## 与 `.category-level-chip` 的关系

    那是 A3（分类图标）那一批在 `SpatialManageView` 里就地写的一份同款样式。
    本组件落地后**它改用这里**，全仓的「分类的中性表达」只有一种长相。
  -->
  <span class="meta-chip"><slot /></span>
</template>

<style scoped>
.meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: none;
  padding: 0 6px;
  border: var(--hairline) solid var(--line);
  border-radius: var(--radius);
  color: var(--faint);
  font-size: var(--fs-base);
  line-height: 18px;
  white-space: nowrap;
}
</style>
