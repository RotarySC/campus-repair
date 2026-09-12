<template>
  <div class="personal-center">
    <el-row :gutter="14">
      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header><span class="block-title">基本联系资料</span></template>
          <el-form :model="profileForm" :label-width="isNarrowPhone ? undefined : '110px'" :label-position="isNarrowPhone ? 'top' : 'right'">
            <el-form-item label="账号">{{ authStore.userInfo?.username }}</el-form-item>
            <el-form-item label="身份">
              <el-tag>{{ roleLabel }}</el-tag>
            </el-form-item>
            <el-form-item label="姓名">
              <el-input v-model="profileForm.realName" placeholder="真实姓名" />
            </el-form-item>
            <el-form-item label="手机号码">
              <el-input v-model="profileForm.phone" placeholder="常用联系电话" />
            </el-form-item>
            <el-form-item label="学院/部门">
              <el-input v-model="profileForm.department" placeholder="所在单位" />
            </el-form-item>
            <el-form-item label="默认报修空间">
              <el-cascader
                v-model="spacePath"
                :options="spaceOptions"
                :props="cascaderProps"
                placeholder="选择默认宿舍/办公房间"
                style="width: 100%"
                clearable
                @change="onSpaceChange"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="profileLoading" @click="onUpdateProfile">保存资料修改</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header><span class="block-title">安全密码修改</span></template>
          <el-form :model="passwordForm" :label-width="isNarrowPhone ? undefined : '110px'" :label-position="isNarrowPhone ? 'top' : 'right'">
            <el-form-item label="原密码">
              <el-input v-model="passwordForm.oldPassword" type="password" show-password placeholder="请输入当前密码" />
            </el-form-item>
            <el-form-item label="新密码">
              <el-input v-model="passwordForm.newPassword" type="password" show-password placeholder="请输入新密码" />
            </el-form-item>
            <el-form-item>
              <el-button type="danger" plain :loading="passwordLoading" @click="onChangePassword">确认修改密码</el-button>
            </el-form-item>
          </el-form>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="修改密码成功后将退出登录，请使用新密码重新登录。"
          />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useAuthStore, httpClient } from '../../stores/auth';
import { useViewport } from '../../composables/useViewport';

const { isNarrowPhone } = useViewport();

const router = useRouter();
const authStore = useAuthStore();

const profileLoading = ref(false);
const passwordLoading = ref(false);
const spaceOptions = ref<any[]>([]);
const spacePath = ref<number[]>([]);

const cascaderProps = { value: 'id', label: 'name', children: 'children', emitPath: true };

const profileForm = reactive({
  realName: '',
  phone: '',
  department: '',
  defaultSpaceId: null as number | null
});

const passwordForm = reactive({
  oldPassword: '',
  newPassword: ''
});

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: '超级管理员',
  LOGISTICS_ADMIN: '后勤管理员',
  REPAIR_WORKER: '维修师傅',
  USER_STUDENT: '学生',
  USER_TEACHER: '教工'
};

const roleLabel = computed(() => {
  const userType = authStore.userInfo?.userType || '';
  return ROLE_LABELS[userType] || userType || '未分配角色';
});

function findIdPath(list: any[], id: number, prefix: number[] = []): number[] | null {
  for (const node of list) {
    const curPath = [...prefix, node.id];
    if (node.id === id) return curPath;
    if (node.children) {
      const r = findIdPath(node.children, id, curPath);
      if (r) return r;
    }
  }
  return null;
}

function onSpaceChange() {
  profileForm.defaultSpaceId = spacePath.value.length
    ? spacePath.value[spacePath.value.length - 1]
    : null;
}

async function loadSpaceOptions() {
  try {
    const res = await httpClient.get<any[]>('/spatial/tree', { params: { onlyEnabled: true } });
    if (res.code === 200) {
      spaceOptions.value = res.data || [];
      if (profileForm.defaultSpaceId) {
        spacePath.value = findIdPath(spaceOptions.value, profileForm.defaultSpaceId) || [];
      }
    }
  } catch (_) {}
}

async function onUpdateProfile() {
  profileLoading.value = true;
  try {
    const payload = {
      ...profileForm,
      defaultSpaceId: profileForm.defaultSpaceId ? Number(profileForm.defaultSpaceId) : null
    };
    const res = await httpClient.put<null>('/users/profile', payload);
    if (res.code === 200) {
      ElMessage.success('资料保存成功');
      if (authStore.userInfo) {
        authStore.userInfo.realName = profileForm.realName;
        authStore.userInfo.phone = profileForm.phone;
        authStore.userInfo.department = profileForm.department;
        if (payload.defaultSpaceId) authStore.userInfo.defaultSpaceId = payload.defaultSpaceId;
        localStorage.setItem('user_info', JSON.stringify(authStore.userInfo));
      }
    } else {
      ElMessage.error(res.message || '更新失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '更新出错');
  } finally {
    profileLoading.value = false;
  }
}

async function onChangePassword() {
  if (!passwordForm.oldPassword || !passwordForm.newPassword) {
    ElMessage.warning('原密码与新密码均不能为空');
    return;
  }
  passwordLoading.value = true;
  try {
    const res = await httpClient.put<null>('/users/password/change', passwordForm);
    if (res.code === 200) {
      ElMessage.success('密码修改成功，请重新登录');
      authStore.logout();
      router.push('/login');
    } else {
      ElMessage.error(res.message || '修改密码失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '修改出错');
  } finally {
    passwordLoading.value = false;
  }
}

onMounted(() => {
  if (authStore.userInfo) {
    profileForm.realName = authStore.userInfo.realName || '';
    profileForm.phone = authStore.userInfo.phone || '';
    profileForm.department = authStore.userInfo.department || '';
    profileForm.defaultSpaceId = authStore.userInfo.defaultSpaceId || null;
  }
  loadSpaceOptions();
});
</script>

<style scoped>
.personal-center {
  padding: 16px;
}
.block-title {
  font-weight: 600;
}
</style>
