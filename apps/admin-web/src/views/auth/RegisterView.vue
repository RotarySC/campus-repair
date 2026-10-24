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
        v-if="!registerResult"
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
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="8~32 位，需同时包含字母与数字"
          />
        </el-form-item>
        <!-- 身份/条线**必须排在「学院/部门」之前**：单位字典按条线剪枝，
             部门选项集合依赖这两个选择。放在下面会让选项在用户眼皮底下整批换掉。 -->
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
        <!-- 单位由自由文本改为**字典下钻**（原自由文本会让 sys_user.department 存进一个
             字典里没有的串，个人中心只能显示「不在字典中」）。提交值仍是 full_path_name
             原串（空格分隔），与库中口径逐字符一致。 -->
        <el-form-item label="学院/部门" prop="department">
          <DrilldownCascader
            v-model="orgPath"
            :options="orgOptions"
            :placeholder="orgPlaceholder"
            label="学院/部门"
            separator=" "
            level-icon-kind="org"
            @change="onOrgChange"
          />
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

      <!--
        注册结果两态（Phase 01）：提交后不再一律 toast + 跳登录。
        A）名单命中，自动通过 → 告知可直接登录；
        B）待核验 → 就地显示原因与学号，让用户知道「为什么还不能提单」。
        结果态跟表单同宽同卡片长相，避免从表单突然跳到一个孤零零的提示条。
      -->
      <section v-else class="register-form register-result" role="status" aria-live="polite">
        <div class="result-head">
          <StatusRing v-if="isPending" variant="dot" tone="muted" label="待核验" />
          <h3>{{ isPending ? '注册已提交，等待管理员核验' : '注册成功' }}</h3>
        </div>

        <p class="result-desc">
          <template v-if="isPending">
            账号已创建，但校内身份还需管理员确认。核验通过后即可提交报修申请。
          </template>
          <template v-else>名单核验已通过，请使用刚设置的密码登录。</template>
        </p>

        <!-- 待核验原因：中性提示块（不用 el-alert 的语义色，发送给用户的是一条解释而非警报） -->
        <div v-if="isPending" class="result-reason">
          <span class="result-reason__label">待核验原因</span>
          <span class="result-reason__text">{{ pendingReasonText }}</span>
        </div>

        <dl class="result-meta">
          <div class="result-meta__row">
            <dt>学号/工号</dt>
            <dd>{{ form.username }}</dd>
          </div>
          <div class="result-meta__row">
            <dt>真实姓名</dt>
            <dd>{{ form.realName }}</dd>
          </div>
          <div class="result-meta__row">
            <dt>账号状态</dt>
            <dd>{{ AccountStatusLabels[registerResult.status] || '待核验' }}</dd>
          </div>
          <div class="result-meta__row">
            <dt>名单比对</dt>
            <dd>{{ IdentityMatchResultLabels[registerResult.matchResult] || registerResult.matchResult }}</dd>
          </div>
        </dl>

        <div class="result-actions">
          <el-button type="primary" size="large" class="submit-btn" @click="router.push('/login')">
            返回登录
          </el-button>
          <el-button v-if="isPending" size="large" @click="registerResult = null">
            修改资料重新提交
          </el-button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { ArrowLeft } from '@element-plus/icons-vue';
import { httpClient } from '../../stores/auth';
import {
  AccountStatusLabels,
  BranchOrgPlaceholders,
  IdentityMatchResultLabels,
  PASSWORD_PATTERN,
  PASSWORD_RULE_MESSAGE
} from '@campus/shared';
import type { OrgBranch, OrgNodeVO, RegisterResultVO } from '@campus/shared';
import DrilldownCascader from '../../components/DrilldownCascader.vue';
import StatusRing from '../../components/StatusRing.vue';

const router = useRouter();
const loading = ref(false);
const registerFormRef = ref<FormInstance>();

/**
 * 注册结果（Phase 01）。`null` = 还在填表；非空 = 展示两态结果页。
 * `status=1 & identityVerified=true` 为自动通过，其余一律按「待核验」展示
 * （宁可多给一条核验说明，也不要把未核验账号误报成注册完成）。
 */
const registerResult = ref<RegisterResultVO | null>(null);
const isPending = computed(
  () => !(registerResult.value?.status === 1 && registerResult.value?.identityVerified === true)
);
const pendingReasonText = computed(
  () => registerResult.value?.pendingReason || '未在预导入名单中找到该学号/工号，需管理员人工核验'
);

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

/**
 * 单位字典：注册页**没有登录态**，audience 只能取自表单里已选的条线。
 * 取值仍是同一个值、不做二次映射（见 CONTEXT.md「组织条线」）。
 */
const orgAudience = computed<OrgBranch>(() => currentBranch() as OrgBranch);
const orgPlaceholder = computed(() => BranchOrgPlaceholders[orgAudience.value] ?? '选择所属单位');
const orgOptions = ref<OrgNodeVO[]>([]);
const orgPath = ref<number[]>([]);

