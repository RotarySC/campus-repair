# 校园报修管理系统前端架构与界面交互设计规范

> **文档版本**：v1.0.0  
> **创建日期**：2026-09-06  
> **归档路径**：`docs/20260906-1430-campus-repair-system-frontend-design.md`  
> **溯源需求基线**：`docs/20260906-1219-campus-repair-system-requirements.md`  
> **技术方案基线**：`docs/20260906-1223-campus-repair-system-implementation-plan.md`  
> **架构定型方式**：基于 `/grill-me` 三轮深度设计树盘问（共14个关键决策分支全闭环）

---

## 1. 总体设计愿景与工程架构

### 1.1 核心设计理念
本系统前端定位于面向现代高校全场景的**工程级后勤运维与智能调度交互中枢**。系统兼顾后勤调度人员在桌面端的高密度信息处理需求、管理层在指挥大屏上的沉浸式态势感知，以及一线维修师傅与在校师生在移动弱网环境下的极简、高韧性触屏作业体验。

设计遵循以下核心原则：
1. **高内聚低耦合**：桌面 PC 与移动端在 UI 层面彻底物理解耦，底层 API 协议、业务状态枚举与类型契约 100% 共享。
2. **零打扰与高鲁棒**：无感双 Token 并发刷新、断网与弱网离线草稿自动保全、上传前客户端智能压缩与防篡改水印。
3. **状态可视化与零歧义**：工单全生命周期显式状态机展示、维修前后实况卡片对比、不可篡改审计时间轴、强对比语义状态色彩。

---

### 1.2 Monorepo 工程结构拓扑

系统采用 **pnpm workspace** 搭建 Monorepo 统一代码仓库，组织结构如下：

```text
campus-repair-frontend/
├── package.json
├── pnpm-workspace.yaml
├── tsconfig.base.json
├── .editorconfig
├── .eslintrc.cjs
├── .prettierrc
│
├── packages/                               # 公共共享基础包
│   ├── shared/                             # 通用核心包 (@campus/shared)
│   │   ├── src/
│   │   │   ├── types/                      # 全量 TypeScript 类型契约 (DTO/VO/Entity)
│   │   │   │   ├── auth.ts
│   │   │   │   ├── workorder.ts
│   │   │   │   ├── inventory.ts
│   │   │   │   ├── spatial.ts
│   │   │   │   └── notification.ts
│   │   │   ├── constants/                  # 业务状态枚举、错误码、工种字典常量
│   │   │   │   ├── status.ts               # OrderStatusEnum, UrgencyEnum 等
│   │   │   │   └── error-codes.ts
│   │   │   ├── utils/                      # 通用工具函数
│   │   │   │   ├── format.ts               # 日期时间格式化、金额核算转换
│   │   │   │   ├── watermark.ts            # Canvas 客户端防伪防篡改水印生成器
│   │   │   │   ├── image-compressor.ts     # Canvas/Blob 客户端有损等比压缩器
│   │   │   │   └── storage.ts              # 本地安全缓存与草稿封装
│   │   │   └── api/                        # 统一封装的 Axios HTTP Client 底座
│   │   │       ├── http.ts                 # 双Token无感刷新与并发队列请求拦截器
│   │   │       └── endpoints.ts
│   │   ├── package.json
│   │   └── tsconfig.json
│
├── apps/                                   # 独立客户端应用
│   ├── admin-web/                          # PC端后勤综合管理与调度平台 (@campus/admin)
│   │   ├── index.html
│   │   ├── vite.config.ts
│   │   ├── src/
│   │   │   ├── main.ts
│   │   │   ├── App.vue
│   │   │   ├── router/                     # 动态路由配置与 RBAC 权限守卫
│   │   │   ├── stores/                     # Pinia 状态树 (user, permission, sse)
│   │   │   ├── directives/                 # 自定义指令 (v-perm 按钮级权限)
│   │   │   ├── styles/                     # 经典科技蓝主题变量与 Element Plus 覆盖
│   │   │   ├── components/                 # PC 通用组件 (Timeline, ImageCompare, SLA)
│   │   │   └── views/
│   │   │       ├── login/                  # 统一登录与图形验证码
│   │   │       ├── dispatch/               # 调度中心双栏分屏工作台
│   │   │       ├── workorder/              # 工单高级多维检索与全景详情
│   │   │       ├── inventory/              # 耗材台账、入库流水、库存预警
│   │   │       ├── spatial/                # 四级物理空间树维护
│   │   │       ├── system/                 # 用户台账、角色权限、SLA规则字典
│   │   │       └── cockpit/                # 16:9 全屏深色指挥大屏 (ECharts)
│   │   └── package.json
│   │
│   └── mobile-web/                         # 移动自适应 H5 应用 (@campus/mobile)
│       ├── index.html
│       ├── vite.config.ts
│       ├── src/
│       │   ├── main.ts
│       │   ├── App.vue
│       │   ├── router/                     # 移动端路由与师生/师傅动态分流守卫
│       │   ├── stores/                     # 移动端状态 (worker-order, draft)
│       │   ├── components/                 # 触屏组件 (CascaderDrawer, ActionSheet)
│       │   └── views/
│       │       ├── auth/                   # 移动登录与师生自助注册
│       │       ├── student/                # 师生端
│       │       │   ├── submit/             # 极速报修提单与同位置防重预警
│       │       │   ├── list/               # 我的报修轨迹列表
│       │       │   ├── detail/             # 工单状态轴与验收/评价卡片
│       │       │   └── profile/            # 个人中心与默认住址维护
│       │       └── worker/                 # 维修师傅端
│       │           ├── workbench/          # 三栏状态卡片看板流 (待承接/维修中/历史)
│       │           ├── action/             # 完工填报、耗材核销、挂起与转派抽屉
│       │           └── detail/             # 师傅视角详情与一键电话拨号
│       └── package.json
```

---

## 2. 技术栈选型与核心依赖清单

