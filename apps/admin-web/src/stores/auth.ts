import { defineStore } from 'pinia';
import { UserInfo, LoginResult } from '@campus/shared';
import type { AccountStatus } from '@campus/shared';
import { createHttpClient } from '@campus/shared';

/** 身份核验状态的本地持久化键（Phase 01）；与登录三件套一起清理 */
const IDENTITY_VERIFIED_KEY = 'identity_verified';
const ACCOUNT_STATUS_KEY = 'account_status';

/**
 * 读取本地核验状态。**缺省按「已核验 / 正常」**——与后端「存量账号回填
 * `identity_verified=1`」同口径，也避免升级前建立的会话被误跳到 `/pending`。
 */
function readIdentityVerified(): boolean {
  const raw = localStorage.getItem(IDENTITY_VERIFIED_KEY);
  return raw === null ? true : raw !== 'false';
}

function readAccountStatus(): AccountStatus {
  const raw = Number(localStorage.getItem(ACCOUNT_STATUS_KEY));
  return raw === 0 || raw === 2 ? raw : 1;
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: localStorage.getItem('access_token') || '',
    refreshToken: localStorage.getItem('refresh_token') || '',
    userInfo: JSON.parse(localStorage.getItem('user_info') || 'null') as UserInfo | null,
    /**
     * 是否已通过身份核验（Phase 01）。
     * 与 `accountStatus` 分开存：`2` 待核验 / `0` 停用是「账号能不能用」，
     * `identityVerified` 是「身份核验过没有」——页面与路由守卫各取所需。
     */
    identityVerified: readIdentityVerified(),
    /** 账号三态：`0` 停用 / `1` 正常 / `2` 待核验 */
    accountStatus: readAccountStatus() as AccountStatus
  }),
  getters: {
    isAuthenticated: (state) => !!state.accessToken,
    /** 路由守卫用：未核验或处于待核验三态时应落 `/pending` */
    needsIdentityReview: (state) => !state.identityVerified || state.accountStatus === 2
  },
  actions: {
    setAuth(loginResult: LoginResult) {
      this.accessToken = loginResult.accessToken;
      this.refreshToken = loginResult.refreshToken;
      this.userInfo = loginResult.userInfo;

      // Phase 01：登录响应在 data 顶层平铺 identityVerified/accountStatus；
      // 旧响应没有这两个字段时回退到 userInfo 上的同源字段，再回退到「已核验/正常」。
      this.identityVerified =
        loginResult.identityVerified ?? loginResult.userInfo?.identityVerified ?? true;
      this.accountStatus = loginResult.accountStatus ?? loginResult.userInfo?.status ?? 1;

      localStorage.setItem('access_token', loginResult.accessToken);
      localStorage.setItem('refresh_token', loginResult.refreshToken);
      localStorage.setItem('user_info', JSON.stringify(loginResult.userInfo));
      this.persistIdentityState();
    },
    /**
     * 用 `GET /users/me` 的完整资料刷新登录快照（个人中心等页面进入时调用）。
     * 只覆盖字段齐备的数据；核验字段缺失时保留当前值（旧后端过渡期）。
     */
    setUserInfo(userInfo: UserInfo) {
      this.userInfo = userInfo;
      if (typeof userInfo.identityVerified === 'boolean') {
        this.identityVerified = userInfo.identityVerified;
      }
      if (userInfo.status === 0 || userInfo.status === 1 || userInfo.status === 2) {
        this.accountStatus = userInfo.status;
      }
      localStorage.setItem('user_info', JSON.stringify(userInfo));
      this.persistIdentityState();
    },
    /**
     * 刷新令牌（`POST /auth/refresh-token`）也回传 `identityVerified/accountStatus`，
     * 但 `packages/shared/src/api/http.ts` 的换票拦截器目前只消费 tokens（该文件不在
     * Phase 01 前端写权限内）。接上换票路径时调用本 action 即可，避免仅靠本地快照判断。
     */
    setIdentityState(identityVerified: boolean, accountStatus: AccountStatus) {
      this.identityVerified = identityVerified;
      this.accountStatus = accountStatus;
      this.persistIdentityState();
    },
    persistIdentityState() {
      localStorage.setItem(IDENTITY_VERIFIED_KEY, String(this.identityVerified));
      localStorage.setItem(ACCOUNT_STATUS_KEY, String(this.accountStatus));
    },
    updateTokens(accessToken: string, refreshToken: string) {
      this.accessToken = accessToken;
      this.refreshToken = refreshToken;
      localStorage.setItem('access_token', accessToken);
      localStorage.setItem('refresh_token', refreshToken);
    },
    /**
     * 退出登录：**先通知服务端吊销令牌**，再清本地。
     *
     * 只清 localStorage 是不够的——服务端签发的是无状态 JWT（默认 2h），清掉本地副本后
     * 令牌在剩余有效期内依然可用。这里把 Access Token（走 Authorization 头）与
     * Refresh Token（走 body）一并交给 `POST /auth/logout` 拉黑。
     *
     * 刻意 **fire-and-forget 不 await**：① 登出是不可失败的清理动作，网络异常也必须让用户
     * 退出得掉；② 调用方（顶栏菜单、403 页、改密后重登）都是同步流程，不该被网络等待卡住。
     * 服务端侧同样是幂等的（令牌缺失/过期/非法都返回成功）。
     */
    logout() {
      const accessToken = this.accessToken;
      const refreshToken = this.refreshToken;

      if (accessToken || refreshToken) {
        httpClient
          .post<null>(
            '/auth/logout',
            { refreshToken },
            // 必须显式带 Authorization：请求拦截器是异步跑的，等它执行时下面的 localStorage
            // 已经被清空，它取到的 token 会是 null（拦截器只在 headers 里没有时才补 token）
            accessToken ? { headers: { Authorization: `Bearer ${accessToken}` } } : undefined
          )
          .catch(() => {
            /* 登出失败不打扰用户：本地登录态照清，令牌到期后自然失效 */
          });
      }

      this.accessToken = '';
      this.refreshToken = '';
      this.userInfo = null;
      // 核验状态归零回默认，避免下一位登录者继承上一位的「已核验」
      this.identityVerified = true;
      this.accountStatus = 1;
      localStorage.removeItem('access_token');
      localStorage.removeItem('refresh_token');
      localStorage.removeItem('user_info');
      localStorage.removeItem(IDENTITY_VERIFIED_KEY);
      localStorage.removeItem(ACCOUNT_STATUS_KEY);
    }
  }
});

// 单例 HTTP 客户端导出
export const httpClient = createHttpClient('/api/v1', {
  getAccessToken: () => localStorage.getItem('access_token'),
  getRefreshToken: () => localStorage.getItem('refresh_token'),
  setTokens: (access, refresh) => {
    localStorage.setItem('access_token', access);
    localStorage.setItem('refresh_token', refresh);
  },
  onSessionExpired: () => {
    // 只清登录三件套，保留提单草稿等业务缓存（统一门户后本 origin 还承载移动树）
    localStorage.removeItem('access_token');
    localStorage.removeItem('refresh_token');
    localStorage.removeItem('user_info');
    window.location.href = '/login?expired=1';
  }
});
