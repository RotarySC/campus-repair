import axios, { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from 'axios';
import { ApiResponse } from '../types/auth';

export interface TokenProvider {
  getAccessToken: () => string | null;
  getRefreshToken: () => string | null;
  setTokens: (accessToken: string, refreshToken: string) => void;
  onSessionExpired: () => void;
}

export function createHttpClient(baseURL: string, tokenProvider: TokenProvider): AxiosInstance {
  const instance = axios.create({
    baseURL,
    timeout: 15000,
    headers: {
      'Content-Type': 'application/json'
    }
  });

  let isRefreshing = false;
  let retrySubscribers: Array<(token: string) => void> = [];

  function subscribeTokenRefresh(cb: (token: string) => void) {
    retrySubscribers.push(cb);
  }

  function onRefreshed(newToken: string) {
    retrySubscribers.forEach((cb) => cb(newToken));
    retrySubscribers = [];
  }

  // 请求拦截器注入 Bearer Token
  instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const token = tokenProvider.getAccessToken();
    if (token && !config.headers.Authorization) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });

  // 响应拦截器：处理 401 并发无感换票
  instance.interceptors.response.use(
    (response) => response.data,
    async (error: AxiosError<ApiResponse>) => {
      const originalRequest = error.config;
      const status = error.response?.status;

      if (status === 401 && originalRequest && !(originalRequest as any)._retry) {
        (originalRequest as any)._retry = true;

        if (!isRefreshing) {
          isRefreshing = true;
          const refreshToken = tokenProvider.getRefreshToken();

          if (!refreshToken) {
            isRefreshing = false;
            tokenProvider.onSessionExpired();
            return Promise.reject(error);
          }

          try {
            // 调用换票端点
            const res = await axios.post<ApiResponse<{ accessToken: string; refreshToken: string }>>(
              `${baseURL}/auth/refresh-token`,
              { refreshToken }
            );

            if (res.data && res.data.code === 200) {
              const { accessToken, refreshToken: newRefreshToken } = res.data.data;
              tokenProvider.setTokens(accessToken, newRefreshToken);
              isRefreshing = false;
              onRefreshed(accessToken);

              originalRequest.headers.Authorization = `Bearer ${accessToken}`;
              return instance(originalRequest);
            } else {
              throw new Error('Refresh token rejected');
            }
          } catch (refreshErr) {
            isRefreshing = false;
            retrySubscribers = [];
            tokenProvider.onSessionExpired();
            return Promise.reject(refreshErr);
          }
        } else {
          // 将当前请求放入等待队列
          return new Promise((resolve) => {
            subscribeTokenRefresh((newToken: string) => {
              originalRequest.headers.Authorization = `Bearer ${newToken}`;
              resolve(instance(originalRequest));
            });
          });
        }
      }

      return Promise.reject(error);
    }
  );

  return instance;
}
