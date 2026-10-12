/**
 * 校园空间网格的**建筑性质**词表。
 *
 * 取值来源是 `spatial_node.building_nature`，后端在 `SpatialNode.java` 上标注了取值集合：
 * `DORMITORY, TEACHING, OFFICE, VENUE, OUTDOOR`。
 *
 * ⚠️ **这个映射以前只存在于一个地方**：空间字典页「建筑性质」下拉框里那 5 行 `el-option`。
 * 而同一页的树节点把 `buildingNature` **直接渲染**：
 *
 * ```html
 * <el-tag>{{ data.buildingNature }}</el-tag>   <!-- 用户看到的是 DORMITORY / TEACHING / OFFICE -->
 * ```
 *
 * 于是「学生宿舍」在下拉里是中文、在同一页的树上就是 `DORMITORY`——
 * 与 AGENTS.md 反复点名的「不许把 `PLUMBER` 这种字典码端给用户」是同一个缺陷，
 * 只是它藏在 `el-tree` 的节点里，**表格单元格的扫描看不见**。
 *
 * 现在是**一处声明、两处消费**：下拉框由它 `v-for` 生成，树上的标签用它翻译。
 * 新增一个建筑性质只需要改这一个文件。
 */
export const BuildingNatureLabels: Record<string, string> = {
  DORMITORY: '学生宿舍',
  TEACHING: '教学科研',
  OFFICE: '行政办公',
  VENUE: '公共场馆',
  OUTDOOR: '室外公共'
};

/** 供下拉框 `v-for` 用的有序选项（顺序即展示顺序，与后端注释的声明顺序一致） */
export const BuildingNatureOptions = Object.entries(BuildingNatureLabels).map(([value, label]) => ({
  value,
  label
}));

/** 未知码**回落成原码**而不是空串：悄悄显示空白比显示裸码更难排查 */
export const buildingNatureLabelOf = (code?: string | null): string =>
  code ? BuildingNatureLabels[code] ?? code : '';
