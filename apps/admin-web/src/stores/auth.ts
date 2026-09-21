import { defineStore } from 'pinia';
import { UserInfo, LoginResult } from '@campus/shared';
import { createHttpClient } from '@campus/shared';

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: localStorage.getItem('access_token') || '',
    refreshToken: localStorage.getItem('refresh_token') || '',
    userInfo: JSON.parse(localStorage.getItem('user_info') || 'null') as UserInfo | null
  }),
  getters: {
    isAuthenticated: (state) => !!state.accessToken
  },
  actions: {
    setAuth(loginResult: LoginResult) {
      this.accessToken = loginResult.accessToken;
      this.refreshToken = loginResult.refreshToken;
      this.userInfo = loginResult.userInfo;

      localStorage.setItem('access_token', loginResult.accessToken);
      localStorage.setItem('refresh_token', loginResult.refreshToken);
      localStorage.setItem('user_info', JSON.stringify(loginResult.userInfo));
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
      localStorage.removeItem('access_token');
      localStorage.removeItem('refresh_token');
      localStorage.removeItem('user_info');
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
