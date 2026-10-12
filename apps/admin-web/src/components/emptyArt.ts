/**
 * 空状态插画的**唯一映射**（规范 §4.6 G2）。
 *
 * ## 为什么是 3 张而不是 12 张
 *
 * 规范原话：「**一套 2–3 张**（无数据/无结果/无权限），**不是一个空状态一张**」。
 * 全仓 `.el-empty` 的实际落点按**实际文案**分成三组（数出来的，不是分出来的）：
 *
 * | kind | 落点 | 处数 |
 * | :--- | :--- | :--- |
 * | `no-data` | 控制台 3 张图、完工耗材核销、进销存入库历史、流转时间轴、调度池、站内信、`ResponsiveTable` 兜底 | 9 |
 * | `no-result` | 完工页「未找到匹配的在库耗材」、调度中心「未检索到符合条件的维修师傅」 | 2 |
 * | `no-profile` | 个人中心「尚未建立师傅档案，请联系管理员开通后查看作业数据」 | 1 |
 *
 * ⚠️ 第三组的语义**不是「无权限」而是「未建档」**——按它的实际文案取形（头像占位符）。
 * 这样也顺带避开了与 A7（403 那把挂锁）撞形的问题：403 是"你没资格看"，
 * 这页是"你的档案还没建"，两件事。
 *
 * ## 图从哪来
 *
 * 生成式（Qwen-Image-2.1 Turbo + TE-Speed + 20 步，与 G1 同配方），
 * 配方与选片见 `.scratch/gen-empty-art.py` / `ship-empty-art.py`。
 * 落盘的是**归一化之后**的 240×240 WebP：转灰阶、近白压成纯白、q=90。
 * 每张 2~4 KB —— 比 EP 自带的默认插画（SVG）还小。
 *
 * 240 而不是 80：实机显示是 72~80px，240 给到 3 倍 DPR 仍有像素可退。
 */
export type EmptyArtKind = 'no-data' | 'no-result' | 'no-profile';

export const EMPTY_ART: Record<EmptyArtKind, string> = {
  'no-data': '/empty/no-data.webp',
  'no-result': '/empty/no-result.webp',
  'no-profile': '/empty/no-profile.webp'
};

/** 给探针与无障碍用的中文名（`el-empty` 的插画是装饰性的，`alt` 由 `description` 承担） */
export const EMPTY_ART_LABELS: Record<EmptyArtKind, string> = {
  'no-data': '无数据',
  'no-result': '无结果',
  'no-profile': '未建档'
};