| 依赖模块 | 选型规格 | 作用说明 |
| :--- | :--- | :--- |
| **基础语言与构建工具** | TypeScript 5.4+<br>Vite 5.2+ | 强类型静态类型校验，ESM 极速热更新，构建秒级响应 |
| **核心框架与核心库** | Vue 3.4+ (Composition API)<br>Vue Router 4.3+<br>Pinia 2.1+ | 现代化组合式 API 开发范式，轻量响应式状态管理，类型安全 |
| **PC 端 UI 组件库** | Element Plus 2.6+<br>@element-plus/icons-vue | 适用于桌面端的高密度数据表格、树形控件、表单校验、抽屉弹窗 |
| **移动端 UI 组件库** | Vant 4.8+<br>@vant/touch-emulator | 专门针对移动触屏优化的底部动作条、图片预览、级联选择抽屉 |
| **数据可视化引擎** | Apache ECharts 5.5+ | 渲染 30 天双折线趋势、分类占比饼图、空间故障热力图与排名榜 |
| **网络通信与流式推送** | Axios 1.6+<br>Native EventSource (SSE) | RESTful API 请求封装、双 Token 无感刷新、服务端实时事件流推送 |
| **客户端图像处理** | compressorjs 1.2+<br>Native HTML5 Canvas | 移动端现场拍照客户端尺寸重采样与压缩、自动合成防伪时间水印 |
| **大屏自适应引擎** | autofit.js 3.1+ (或定制 CSS scale 容器) | 1920×1080 基准分辨率全屏等比缩放，杜绝拉伸变形 |
| **工具函数库** | dayjs 1.11+<br>lodash-es 4.17+ | 轻量日期格式化、防抖节流（用于同位置提单防重检索与草稿暂存） |

---

## 3. UI/UX 设计系统与视觉规范

### 3.1 色彩系统规范 (Design Tokens)

以**专业科技蓝**为核心品牌色，辅以高辨识度的业务状态语义色：

```css
:root {
  /* 品牌核心主色 - 智慧后勤科技蓝 */
  --color-primary: #1677FF;
  --color-primary-hover: #4096FF;
  --color-primary-active: #0958D9;
  --color-primary-bg: #E6F4FF;

  /* 业务状态语义色 (严格对齐工单状态机) */
  --status-pending-dispatch: #FA8C16;    /* 待派单 / 待调度 - 琥珀橙 */
  --status-assigned: #13C2C2;            /* 待承接 - 碧青色 */
  --status-in-progress: #1677FF;         /* 维修中 - 科技蓝 */
  --status-suspended: #FAAD14;           /* 已挂起 - 警示黄 */
  --status-pending-accept: #722ED1;       /* 待验收 - 罗兰紫 */
  --status-rework: #F5222D;               /* 返工待派 - 警戒红 (重点高亮) */
  --status-completed: #52C41A;            /* 已结单 - 森林绿 */
  --status-cancelled: #8C8C8C;            /* 已作废 - 中性灰 */

  /* 中性功能色 */
  --color-bg-base: #F5F7FA;
  --color-bg-card: #FFFFFF;
  --color-text-main: #1F2937;
  --color-text-regular: #4B5563;
  --color-text-secondary: #9CA3AF;
  --color-border: #E5E7EB;

  /* 指挥大屏深色模式专属 */
  --cockpit-bg-main: #0B132B;
  --cockpit-bg-card: #1C2541;
  --cockpit-border: #1F3A60;
  --cockpit-accent-cyan: #48CAE4;
  --cockpit-accent-glow: rgba(72, 202, 228, 0.35);
}
```

### 3.2 排版与触控规范
- **字体栈**：优先选用 `-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif`。
- **字阶设计**：
  - 大屏/核心 KPI：`32px` ~ `44px`（粗体，搭配机械数字字体 `DIN Alternate` / `Chakra Petch`）；
  - 页面一级标题：`20px`（粗体）；
  - 卡片标题/导航：`16px`（中粗）；
  - 正文字体：`14px`（常规，行高 `1.5`）；
  - 次要说明/辅助时间戳：`12px`。
- **移动端触控目标**：所有按钮、选择项及外呼图标的点击命中区域（Hit Area）保证 **≥ 44 × 44 pt**，输入框高度统一设为 `48px`，防止现场工人误触。

### 3.3 已落地的视觉语言约束（2026-09-27 补，收口师傅两页时沉淀）

§3.1 的令牌此前只是"文档里的约定"——各页各自硬编码同一批十六进制值，于是**漂移是必然的**：
`views/worker/WorkbenchView.vue` 与 `views/worker/WorkerFinishView.vue` 在 2026-09-12 的单树重构里
只被换成了 Element Plus，**视觉语言仍是从移动端 Vant 版带过来的**（Vant 调色板、页面级居中限宽、
自造卡片投影、自造状态徽标、`round` 按钮）。两页因此成了全仓唯一风格不统一的两页。
本节把"什么算统一"写成可验证的硬约束——**新页面照此写，改动前先跑 `.scratch/assert-worker-ui.mjs` 的同类断言**。

| # | 约束 | 判据 |
| :-- | :--- | :--- |
| V1 | 页面根节点有内边距 | `padding: 16px`（数据密集型页可用 `20px 24px`）；**不许 0 内边距** |
| V2 | 页面内容装进卡片 | `el-card shadow="never"`；分段卡片用 `#header` + `<span class="block-title">` |
| V3 | 卡片靠**边框**而非投影分层 | `border: 1px solid #E5E7EB` + `border-radius: 8px`；**不许自造 `box-shadow`** |
| V4 | 颜色只取 §3.1 令牌 + 品牌主色 | 自写 chrome 用 `#1677FF` / `#E5E7EB` / `#1F2937` / `#4B5563` / `#9CA3AF` / `#F5F7FA` / `#F9FAFB` / `#E6F4FF` 与 `--status-*`；**EP 组件保留 EP 语义色**（`el-tag`/`el-button` 的 `type` 由组件决定，不要手动染色） |
| V5 | 页面级禁止居中限宽 | **不许** `max-width + margin-inline: auto`（那是移动端单列布局的写法）；只有**表单列**可 `max-width: 760px` 且**左对齐** |
| V6 | 按钮形状统一 | **不许 `round`**；主操作用 `type="primary"`，次级用 `plain` |
| V7 | 状态一律用 `el-tag` | `el-tag :type="orderStatusTagType(status)" effect="light"`；**不许自造 `.status-badge` 之类的彩色 span** |
| V8 | 表单标签随断点走 | `:label-position="isNarrowPhone ? 'top' : 'right'"` + `:label-width="isNarrowPhone ? undefined : '110px'"`（弹窗内可固定 `top`） |
| V9 | 状态配色只有一份实现 | `orderStatusTagType()` 在 `packages/shared/src/constants/status.ts`，**全仓唯一**；见 §3.3.1 |
| V10 | 列表页**宽档必须横向铺开** | 宽档用 `el-table`、窄档用卡片，走 `ResponsiveTable`（**一份列定义**）；**不许把移动端单列卡片拉成全宽**——见 §3.3.2 |
| V11 | 列表页断点按「表格装得下」定 | `:card-below` = 列宽合计 + 余量 + 312（侧边栏 240 + 页 32 + 卡片 40）；**改列宽必须一起复核** |
| V12 | 同一件事只有一个投影 | 状态配色 → `orderStatusTagType()`（§3.3.1）；**时间展示 → `formatDateTime`/`formatDate`（§3.3.3）**；空值文案 → `EMPTY_PLACEHOLDER`（`'—'`）。**两套写法就是下一个「同一个状态两种颜色」** |

