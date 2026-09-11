<template>
  <div class="admin-shell" :class="{ 'sidebar-collapsed': collapsed }">
    <!-- 固定左侧边栏 -->
    <aside class="sidebar" aria-label="主导航">
      <div class="brand">
        <el-icon :size="24" color="#1677FF" class="brand-icon"><Management /></el-icon>
        <span v-show="!collapsed" class="brand-text">校园设施报修运维平台</span>
      </div>

      <nav class="nav" aria-label="功能导航">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ 'is-active': isActive(item.path) }"
          :aria-current="isActive(item.path) ? 'page' : undefined"
          :title="collapsed ? item.label : undefined"
        >
          <el-icon :size="18" class="nav-icon"><component :is="item.icon" /></el-icon>
          <span v-show="!collapsed" class="nav-label">{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="sidebar-footer">
        <button
          type="button"
          class="collapse-btn"
          :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'"
          :aria-expanded="!collapsed"
          @click="toggleCollapse"
        >
          <el-icon :size="18"><Expand v-if="collapsed" /><Fold v-else /></el-icon>
          <span v-show="!collapsed">收起侧边栏</span>
        </button>
      </div>
    </aside>

    <!-- 右侧内容区：顶部条固定，仅页面内容滚动 -->
    <div class="content-area">
      <header class="topbar">
        <h1 class="page-title">{{ pageTitle }}</h1>

        <div class="topbar-actions">
          <el-badge :value="unreadCount" :max="99" :hidden="unreadCount === 0">
            <el-button
              :icon="Bell"
              circle
              aria-label="打开站内消息通知中心"
              title="站内消息通知中心"
              @click="showNotificationDrawer = true"
            />
          </el-badge>

          <el-dropdown trigger="click" @command="onUserCommand">
            <button type="button" class="user-chip" aria-label="账号菜单">
              <el-avatar :size="28" class="user-avatar">{{ userInitial }}</el-avatar>
              <span class="user-meta">
                <span class="user-name">{{ authStore.userInfo?.realName || authStore.userInfo?.username }}</span>
                <span class="user-role">{{ roleLabel }}</span>
              </span>
              <el-icon :size="12"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="page-scroll">
        <router-view />
      </main>
    </div>

    <!-- 全局唯一的站内消息抽屉（原先在多个视图里各写了一份） -->
    <el-drawer
      v-model="showNotificationDrawer"
      title="站内消息通知中心"
      size="420px"
      direction="rtl"
    >
      <NotificationCenterDrawer @unread-updated="fetchUnreadCount" @select-order="handleSelectOrder" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Bell, ArrowDown, SwitchButton } from '@element-plus/icons-vue';
import { useAuthStore, httpClient } from '../stores/auth';
import NotificationCenterDrawer from '../components/NotificationCenterDrawer.vue';
import { ApiResponse } from '@campus/shared';

interface NavItem {
  path: string;
  label: string;
  icon: string;
}

/** 导航顺序按使用频次与重要性排列：日常入口在上，只读看板与大屏在下 */
const navItems: NavItem[] = [
  { path: '/', label: '控制台概览', icon: 'Odometer' },
  { path: '/dispatch', label: '后勤调度中心', icon: 'Headset' },
  { path: '/reports/orders', label: '工单检索报表', icon: 'Document' },
  { path: '/inventory', label: '耗材进销存台账', icon: 'Box' },
  { path: '/spatial', label: '空间与分类字典', icon: 'Grid' },
  { path: '/system/users', label: '用户台账管理', icon: 'User' },
  { path: '/cockpit', label: '运营指挥大屏', icon: 'DataLine' }
];

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: '超级管理员',
  LOGISTICS_ADMIN: '后勤管理员',
  REPAIR_WORKER: '维修师傅',
  USER_STUDENT: '学生',
  USER_TEACHER: '教工'
};

const COLLAPSE_STORAGE_KEY = 'admin_sidebar_collapsed';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();

const showNotificationDrawer = ref(false);
const unreadCount = ref(0);
const collapsed = ref(localStorage.getItem(COLLAPSE_STORAGE_KEY) === '1');

const pageTitle = computed(() => (route.meta.title as string) || '控制台概览');
const roleLabel = computed(() => {
  const userType = authStore.userInfo?.userType || '';
  return ROLE_LABELS[userType] || userType || '未分配角色';
});
const userInitial = computed(() => {
  const name = authStore.userInfo?.realName || authStore.userInfo?.username || '—';
  return name.slice(0, 1);
});

function isActive(path: string): boolean {
  return path === '/' ? route.path === '/' : route.path.startsWith(path);
}

function toggleCollapse() {
  collapsed.value = !collapsed.value;
  localStorage.setItem(COLLAPSE_STORAGE_KEY, collapsed.value ? '1' : '0');
}

