import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus';
import type { ApiResponse, DispatchOrderItemVO, PageResult } from '@campus/shared';
import StudentOrderListView from './StudentOrderListView.vue';

/**
 * 「我的工单」列表的挂载测试（Phase 07 · D1：整页挂载只做这一例）。
 *
 * 覆盖的是**列表页的职责**，不是 Element Plus 的表格实现：
 * - 后端返回两条工单 → 渲染两行（数据没有在映射层被吞掉）；
 * - 「取消」入口只看 `canCancel`：两行里只有一行有这个按钮（规则在后端，前端不许自己按 status 推）；
 * - 电话掩码由后端给的 `*PhoneDisplay` 承载、且只在详情页展示 —— 列表页不得泄漏原始号码；
 * - 点击取消 → 弹窗 → 二次确认 → `POST /work-orders/{id}/cancel-by-reporter`（对话框内部行为也在此验证）。
 *
 * store 用 `vi.mock` 整个替换：本测试不需要 pinia，也不需要真 axios。
 */

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  push: vi.fn()
}));

vi.mock('../../stores/auth', () => ({
  httpClient: {
    get: apiMocks.get,
    post: apiMocks.post,
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn()
  },
  useAuthStore: () => ({ userInfo: null, isAuthenticated: true })
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: apiMocks.push })
}));

function response<T>(data: T): ApiResponse<T> {
  return { code: 200, message: 'ok', data, timestamp: 0 };
}

function order(overrides: Partial<DispatchOrderItemVO> = {}): DispatchOrderItemVO {
  return {
    id: 101,
    orderNo: 'BX20261024000001',
    studentUserId: 7,
    applicantName: '张三',
    applicantPhone: '13800138000',
    applicantPhoneDisplay: '138****8000',
    spaceId: 1,
    spaceFullPath: '东区 1 号楼 101',
    categoryId: 2,
    categoryFullName: '水暖管道',
    workerSkillCode: 'PLUMBER',
    description: '水龙头漏水',
    urgencyLevel: 2,
    appointmentPeriod: '工作日 09:00-12:00',
    status: 'PENDING_DISPATCH' as DispatchOrderItemVO['status'],
    isSlaTimeout: 0,
    reworkCount: 0,
    suspendCount: 0,
    materialCost: 0,
    createdAt: '2026-10-24T09:00:00',
    updatedAt: '2026-10-24T09:00:00',
    canCancel: true,
    workerPhoneDisplay: '139****0102',
    ...overrides
  };
}

const ROWS: DispatchOrderItemVO[] = [
  order({ id: 101, orderNo: 'BX20261024000001', canCancel: true, urgencyLevel: 2 }),
  order({
    id: 102,
    orderNo: 'BX20261024000002',
    status: 'ASSIGNED' as DispatchOrderItemVO['status'],
    canCancel: false,
    urgencyLevel: 1,
    workerName: '李师傅',
    workerPhoneDisplay: '139****0102'
  })
];

async function mountView() {
  const wrapper = mount(StudentOrderListView, {
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  });
  // onMounted 里的 fetchData 是异步的：先让 Promise 结算，再让表格的响应式渲染跑完
  await flushPromises();
  await nextTick();
  return wrapper;
}

/** 在 wrapper 与 teleport 到 body 的节点里一起找按钮（弹窗默认可能被传送出组件树） */
function findButton(wrapper: Awaited<ReturnType<typeof mountView>>, text: string): HTMLElement | undefined {
  const inWrapper = wrapper
    .findAll('button')
    .find((button) => button.text().trim() === text);
  if (inWrapper) return inWrapper.element as HTMLElement;
  return [...document.body.querySelectorAll('button')].find(
    (button) => button.textContent?.trim() === text
  );
}

describe('StudentOrderListView · 我的工单列表', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiMocks.get.mockResolvedValue(
      response<PageResult<DispatchOrderItemVO>>({
        records: ROWS,
        total: ROWS.length,
        size: 10,
        current: 1,
        pages: 1
      })
    );
    apiMocks.post.mockResolvedValue(response<null>(null));
    // 对话框里的二次确认：直接放行，避免测试去操作 ElMessageBox 的 DOM
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never);
    // 成功提示不参与断言，但会往 body 插节点；静音避免污染 DOM 查询
    vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: () => {} }) as never);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

  it('① 后端返回两条工单 → 表格渲染两行，且请求的是 /work-orders/my', async () => {
    const wrapper = await mountView();

    // 期望列表接口被调用一次：页面数据必须来自后端而不是本地兜底
    expect(apiMocks.get).toHaveBeenCalledWith('/work-orders/my', expect.any(Object));
    // 期望两行：少于两行说明其中一条被映射逻辑吞掉（比如缺字段导致 v-if 隐藏）
    expect(wrapper.findAll('.el-table__body tr.el-table__row')).toHaveLength(2);
    expect(wrapper.text()).toContain('BX20261024000001');
    expect(wrapper.text()).toContain('BX20261024000002');
  });

  it('② 「取消」按钮只出现一次：入口只由 canCancel 决定，不由前端按状态推断', async () => {
    const wrapper = await mountView();

    const cancelButtons = wrapper.findAll('button').filter((button) => button.text().trim() === '取消');
    // 期望只有 canCancel=true 那一行有入口；两行都有说明后端字段被忽略
    expect(cancelButtons).toHaveLength(1);
  });

  it('③ 电话掩码只在详情页展示：列表不渲染电话列，原始号码不得泄漏到 DOM', async () => {
    const wrapper = await mountView();

    const text = wrapper.text();
    // 期望原始号码不出现：列表页拿到的字段里带原始值，但页面不得直接渲染它
    expect(text).not.toContain('13800138000');
    // 期望状态与紧急度的真实渲染：这两列是列表页的核心信息（弱断言不依赖 EP 内部结构）
    expect(text).toContain('待派单');
    expect(text).toContain('加急');
    expect(text).toContain('普通');
  });

  it('④ 点击取消 → 弹窗 → 二次确认 → POST /work-orders/101/cancel-by-reporter', async () => {
    const wrapper = await mountView();

    const cancelButton = wrapper.findAll('button').find((button) => button.text().trim() === '取消');
    expect(cancelButton, 'canCancel=true 的行没有渲染「取消」入口').toBeTruthy();
    await cancelButton!.trigger('click');
    await flushPromises();
    await nextTick();

    // 弹窗里的「确认取消」：先在组件树内找，再兜底到 teleport 后的 body
    const confirmButton = findButton(wrapper, '确认取消');
    expect(confirmButton, '取消弹窗没有渲染出「确认取消」按钮').toBeTruthy();
    confirmButton!.click();
    await flushPromises();
    await nextTick();

    // 期望打的是被点中那一行的 id，而且走的是「报修人自助取消」这个专用端点
    expect(apiMocks.post).toHaveBeenCalledTimes(1);
    const [url, payload] = apiMocks.post.mock.calls[0] as [string, { reason?: string }];
    expect(url).toBe('/work-orders/101/cancel-by-reporter');
    // 期望原因为空时提交 undefined（后端按「报修人自行取消」记录），不是空字符串
    expect(payload.reason).toBeUndefined();
  });
});