**V4 的两点澄清**：① EP 的默认主色是 `#409EFF`，而本项目自写 chrome 用 `#1677FF`——**这个"两个蓝"是既有事实**，
本节不要求消除（消除要改 EP 主题变量，属独立工作），只要求**新代码别再引入第三种蓝**；
② 数据可视化例外见 §7（大屏深色模式有自己的一套 `--cockpit-*`）。

**V5 的由来**：师傅两页原本是移动端 H5（§8.3/8.4），改成 EP 后仍留着 `max-width:900px/760px + margin-inline:auto`
的单列居中，在 1272px 桌面档下表现为"中间一条、两边大片空白"。**§8.3/8.4 的"移动端"定位已被 R1 单树重构取代**
（两页现挂桌面外壳、走同一套断点）。**注意 V5 只解决"居中"这一半**——把移动端卡片从 900px 居中改成全宽
只是让空白换了位置，V10/V11 才是列表页的正解，见 §3.3.2。

#### 3.3.1 `orderStatusTagType()` —— 状态配色的唯一来源

```ts
// packages/shared/src/constants/status.ts（紧挨 OrderStatusLabels：同一件事的两个投影——文案与配色）
export function orderStatusTagType(status?: string | null): OrderStatusTagType {
  switch (status) {
    case 'COMPLETED': return 'success';
    case 'PENDING_ACCEPTANCE': return 'primary';
    case 'REWORK_PENDING': return 'danger';
    case 'IN_PROGRESS':
    case 'ASSIGNED': return 'warning';
    default: return 'info';
  }
}
```

**为什么必须只有一份**：2026-09-27 收口前，`StudentOrderListView.vue` 与 `PersonalCenterView.vue` 里各有
一份**逐字节相同**的 `statusTagType()`；师傅工作台要渲染状态标签时眼看就要出现第三份。三个实现就是
"同一个状态在不同页显示成不同颜色"的起点——趁第三处出现之前先收成一份。

**已知后果（有意对齐，不是回归）**：`ASSIGNED` 与 `IN_PROGRESS` 都映射到 `warning`（橙）、`SUSPENDED`
落到 `info`（灰）。师傅工作台旧的自造徽标是「待承接=蓝 / 维修中=绿 / 已挂起=橙」，收口后与
「我的工单」「个人中心」一致——**判据是"跨页一致"而不是"单页可分辨"**，卡片的挂起信号由
`.suspended-card` 的琥珀左条与 `.suspend-banner` 横幅承担。

#### 3.3.2 列表页的双形态：宽档表格 / 窄档卡片

**症状（2026-09-27 实测，改造前的工作台）**：把移动端单列卡片直接拉成全宽后，卡片里每个块都是整宽的
`display:flex` 块——宽度长上去了，**信息没有跟着横向铺开**：

| 视口 | 卡片宽 | 描述框宽 | 框里文字 | 承诺截止行宽 | 行里文字 |
| :--- | ---: | ---: | ---: | ---: | ---: |
| 1272 | 943 | 914 | 182（**20%**） | 914 | 147（16%） |
| 1440 | 1111 | 1082 | 182（**17%**） | 1082 | 147（14%） |
| 1920 | 1591 | 1562 | 182（**12%**） | 1562 | 147（**9%**） |

**窗口越宽越糟**，而纵向一点没省（一条记录恒为 250~350px 高）。用户的原话是「信息既集中又空旷」。

**参照系**：Cloudflare DNS 记录页（同一份数据）——宽档是**一行一条记录、信息横向铺成列**（名称/类型/内容/
代理状态/TTL/标签/注释），每列按内容定宽、没有整宽背景块；窄档压成 3~4 行卡片，只留识别与判别字段。
一条记录宽档 ≈ 40px、窄档 ≈ 160px。

**本项目的正解**：`ResponsiveTable`（`components/ResponsiveTable.vue`，2026-09-12 起已被 6 张表使用，
`AGENTS.md` §2.1 本就要求新列表页用它）。工作台 2026-09-27 第二轮改造后：

- 宽档 `el-table` 一行一单，**行高 42px**（原卡片 237~350px，约 6~8 倍密度）；
- 窄档卡片由同一份列定义驱动，**卡高 237px**；
- 两个新参数（默认值不变，6 个既有调用方零影响）：
  - `:card-below="1400"` —— 切卡片的视口阈值。**按「表格真装得下」定**，不是随手取的档位：
    工作台列宽合计 1054px，内容区 ≈ 视口 − 312，故 1400 是表格可用的最小视口。
    低于它表格会横滚，而 `fixed:'right'` 的操作列会**浮起来盖住报修人**（实测踩到）。
  - `TableColumn.cardOnly` —— 只在窄档卡片出现的列（故障描述、预约时段）。窄档卡片本来就该把一条记录读全，
    宽档表格要靠列数控宽；同一份列定义因此能同时满足两个目标。

