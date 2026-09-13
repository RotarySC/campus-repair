<template>
  <div class="register-page">
    <header class="register-header">
      <button type="button" class="back-btn" aria-label="返回登录" @click="router.push('/login')">
        <el-icon :size="18"><ArrowLeft /></el-icon>
        <span>返回登录</span>
      </button>
    </header>

    <div class="register-card">
      <div class="banner">
        <h3>创建智慧报修账号</h3>
        <p>面向在校学生与教职工开通自主报修服务</p>
      </div>

      <el-form
        ref="registerFormRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="register-form"
        @submit.prevent
      >
        <el-form-item label="学号/工号" prop="username">
          <el-input v-model="form.username" placeholder="请输入本人学号或教工号" />
        </el-form-item>
        <el-form-item label="真实姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="用于报修现场核验身份" />
        </el-form-item>
        <el-form-item label="手机号码" prop="phone">
          <el-input v-model="form.phone" placeholder="用于维修师傅一键电话联络" />
        </el-form-item>
        <el-form-item label="登录密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="请设置登录密码" />
        </el-form-item>
        <el-form-item label="学院/部门" prop="department">
          <el-input v-model="form.department" placeholder="例如：软件学院 / 计算机系" />
        </el-form-item>
        <el-form-item label="身份类型">
          <el-radio-group v-model="identityType">
            <el-radio value="STUDENT">在校学生</el-radio>
            <el-radio value="STAFF">教职员工</el-radio>
          </el-radio-group>
        </el-form-item>
        <!-- 教工必须选所属条线：教学科研 与 后勤保障 的单位是两棵不同的字典子树 -->
        <el-form-item v-if="identityType === 'STAFF'" label="所属条线">
          <el-radio-group v-model="staffBranch">
            <el-radio value="FACULTY">教学科研（学院/系）</el-radio>
            <el-radio value="LOGISTICS">后勤保障（处室/班组）</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="register-tip"
          title="维修师傅、后勤调度与平台管理账号由管理员在「用户台账」建档，不开放自助注册。"
        />

        <el-button
          type="primary"
          size="large"
          class="submit-btn"
          :loading="loading"
          @click="onSubmit"
        >
          确认注册并激活
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { ArrowLeft } from '@element-plus/icons-vue';
import { httpClient } from '../../stores/auth';

const router = useRouter();
const loading = ref(false);
const registerFormRef = ref<FormInstance>();

/** 身份（学生/教职工）——注册页的表达层；服务端只收「组织条线」 */
const identityType = ref<'STUDENT' | 'STAFF'>('STUDENT');
/** 教工所属条线（教学科研 / 后勤保障）；行政信息化不开放自助注册 */
const staffBranch = ref<'FACULTY' | 'LOGISTICS'>('FACULTY');

const form = reactive({
  username: '',
  realName: '',
  phone: '',
  password: '',
  department: ''
});

/** 提交给服务端的组织条线（STUDENT / FACULTY / LOGISTICS，白名单见后端 IdentityCodes.SELF_REGISTRABLE_BRANCHES） */
function currentBranch(): string {
  return identityType.value === 'STUDENT' ? 'STUDENT' : staffBranch.value;
}

const rules: FormRules = {
  username: [{ required: true, message: '请填写学号或工号', trigger: 'blur' }],
  realName: [{ required: true, message: '请填写真实姓名', trigger: 'blur' }],
  phone: [
    { required: true, message: '请填写手机号码', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不合法', trigger: 'blur' }
  ],
  password: [{ required: true, message: '请设置登录密码', trigger: 'blur' }]
};

async function onSubmit() {
  const valid = await registerFormRef.value?.validate().catch(() => false);
  if (!valid) return;

  loading.value = true;
  try {
    const res = await httpClient.post<null>('/auth/register', { ...form, userType: currentBranch() });
    if (res.code === 200) {
      ElMessage.success('注册成功，请使用新密码登录');
      router.push('/login');
    } else {
      ElMessage.error(res.message || '注册失败');
    }
  } catch (err: any) {
    ElMessage.error(err.response?.data?.message || err.message || '网络异常');
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.register-page {
  min-height: 100vh;
  background-color: #F7F8FA;
}

.register-header {
  display: flex;
  align-items: center;
  height: 48px;
  padding-inline: 8px;
  background-color: #FFFFFF;
  border-bottom: 1px solid #EBEDF0;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 36px;
  padding-inline: 8px;
  border: none;
  border-radius: 8px;
  background-color: transparent;
  color: #4B5563;
  font-size: 13px;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.back-btn:hover {
  background-color: #F5F7FA;
  color: #1677FF;
}

.register-card {
  max-width: 560px;
  margin-inline: auto;
  padding: 16px;
}

.banner {
  padding: 20px 4px;
}

.banner h3 {
  margin: 0;
  font-size: 20px;
  color: #1F2937;
}

.banner p {
  margin: 6px 0 0;
  font-size: 13px;
  color: #6B7280;
}

.register-form {
  background-color: #FFFFFF;
  border-radius: 10px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
  padding: 20px 16px 24px;
}

.submit-btn {
  width: 100%;
  min-height: 44px;
  margin-top: 8px;
}
</style>
