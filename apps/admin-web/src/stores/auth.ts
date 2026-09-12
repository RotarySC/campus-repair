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
    isAuthenticated: (state) => !!state.accessToken,
    isSuperAdmin: (state) => state.userInfo?.roleCodes.includes('SUPER_ADMIN'),
    isLogisticsAdmin: (state) => state.userInfo?.roleCodes.includes('LOGISTICS_ADMIN')
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
    logout() {
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