**四条判据（改列表页时必须同时成立）**：
1. **密度**：宽档行高 ≤ 50px。列宽窄一点点就会换行，行高从 42px 涨到 66px，白拿回一半密度。
2. **零横滚**：表格**真实**横向溢出 = 0。⚠️ 量 `.rl-table-wrapper` 会得到假的 0——EP 的横滚在**内层**
   `.el-table__body-wrapper .el-scrollbar__wrap` 上。
3. **列宽按内容实测**：`工单号 196`（含内联「加急」标签）、`报修人 172`（图标+姓名+11 位号码，
   **压到 158 号码就出省略号**）、`操作 192`（主按钮 88 + 两个 link 各 30 + 间距 16 + 内边距 24）。
   ⚠️ EP 给相邻 `.el-button` 默认加 `margin-left: 12px`，与 flex `gap` **叠加**——
   必须 `.action-buttons .el-button + .el-button { margin-left: 0 }`，否则「转派」被挤到第二行。
4. **操作列不允许整列空白**：`el-table` 的每一行都必须有操作（否则这列就不该存在）。
   **「某档恰好没有动作」是设计输入，不是可以留空的理由**——2026-09-27 用户截图指出：工作台
   「历史记录」档的四种状态（待验收/已结单/返工待派/已作废）对师傅都没有业务动作，于是那列
   **整列空白**，实测 **192px / 1126px = 17%** 死宽；窄档更糟——**每张卡底部留一条无内容的虚线**
   （11px 高、0 个按钮）。正解是给一个只读的「查看」（评价 + 流转时间轴），**不是把列删掉**：
   空白列问的不是「要不要这列」，而是**「这里本该有什么」**。详见
   `docs/20260912-1351-…-unified-portal-design.md` §15.6。

#### 3.3.3 时间展示的唯一来源

`packages/shared/src/utils/format.ts`（2026-09-27 抽取，见 `docs/20260912-1351-…-unified-portal-design.md` §16）：

```ts
export const EMPTY_PLACEHOLDER = '—';                       // 空值文案全仓唯一

/** 2026-09-21T16:42:30.775846 → 2026-09-21 16:42；只有日期时原样返回 */
export function formatDateTime(value?: string | null, placeholder = EMPTY_PLACEHOLDER): string;
/** 2026-09-21T16:42:30.775846 → 2026-09-21 */
export function formatDate(value?: string | null, placeholder = EMPTY_PLACEHOLDER): string;
/** 本地时区的今天（不要用 toISOString().slice(0,10)，那是 UTC） */
export function todayIsoDate(): string;
```

抽取前的实际状况——**4 份各自实现 + 3 处裸渲染**：

| 位置 | 实现 | 空值 |
| :--- | :--- | :--- |
| `ResponsiveTable.display()` | `String(raw).replace('T',' ').slice(0,16)` | `'—'` |
| `WorkbenchView.formatTime()` | `replace('T',' ').substring(0,16)` | `'--'` |
| `DispatchCenterView.formatTime()` | **与上一行逐字节相同** | `'--'` |
| `PersonalCenterView.formatDateTime()` | `String(value).replace('T',' ').slice(0,16)` | `'—'` |
| `WorkOrderTimeline` / `NotificationCenterDrawer` / `StudentOrderDetailView` | **无实现——直接把后端原始 ISO 渲染给用户** | — |

**空值文案已经漂移成两套**（`'--'` vs `'—'`）——这正是 V9 那个"同一个状态两种颜色"的同一形态，
只是换成了"同一个空值两种写法"。

**两条不能违反的实现约束**：

1. **必须用字符串切片，不许改成 `new Date(...)` + `toLocaleString()`**。后端所有时间字段都是
   `LocalDateTime`（如 `RepairOrderLog.createdAt`），Jackson 序列化成 `2026-09-21T16:42:30.775846`——
   **没有时区标记**，字面值就是业务时间本身。`Date` 解析会按**浏览器所在时区**重新解释，同一份数据
   在不同机器上显示成不同小时数。`test/format.spec.ts` 用 `vi.spyOn(globalThis,'Date')` 断言
   "实现不得调用 `Date` 解析输入"来锁死这条。
2. **`todayIsoDate()` 不要用 `new Date().toISOString().slice(0,10)`**——那是 **UTC**，UTC+8 的
   00:00~08:00 之间会给出**昨天**（入库单「批次日期」默认值曾这样差一天）。

**⚠️ 加公共工具函数前先 grep 一遍名字**：`src/index.ts` 是 `export *` 的 barrel，同名导出不会在写代码时
提示，只会在 typecheck 阶段报 `TS2308` 与一串 `TS2345`。本轮就撞上了 `utils/watermark.ts` 里那个
**同样叫 `formatDateTime`** 的水印时间戳（已改名 `formatWatermarkStamp`：它精确到秒、输入是 `Date`、
用途是"给照片盖现场时间戳"，与"把后端时间显示给人看"是两件事）。

---

## 4. 核心底层基础设施与通用设计

### 4.1 双 Token 无感刷新与并发队列拦截器

前端通过 Axios 封装严格的无感换票拦截器，解决长效登录与会话中断问题：

```typescript
// packages/shared/src/api/http.ts
import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { useAuthStore } from '../stores/auth';

let isRefreshing = false;
let retrySubscribers: Array<(newToken: string) => void> = [];

function subscribeTokenRefresh(cb: (newToken: string) => void) {
  retrySubscribers.push(cb);
}

function onRefreshed(newToken: string) {
  retrySubscribers.forEach((cb) => cb(newToken));
  retrySubscribers = [];
}

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 15000,
});

// 请求拦截器：自动注入 Bearer Access Token
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const authStore = useAuthStore();
  if (authStore.accessToken && !config.headers.Authorization) {
    config.headers.Authorization = `Bearer ${authStore.accessToken}`;
  }
  return config;
});

// 响应拦截器：捕获 401 并发互斥刷新
http.interceptors.response.use(
  (response) => response.data,
  async (error: AxiosError) => {
    const { response, config } = error;
    const authStore = useAuthStore();

    if (response?.status === 401 && config) {
      if (!isRefreshing) {
        isRefreshing = true;
        try {
          // 调用换票接口
          const res = await axios.post('/api/v1/auth/refresh-token', {
            refreshToken: authStore.refreshToken,
          });
          const { accessToken, refreshToken } = res.data.data;
          authStore.setTokens(accessToken, refreshToken);
          isRefreshing = false;
          onRefreshed(accessToken);

          // 重新发起当前失败请求
          config.headers.Authorization = `Bearer ${accessToken}`;
          return http(config);
        } catch (refreshErr) {
          isRefreshing = false;
          retrySubscribers = [];
          authStore.logout();
          window.location.href = '/login?expired=1';
          return Promise.reject(refreshErr);
        }
      } else {
        // 挂起并发请求，放入订阅队列
        return new Promise((resolve) => {
          subscribeTokenRefresh((newToken: string) => {
            config.headers.Authorization = `Bearer ${newToken}`;
            resolve(http(config));
          });
        });
      }
    }
    return Promise.reject(error);
  }
);
```

