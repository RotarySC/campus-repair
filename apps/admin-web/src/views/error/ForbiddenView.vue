<template>
  <div class="forbidden-page">
    <el-card shadow="never" class="forbidden-card">
      <!--
        §4.6 的 A7（旧编号 V7）：`el-result icon="warning"` 换成矢量线稿。
        el-result 的槽位（icon / title / sub-title / #extra）只用到前三档，
        而第一档要换成自己的图，留着它反而要和它的内部布局较劲 —— 直接铺开。
      -->
      <div class="forbidden-head">
        <ForbiddenArt class="forbidden-art" />
        <h1 class="forbidden-head__title">无权访问该页面</h1>
        <p class="forbidden-head__sub">当前账号的角色没有访问此功能的权限</p>
      </div>

      <div class="account-box">
        <p>
          当前登录账号：<strong>{{ authStore.userInfo?.realName || authStore.userInfo?.username }}</strong>
        </p>
        <p>
          账号职能：<MetaChip>{{ functionText }}</MetaChip>
        </p>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="tips"
        title="说明"
        description="调度、报表、台账等管理功能仅对超级管理员与后勤管理员开放。师生可使用侧边栏「提交报修 / 我的工单」，维修师傅的接单作业请在手机上打开本系统地址（界面将自动适配为移动端）。"
      />

      <div class="actions">
        <el-button type="primary" @click="handleLogout">退出登录并切换账号</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import MetaChip from '../../components/MetaChip.vue';
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { functionLabel } from '@campus/shared';
import { useAuthStore } from '../../stores/auth';
import ForbiddenArt from '../../components/ForbiddenArt.vue';

const router = useRouter();
const authStore = useAuthStore();

const functionText = computed(() => {
  const codes: string[] = authStore.userInfo?.roleCodes ?? [];
  return codes.map((c: string) => functionLabel(c)).join('、') || '未分配职能';
});

function handleLogout() {
  authStore.logout();
  router.push('/login');
}
</script>

<style scoped>
.forbidden-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--canvas);
  padding: 24px;
}

.forbidden-card {
  width: 100%;
  max-width: 560px;
  border-radius: var(--radius-card);
  /*
   * ⚠️ 这一行是**补回来的**，不是新加的样式。
   *
   * 原先整页在 `<el-result>` 里，而 EP 的 `.el-result` 容器是 `text-align: center`——
   * 标题、副标题、账号信息、说明条**文字**、底部按钮全都是居中的。
   * 拆掉 el-result 换线稿时如果只给页头写 `text-align: center`，
   * 标题居中了、下面整块却回到左对齐，页面会读成「两套版式叠在一起」。
   *
   * 证据就在本文件里：`.tips { text-align: start }` 这条规则**只有在祖先是居中时才有意义**
   * ——它本来就是用来把说明条的文字从居中扳回左对齐的。按那条规则反推，祖先是居中的。
   */
  text-align: center;
}

/* 页头：线稿 + 标题 + 副标题，居中堆叠（原 el-result 的默认排布） */
.forbidden-head {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  margin-bottom: 20px;
}

/*
 * 线稿取 --faint（.5）而不是 --ink：它是**装饰**，不该和标题抢视觉重心。
 * 全站唯一允许出现的「重颜色」仍然是 9px 状态圆环（§3.2）。
 */
.forbidden-art {
  color: var(--faint);
  margin-bottom: 14px;
}

.forbidden-head__title {
  margin: 0;
  font-size: var(--fs-xl);
  font-weight: var(--fw-medium);
  color: var(--ink);
}

.forbidden-head__sub {
  margin: 6px 0 0;
  font-size: var(--fs-base);
  font-weight: var(--fw-normal);
  color: var(--muted);
}

.account-box {
  margin-bottom: 16px;
}

.account-box p {
  margin: 6px 0;
  font-size: 14px;
  color: var(--muted);
}

.tips {
  text-align: start;
}

.actions {
  margin-top: 24px;
}
</style>
