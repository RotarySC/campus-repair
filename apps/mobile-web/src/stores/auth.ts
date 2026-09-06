import { defineStore } from 'pinia';
import { UserInfo, LoginResult } from '@campus/shared';
import { createHttpClient } from '@campus/shared';

export const useAuthStore = defineStore('mobile_auth', {
  state: () => ({
    accessToken: localStorage.getItem('mobile_access_token') || '',
    refreshToken: localStorage.getItem('mobile_refresh_token') || '',
    userInfo: JSON.parse(localStorage.getItem('mobile_user_info') || 'null') as UserInfo | null
  }),
  getters: {
    isAuthenticated: (state) => !!state.accessToken,
    isWorker: (state) => state.userInfo?.userType === 'REPAIR_WORKER',
    isStudent: (state) => state.userInfo?.userType === 'USER_STUDENT'
  },
  actions: {
    setAuth(loginResult: LoginResult) {
      this.accessToken = loginResult.accessToken;
      this.refreshToken = loginResult.refreshToken;
      this.userInfo = loginResult.userInfo;

      localStorage.setItem('mobile_access_token', loginResult.accessToken);
      localStorage.setItem('mobile_refresh_token', loginResult.refreshToken);
      localStorage.setItem('mobile_user_info', JSON.stringify(loginResult.userInfo));
    },
    logout() {
      this.accessToken = '';
      this.refreshToken = '';
      this.userInfo = null;
      localStorage.removeItem('mobile_access_token');
      localStorage.removeItem('mobile_refresh_token');
      localStorage.removeItem('mobile_user_info');
    }
  }
});

export const httpClient = createHttpClient('/api/v1', {
  getAccessToken: () => localStorage.getItem('mobile_access_token'),
  getRefreshToken: () => localStorage.getItem('mobile_refresh_token'),
  setTokens: (access, refresh) => {
    localStorage.setItem('mobile_access_token', access);
    localStorage.setItem('mobile_refresh_token', refresh);
  },
  onSessionExpired: () => {
    localStorage.removeItem('mobile_access_token');
    localStorage.removeItem('mobile_refresh_token');
    localStorage.removeItem('mobile_user_info');
    window.location.href = '/login?expired=1';
  }
});