### 4.2 客户端图像有损压缩与防篡改水印生成器

一线移动拍照通过 Canvas 进行无服务器消耗的纯客户端优化：

```typescript
// packages/shared/src/utils/image-compressor.ts
export interface WatermarkOptions {
  orderNo: string;
  workerName: string;
  timestamp?: string;
}

export async function processAndWatermarkImage(
  file: File,
  options?: WatermarkOptions
): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.readAsDataURL(file);
    reader.onload = (e) => {
      const img = new Image();
      img.src = e.target?.result as string;
      img.onload = () => {
        const canvas = document.createElement('canvas');
        const ctx = canvas.getContext('2d');
        if (!ctx) return reject(new Error('Canvas context unavailable'));

        // 1. 等比重采样：限制长边最大 1920px
        const maxDimension = 1920;
        let { width, height } = img;
        if (width > maxDimension || height > maxDimension) {
          if (width > height) {
            height = Math.round((height * maxDimension) / width);
            width = maxDimension;
          } else {
            width = Math.round((width * maxDimension) / height);
            height = maxDimension;
          }
        }
        canvas.width = width;
        canvas.height = height;
        ctx.drawImage(img, 0, 0, width, height);

        // 2. 注入防伪防篡改水印层 (若传参)
        if (options) {
          const fontSize = Math.max(16, Math.round(width * 0.022));
          ctx.font = `600 ${fontSize}px "Segoe UI", sans-serif`;
          ctx.fillStyle = 'rgba(0, 0, 0, 0.45)';
          const pad = fontSize * 0.8;
          const lineH = fontSize * 1.35;
          const boxW = fontSize * 15;
          const boxH = lineH * 3 + pad;

          // 绘制半透明黑色底卡
          ctx.fillRect(width - boxW - pad, height - boxH - pad, boxW, boxH);

          // 绘制白色文字
          ctx.fillStyle = '#FFFFFF';
          const timeText = options.timestamp || new Date().toLocaleString();
          ctx.fillText(`单号: ${options.orderNo}`, width - boxW, height - boxH + lineH);
          ctx.fillText(`作业: ${options.workerName}`, width - boxW, height - boxH + lineH * 2);
          ctx.fillText(`时间: ${timeText}`, width - boxW, height - boxH + lineH * 3);
        }

        // 3. 质量 0.82 导出为 JPEG Blob (体积通常压缩至 300KB~700KB)
        canvas.toBlob(
          (blob) => {
            if (blob) resolve(blob);
            else reject(new Error('Image blob conversion failed'));
          },
          'image/jpeg',
          0.82
        );
      };
      img.onerror = reject;
    };
    reader.onerror = reject;
  });
}
```

### 4.3 SSE 实时消息通知与全局未读总线

```typescript
// apps/admin-web/src/stores/sse.ts
import { defineStore } from 'pinia';
import { ElNotification } from 'element-plus';

export const useSseStore = defineStore('sse', {
  state: () => ({
    eventSource: null as EventSource | null,
    unreadCount: 0,
    latestNotice: null as any,
  }),
  actions: {
    initSse(token: string) {
      if (this.eventSource) this.eventSource.close();

      // 基于 SSE 单向数据流
      this.eventSource = new EventSource(`/api/v1/notifications/stream?token=${token}`);

      this.eventSource.addEventListener('ORDER_DISPATCH', (e) => {
        const payload = JSON.parse(e.data);
        this.unreadCount++;
        ElNotification({
          title: '⚡ 调度即时提醒',
          message: payload.message,
          type: 'info',
          duration: 4500,
        });
      });

      this.eventSource.addEventListener('REWORK_ALERT', (e) => {
        const payload = JSON.parse(e.data);
        this.unreadCount++;
        ElNotification({
          title: '🚨 差评返工置顶预警',
          message: payload.message,
          type: 'error',
          duration: 0, // 常驻需手动关闭
        });
      });

      this.eventSource.onerror = () => {
        // 网络抖动时自动降级或重连
        console.warn('[SSE] 连接中断，准备重连');
      };
    },
    close() {
      this.eventSource?.close();
      this.eventSource = null;
    },
  },
});
```

---

## 5. 权限控制与路由守卫体系

### 5.1 角色路由白名单与动态装载

```typescript
// apps/admin-web/src/router/routes.ts
import { RouteRecordRaw } from 'vue-router';

export const asyncRoutes: RouteRecordRaw[] = [
  {
    path: '/dispatch',
    name: 'DispatchCenter',
    component: () => import('@/views/dispatch/index.vue'),
    meta: {
      title: '调度中心',
      icon: 'Headset',
      roles: ['SUPER_ADMIN', 'LOGISTICS_ADMIN'],
    },
  },
  {
    path: '/workorder',
    name: 'WorkOrderManager',
    component: () => import('@/views/workorder/index.vue'),
    meta: {
      title: '工单管理',
      icon: 'Document',
      roles: ['SUPER_ADMIN', 'LOGISTICS_ADMIN'],
    },
  },
  {
    path: '/inventory',
    name: 'InventoryManager',
    component: () => import('@/views/inventory/index.vue'),
    meta: {
      title: '耗材备件库',
      icon: 'Box',
      roles: ['SUPER_ADMIN', 'LOGISTICS_ADMIN'],
    },
  },
  {
    path: '/spatial',
    name: 'SpatialManager',
    component: () => import('@/views/spatial/index.vue'),
    meta: {
      title: '空间与分类网格',
      icon: 'OfficeBuilding',
      roles: ['SUPER_ADMIN', 'LOGISTICS_ADMIN'],
    },
  },
  {
    path: '/cockpit',
    name: 'CockpitDashboard',
    component: () => import('@/views/cockpit/index.vue'),
    meta: {
      title: '后勤运营大屏',
      icon: 'DataAnalysis',
      roles: ['SUPER_ADMIN', 'LOGISTICS_ADMIN'],
      isFullScreen: true,
    },
  },
];
```