/** 沿 id 路径取末端节点，把选中路径写回 department 文案（提交的是 full_path_name 原串） */
function orgNodeByPath(list: OrgNodeVO[], ids: number[]): OrgNodeVO | null {
  let level = list;
  let hit: OrgNodeVO | null = null;
  for (const id of ids) {
    hit = level.find((node) => node.id === id) || null;
    if (!hit) return null;
    level = hit.children || [];
  }
  return hit;
}

async function loadOrgOptions() {
  try {
    // **不能用 `/org/tree`**：那是登录后的端点（未登录 401），而注册页没有登录态。
    // 服务端为此开了同内容但**更窄**的匿名端点——只返回可自助注册的三条条线
    // （不含行政信息化），见 AuthController#registerOrgOptions。
    const res = await httpClient.get<OrgNodeVO[]>('/auth/register-org-options', {
      params: { audience: orgAudience.value }
    });
    orgOptions.value = res.code === 200 ? res.data || [] : [];
  } catch (_) {
    orgOptions.value = [];
  }
}

/**
 * 换身份/换条线 = 换了一棵树，旧选择在新树里必然不成立，**必须一起清掉**。
 * 不清会提交出「学生年级的单位挂在后勤账号上」这类脏值。
 * 三处（orgPath、form.department、选择器显示）必须同步清——漏一处就会「表单看着是空的、
 * 提交上去却有值」。
 */
watch(orgAudience, () => {
  orgPath.value = [];
  form.department = '';
  loadOrgOptions();
});

function onOrgChange() {
  form.department = orgPath.value.length
    ? orgNodeByPath(orgOptions.value, orgPath.value)?.fullPathName || ''
    : '';
}

onMounted(loadOrgOptions);

const rules: FormRules = {
  username: [{ required: true, message: '请填写学号或工号', trigger: 'blur' }],
  realName: [{ required: true, message: '请填写真实姓名', trigger: 'blur' }],
  phone: [
    { required: true, message: '请填写手机号码', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不合法', trigger: 'blur' }
  ],
  // 与服务端 RegisterRequest 的 @Size + @Pattern **必须同步**；规则常量取自 shared，
  // 与改密路径（PasswordChangeRequest / PersonalCenterView）共用同一个口径
  password: [
    { required: true, message: '请设置登录密码', trigger: 'blur' },
    { pattern: PASSWORD_PATTERN, message: PASSWORD_RULE_MESSAGE, trigger: 'blur' }
  ]
};

async function onSubmit() {
  const valid = await registerFormRef.value?.validate().catch(() => false);
  if (!valid) return;

  loading.value = true;
  try {
    const res = await httpClient.post<RegisterResultVO>('/auth/register', {
      ...form,
      userType: currentBranch()
    });
    if (res.code === 200 && res.data) {
      // 结果两态由页面承载，不再「toast 成功 + 一律跳登录」：
      // 待核验账号必须先看到原因，否则登录后撞上 1010 只会一脸问号。
      registerResult.value = res.data;
    } else if (res.code === 200) {
      // 过渡期兼容：旧后端仍返回 R<Void>，退回原来的「成功 + 去登录」
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
  background-color: var(--canvas);
}

.register-header {
  display: flex;
  align-items: center;
  height: 48px;
  padding-inline: 8px;
  background-color: var(--surface);
  border-bottom: var(--hairline) solid var(--line);
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 36px;
  padding-inline: 8px;
  border: none;
 /* 圆角由 8px 收到 --radius（圆角 0–2px） */
  border-radius: var(--radius);
  background-color: transparent;
  color: var(--muted);
  font-size: 13px;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.back-btn:hover {
  background-color: var(--canvas);
  color: var(--el-color-primary);
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
  font-size: var(--fs-xl);
  color: var(--ink);
}

.banner p {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--muted);
}

.register-form {
  background-color: var(--surface);
 /* 圆角 10px → 0（卡片圆角是 0）；
     自造阴影 `0 1px 4px rgba(0,0,0,.04)` 按 V15 删除，层级改用发丝线边框 */
  border-radius: var(--radius-card);
  border: var(--hairline) solid var(--line);
  padding: 20px 16px 24px;
}

.submit-btn {
  width: 100%;
  min-height: 44px;
  margin-top: 8px;
}

/* ---------- 注册结果两态（Phase 01） ---------- */
.register-result {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 24px 20px 28px;
}

.result-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.result-head h3 {
  margin: 0;
  font-size: var(--fs-xl);
  font-weight: var(--fw-medium);
  color: var(--ink);
}

.result-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--muted);
}

.result-meta {
  margin: 0;
  padding-top: 12px;
  border-top: var(--hairline) solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.result-reason {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: var(--hairline) solid var(--line);
  border-radius: var(--radius-card);
  background-color: var(--canvas);
}

.result-reason__label {
  font-size: var(--fs-base);
  color: var(--faint);
}

.result-reason__text {
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink);
}
.result-meta__row {
  display: flex;
  gap: 10px;
  font-size: 13px;
}

.result-meta dt {
  flex: 0 0 84px;
  color: var(--muted);
}

.result-meta dd {
  margin: 0;
  color: var(--ink);
  word-break: break-all;
}

.result-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.result-actions .submit-btn {
  width: auto;
  flex: 1 1 180px;
  margin-top: 0;
}
</style>