function onUserCommand(command: string) {
  if (command === 'logout') {
    authStore.logout();
    router.push('/login');
  }
}

/** 通知中携带关联工单时，跳到调度中心并由该页打开审计时间轴（承接原先视图内抽屉的行为） */
function handleSelectOrder(orderId: number) {
  showNotificationDrawer.value = false;
  if (orderId) {
    router.push({ path: '/dispatch', query: { orderId: String(orderId) } });
  }
}

async function fetchUnreadCount() {
  try {
    const res = await httpClient.get<any, ApiResponse<number>>('/notifications/unread-count');
    if (res.code === 200 && typeof res.data === 'number') {
      unreadCount.value = res.data;
    }
  } catch (e) {
    // 未读数为非关键信息，失败时静默降级
  }
}

onMounted(() => {
  fetchUnreadCount();
});
</script>

<style scoped>
.admin-shell {
  --sidebar-w: 240px;
  --sidebar-w-collapsed: 64px;
  --topbar-h: 56px;

  min-height: 100vh;
  background-color: #f5f7fa;
}

/* ---------- 固定侧边栏 ---------- */
.sidebar {
  position: fixed;
  inset-block: 0;
  inset-inline-start: 0;
  z-index: 20;
  /* 含 1px 右边框，保证宽度严格等于内容区偏移量，避免 1px 错位 */
  box-sizing: border-box;
  width: var(--sidebar-w);
  display: flex;
  flex-direction: column;
  background-color: #ffffff;
  border-inline-end: 1px solid #e5e7eb;
  transition: width 0.2s ease;
  overflow: hidden;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  /* 含 1px 下边框，与顶栏同高，两条分隔线落在同一水平线上 */
  box-sizing: border-box;
  height: var(--topbar-h);
  padding-inline: 24px;
  border-block-end: 1px solid #f0f2f5;
  flex-shrink: 0;
}

.brand-text {
  font-size: 15px;
  font-weight: 600;
  color: #1f2937;
  white-space: nowrap;
}

.nav {
  flex: 1;
  overflow-y: auto;
  /* 与 .brand 的 padding-inline 一致，保证品牌图标与导航图标共享同一对齐边 */
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  height: 44px;
  padding-inline: 12px;
  border-radius: 8px;
  color: #4b5563;
  text-decoration: none;
  white-space: nowrap;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.nav-item:hover {
  background-color: #f5f7fa;
  color: #1677ff;
}

.nav-item.is-active {
  background-color: #eaf2ff;
  color: #1677ff;
  font-weight: 600;
}

/* 活动项用前缘色条强化定位，避免只靠颜色区分 */
.nav-item.is-active::before {
  content: '';
  position: absolute;
  inset-inline-start: 0;
  inset-block: 10px;
  width: 3px;
  border-radius: 0 2px 2px 0;
  background-color: #1677ff;
}

.nav-icon {
  flex-shrink: 0;
}

.nav-label {
  font-size: 14px;
}

.sidebar-footer {
  flex-shrink: 0;
  padding: 10px;
  border-block-start: 1px solid #f0f2f5;
}

.collapse-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 40px;
  border: none;
  border-radius: 8px;
  background-color: transparent;
  color: #6b7280;
  font-size: 13px;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.collapse-btn:hover {
  background-color: #f5f7fa;
  color: #1677ff;
}

/* ---------- 内容区 ---------- */
.content-area {
  margin-inline-start: var(--sidebar-w);
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  transition: margin-inline-start 0.2s ease;
}

.sidebar-collapsed .sidebar {
  width: var(--sidebar-w-collapsed);
}

.sidebar-collapsed .brand {
  padding-inline: 0;
  justify-content: center;
}

.sidebar-collapsed .nav-item {
  justify-content: center;
  padding-inline: 0;
}

.sidebar-collapsed .content-area {
  margin-inline-start: var(--sidebar-w-collapsed);
}

.topbar {
  position: sticky;
  inset-block-start: 0;
  z-index: 10;
  /* 含 1px 下边框，避免整页出现 1px 溢出滚动条 */
  box-sizing: border-box;
  height: var(--topbar-h);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-inline: 24px;
  background-color: #ffffff;
  border-block-end: 1px solid #e5e7eb;
}

.page-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: #1f2937;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding-inline: 8px;
  border: none;
  border-radius: 8px;
  background-color: transparent;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.user-chip:hover {
  background-color: #f5f7fa;
}

.user-avatar {
  background-color: #1677ff;
  color: #ffffff;
  font-size: 13px;
}

.user-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.2;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: #1f2937;
}

.user-role {
  font-size: 11px;
  color: #6b7280;
}

.page-scroll {
  flex: 1;
  min-height: 0;
}
</style>