### 5.2 移动端角色智能分流守卫

用户使用手机打开同一域名时，路由守卫进行感知并分流：

```typescript
// apps/mobile-web/src/router/index.ts
router.beforeEach(async (to, from, next) => {
  const authStore = useAuthStore();
  if (!authStore.isLoggedIn && to.path !== '/login') {
    return next(`/login?redirect=${to.fullPath}`);
  }

  // 角色路由分流
  if (to.path === '/' || to.path === '/home') {
    if (authStore.userType === 'REPAIR_WORKER') {
      return next('/worker/workbench');
    } else {
      return next('/student/submit');
    }
  }

  // 跨角色越权防御
  if (to.path.startsWith('/worker') && authStore.userType !== 'REPAIR_WORKER') {
    return next('/student/submit');
  }
  if (to.path.startsWith('/student') && authStore.userType === 'REPAIR_WORKER') {
    return next('/worker/workbench');
  }

  next();
});
```

---

## 6. PC 管理端核心界面与交互详细设计

### 6.1 后勤调度中心双栏分屏工作台 (Dispatch Console)

- **路由**：`/dispatch`
- **设计目标**：无需打开遮罩弹窗，实现全校待派工单与在岗师傅的“同屏秒级对齐指派”。
- **视口划分**：
  - **左栏（55% 宽度 - 待办调度池）**：
    - 顶部快捷过滤器：`全部 (18)`、`智能派单失败 (8)`、`师傅申请转派 (3)`、`加急异常单 (4)`、`返工待指派 (3 - 标红高亮)`。
    - 工单卡片列表（虚拟滚动支撑上千工单）：
      - 卡片信息：工单流水号、故障分类标签（如【水电】水龙头破裂）、空间全路径（朝阳校区/12号楼/302）、报修人联系方式、期望时段；
      - 状态徽章：加急单带闪烁红标，超时临界单带黄色倒计时沙漏；
      - 当前选中态：深蓝边框高亮，右侧师傅看板自动触发条件联动过滤。
  - **右栏（45% 宽度 - 师傅负荷与指派看板）**：
    - 联动面板头部：展示当前左侧所选工单匹配规则：`工种需求: 水暖工` | `所属校区: 朝阳校区`。
    - 师傅网格矩阵（每行 2 列卡片）：
      - 卡片顶部：师傅姓名、头像、综合评分（`★ 4.95`）、在岗状态开关。
      - 负荷指示器：带直观彩色百分比进度条（如 `3/10` 绿色；`8/10` 橙色；`10/10` 红色打上【已满载】禁用标签）。
      - 关键战绩：今日已完工单数、平均修复时长。
      - 操作区：卡片底部提供大尺寸高对比度按钮【指派给该师傅】；若为返工单，支持选择【指派原师傅整改】或【换派高星技师】。

### 6.2 工单多维高级检索与全景详情卡片

- **路由**：`/workorder`、`/workorder/:id`
- **检索面板**：
  - 支持收起/展开的高级过滤行：空间四级 Cascader 级联、故障二级分类、负责师傅选择、状态多选、提交日期 RangePicker。
  - 列表工具栏：支持一键选中多条工单后【批量催办】与【导出 Excel 报表】。
- **全景详情页布局（双 Tab 结构）**：
  - **顶层概览看板**：
    - 左侧显示工单号、状态大徽标、紧急等级、报修人基本信息；
    - 右侧显示 SLA 进度仪表盘：已耗时 `04:20:00` / 承诺时限 `24:00:00`（剩余时间动态倒计时，超过 80% 触发橙色预警，超时变红）。
  - **Tab 1 - 现场实况与质检报告**：
    - **现场对比区域（Visual Diff）**：
      - 左侧【报修现场原始凭证】：1~3 张师生提交图片（支持点击原图放大预览与旋转），附带师生故障详细文字描述；
      - 右侧【完工现场修复成果】：1~3 张师傅修复完工照（显示防伪时间水印），附带师傅维修总结文本；
    - **耗材核销清单**：表格展示本次领用的备件名称、规格型号、数量、单价与合计直接材料成本；
    - **师生质检评价卡**：展示 1~5 星打分、勾选标签（如“技术专业”、“上门快”）及师生评语。
  - **Tab 2 - 全生命周期流转时间轴（不可篡改审计链）**：
    - 纵向时间轴组件：
      - 节点图标按操作主体着色：学生（蓝色）、系统（紫色）、师傅（橙色）、主管（绿色）；
      - 节点内容：时间戳、动作类型（如【自动派单】、【工单挂起】、【验收通过】）、执行人真实姓名、详细流转原因说明（如挂起时的具体缺料说明）。

### 6.3 耗材轻量进销存与安全库存预警

- **路由**：`/inventory`
- **功能界面设计**：
  - **顶部预警横幅**：当存在 `实际库存 <= 安全阈值` 物料时，顶部常驻红色警告横幅：“当前有 3 种耗材处于缺料告警状态，请及时补货”，右侧附带按钮【一键导出采购建议单】。
  - **备件台账表格**：支持根据类别切换 Tab（水暖管件、电工电气、门窗五金等）；库存数量列引入警戒线柱状条，低库存行背景高亮淡红；
  - **采购入库单录入抽屉**：支持快速选择备件、输入采购批次号、单价与入库数量，底部即时根据数量换算采购总额并保存流水。

---

## 7. 16:9 后勤运营指挥大屏 (Command Cockpit)

### 7.1 视口适配方案 (autofit / scale 缩放容器)

采用 **1920 × 1080** 物理基准分辨率，CSS `transform: scale(scaleRate)` 保持严格 16:9 纵横比，非 16:9 视口两翼平滑居中留黑，杜绝图表拉伸变形：

```vue
<template>
  <div class="cockpit-viewport">
    <div class="cockpit-canvas" :style="canvasStyle">
      <!-- 大屏头部 -->
      <CockpitHeader @exit="handleExit" />

      <!-- 核心大屏栅格 -->
      <div class="cockpit-grid">
        <div class="grid-col left-col">
          <KpiSummaryCards :kpis="kpiData" />
          <TrendAreaChart :data="trendData" />
        </div>
        <div class="grid-col center-col">
          <CampusSpatialHeatmap :data="spatialHeatData" />
          <RealtimeDispatchMonitor :logs="realtimeLogs" />
        </div>
        <div class="grid-col right-col">
          <CategoryPieChart :data="categoryPieData" />
          <WorkerLeaderboard :rankings="workerRankings" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue';

const scale = ref(1);

function updateScale() {
  const baseWidth = 1920;
  const baseHeight = 1080;
  const currentWidth = window.innerWidth;
  const currentHeight = window.innerHeight;
  const scaleX = currentWidth / baseWidth;
  const scaleY = currentHeight / baseHeight;
  scale.value = Math.min(scaleX, scaleY);
}

const canvasStyle = computed(() => ({
  transform: `scale(${scale.value}) translate(-50%, -50%)`,
  transformOrigin: '0 0',
  left: '50%',
  top: '50%',
  position: 'absolute' as const,
  width: '1920px',
  height: '1080px',
}));

onMounted(() => {
  updateScale();
  window.addEventListener('resize', updateScale);
});
onUnmounted(() => window.removeEventListener('resize', updateScale));
</script>

<style scoped>
.cockpit-viewport {
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background-color: #0b132b;
  position: relative;
}
.cockpit-grid {
  display: grid;
  grid-template-columns: 520px 1fr 520px;
  gap: 20px;
  padding: 16px 24px;
  height: calc(1080px - 84px);
}
</style>
```

### 7.2 大屏六大模块数据可视化规划

1. **头部核心 KPI 卡片（左上）**：
   - 6 个发光科技质感卡片：历史总报修量、今日新增工单、在修挂起单量、闭环率（%）、平均修复响应时效（h）、师生好评度（4.89分）；
   - 数字采用翻牌器动画（`countUp.js`），每秒动态刷新。
2. **近 30 天报修与结单趋势图（左下）**：
   - ECharts 双面积渐变折线图（提单量：蓝紫渐变，结单量：翠绿渐变），直观呈现波峰周期（如开学周高峰）。
3. **校园空间故障热力图与调度态势（中轴）**：
   - 展现各校区（朝阳/海淀）及楼栋组团的故障密集度排行；
   - 支持鼠标点击某一楼栋，大屏其余图表联动过滤该楼栋的数据切片。
4. **故障类型结构占比环形图（右上）**：
   - 水电、暖通、门窗、多媒体等类别的环形南丁格尔玫瑰图，开启定时轮播高亮高发分类。
5. **维修师傅月度绩效风云榜 Top 10（右下）**：
   - 师傅完工单数横向排行榜（金色、银色、铜色奖牌徽标，附带师傅好评率进度条与平均工时）。

---

## 8. 移动端 (Mobile H5) 核心界面与交互详细设计

### 8.1 师生端极速报修提单与防重预警

- **路由**：`/student/submit`
- **界面交互流**：
  1. **空间位置选择**：
     - 若已登录用户在个人中心设置了常用地址（如：朝阳校区-1号楼-302），表单默认自动带出，展示为地址标签并提供“切换”链接；
     - 点击切换时弹出 Vant Cascader 底部抽屉，支持顶部搜索框模糊检索，4 级空间选中后平滑回填。
  2. **故障分类选取**：
     - 水平滚动 Tab 展示一级大类（水电、暖通、家具等），下方展示二级小类图形卡片（图标 + 名称），点击即选中。
  3. **异步同位置防重预警卡**：
     - 当“空间”与“分类”均选择完成后，前端防抖 300ms 自动调取 `/work-orders/check-duplicate`；
     - 若检测到该宿舍/房间存在未结单同类故障，表单中部瞬时滑入柔和黄色提示条：
       > ⚠️ **温馨提示**：该房间已有同类报修单（`BX20260906000001`），李师傅正在处理中。您可以点击【查看进度】或【继续提交】。
  4. **现场照片拍照/选取**：
     - Vant Uploader 组件，支持上传 1~3 张现场照片；
     - 选取图片后直接触发 Canvas 本地无损压缩，上传进度条清晰可视化。
  5. **期望时段与加急开关**：
     - 单选胶囊按钮：上午 (08:30-11:30)、下午 (14:00-17:30)、晚间 (18:30-21:00)、周末全天；
     - 紧急程度切换开关（紧急单高亮提示）。
  6. **草稿自动恢复机制**：
     - 监听表单输入变化，实时写入 `localStorage['REPAIR_DRAFT']`；
     - 用户若不小心刷新或误退，重新进入页面时检测到未提交数据，顶部弹出 Toast：“已自动为您恢复未提交的草稿内容”。

### 8.2 师生验收、满意度打分与差评返工

- **路由**：`/student/detail/:id`
- **界面交互**：
  - 当工单处于 `PENDING_ACCEPTANCE`（待验收）状态时，详情页底部常驻操作栏【确认完工并评价】；
  - 页面显眼处呈现师傅修复后的现场照片（带水印）和故障排除说明；
  - 弹出全屏评价抽屉：
    - 综合满意度星级打分（1~5 星，动态表情图：1星极差红脸，5星极好笑脸）；
    - 满意度快捷标签（准时到达、技术专业、工完场清、着装规范）；
    - 评语留言框（0~200 字）；
  - **差评联动阻断**：若用户打出 ≤2 星，前端立即动态展开必填输入项“请指出未彻底解决的问题说明”，并提示用户：“提交后该工单将立即转入返工督办流程，主管将安排人员二次上门”。

### 8.3 维修师傅移动自适应工作台 (Worker Mobile Desk)

> **【2026-09-27 定位已变更】** 本节写于「双树 + 移动端 Vant」时期。R1 单树重构后两页挂桌面外壳、
> 走同一套断点；**视觉形态按 §3.3 V10 改为宽档 `el-table` / 窄档卡片**（不再是"移动端单列卡片"）。
> 本节仍有效的只有**信息项与业务动作**（拨号、接单出发、挂起、转派、完工填报）——那部分照旧。

- **路由**：`/worker/workbench`
- **核心组件**：顶部三栏粘性 Tab（Sticky Header）：
  - **【待承接】**：展示系统智能指派过来的新单，大字号呈现预约时段、紧急度标签、故障位置；卡片内置绿色 `tel:` 拨号按钮（可一键呼叫报修学生协商具体上门时间）；底部常驻大按钮【接单出发】。
  - **【维修中】**：展示已接单在修任务。卡片提供三个核心操作按钮：
    - 【挂起】：弹出抽屉选择挂起原因（缺备件/外部协调/现场断水断电），录入预计恢复时间；
    - 【申请转派】：现场排查发现超出自身技能时，填写转派原因与推荐工种，一键移交调度池；
    - 【完工结单】：唤起完工填报工作台。
  - **【历史记录】**：查看已归档历史完工单据，包含最终师生评价星级。

### 8.4 师傅单页完工填报与耗材核销工作台

> **【2026-09-27 定位已变更】** 同 §8.3：不再是移动端专属页；视觉按 §3.3（根节点 16px 内边距、
> 4 段 `el-card shadow="never"`、表单列 `max-width:760px` 左对齐、标签随断点）。要素本身照旧。

- **路由**：`/worker/finish/:id`
- **页面要素**：
  1. **现场完工拍照**：强制要求 1~3 张，点击直接唤起系统相机拍摄，拍摄完毕自动调用防伪水印工具盖上时间与单号印章；
  2. **维修总结文本框**：录入故障原因与维修手段；
  3. **耗材核销列表**：
     - 点击【+ 添加所耗耗材】打开全屏搜索选择面板，直观显示每种备件的当前库房物理库存；
     - 选入主表单后，每项物料配备步进器（Stepper）；若数量大于物理库存，步进器强制上限并提示红色警告；
     - 表单底部实时计算物料直接总成本：`本次领料成本：¥ 45.00`。
  4. **提交完工**：一键将工单推进到待验收状态，并原子扣减库存。

---

## 9. 异常防御、弱网容错与性能优化规范

### 9.1 网络离线状态感知与提示
在移动端根组件 `App.vue` 中挂载全局网络状态监听器：

```typescript
// apps/mobile-web/src/App.vue
import { onMounted, onUnmounted, ref } from 'vue';
import { showNotify } from 'vant';

const isOnline = ref(navigator.onLine);

const updateOnlineStatus = () => {
  isOnline.value = navigator.onLine;
  if (!isOnline.value) {
    showNotify({
      type: 'warning',
      message: '当前处于无网络/弱网环境，表单草稿已自动保存在本地',
      duration: 0,
    });
  } else {
    showNotify({ type: 'success', message: '网络已恢复连接', duration: 2000 });
  }
};

onMounted(() => {
  window.addEventListener('online', updateOnlineStatus);
  window.addEventListener('offline', updateOnlineStatus);
});
onUnmounted(() => {
  window.removeEventListener('online', updateOnlineStatus);
  window.removeEventListener('offline', updateOnlineStatus);
});
```

### 9.2 首屏性能优化与按需分包策略
1. **路由懒加载**：全量路由组件采用动态 `() => import(...)` 懒加载。
2. **CDN 与分包优化（Vite 拆包）**：
   - 将 `echarts`、`element-plus`、`vant`、`vue`、`pinia` 拆分为独立 Chunk，避免主包过大；
   - 移动端生产打包体积严格控制在首屏 JS < 300KB（Gzip），保障学生在楼道 3G/4G 弱网下 1.5 秒内首屏渲染。
3. **骨架屏（Skeleton）与占位**：
   - 移动端工单列表与详情页在网络请求挂起期间，展示结构对齐的骨架屏灰色卡片，杜绝页面抖动与空白等待。

---

## 10. 设计交付与落地追踪矩阵

| 前端设计交付模块 | 对应技术实现包与路径 | 关联业务需求编号 | 验证手段与验收准则 |
| :--- | :--- | :--- | :--- |
| **Monorepo 与共享层** | `packages/shared` | REQ-1.1 ~ REQ-6.3 | pnpm 构建无类型报错，两端共享 API 与 TypeScript 类型 |
| **双 Token 无感刷新** | `packages/shared/src/api/http.ts` | REQ-1.1 | 模拟 Access Token 401，并发请求被拦截并在换票后无感重新触发 |
| **防篡改水印与客户端压缩** | `packages/shared/src/utils/` | REQ-3.1, REQ-3.4 | 10MB 原始照片压缩至 < 800KB，且右下角合成单号与作业时间 |
| **PC 调度中心双栏分屏** | `apps/admin-web/.../dispatch/` | REQ-3.2, REQ-3.3 | 左侧点选工单，右侧师傅看板联动高亮匹配工种与当前负荷进度条 |
| **16:9 运营指挥大屏** | `apps/admin-web/.../cockpit/` | REQ-6.1 | autofit 保持 16:9 等比缩放，图表轮播高亮，热力数据支持下钻联动 |
| **移动端极速提单与防重** | `apps/mobile-web/.../student/` | REQ-3.1 | 默认地址预填，同位置防重预警卡弹出，支持本地草稿自动暂存恢复 |
| **师傅工作台与耗材核销** | `apps/mobile-web/.../worker/` | REQ-3.4, REQ-4.3 | 三栏卡片流，一键外呼唤起，耗材步进器严格受限于当前库存 |
| **工单前后对比与时间轴** | `apps/admin-web`, `apps/mobile` | REQ-3.6, REQ-5.2 | Visual Diff 左右对比卡片，全生命周期不可逆审计时间轴清晰可溯 |
