import axios, { AxiosError, AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios';
import { ApiResponse } from '../types/auth';

export interface TokenProvider {
  getAccessToken: () => string | null;
  getRefreshToken: () => string | null;
  setTokens: (accessToken: string, refreshToken: string) => void;
  onSessionExpired: () => void;
}

/**
 * 已解包业务返回体 `R<T>` 的 HTTP 客户端。
 *
 * 响应拦截器把 axios 的 `AxiosResponse` 解包成了 `response.data`（即业务返回体 `R<T>`），
 * 所以本客户端每个方法都直接 resolve 出 `ApiResponse<T>`：调用方必须读
 * `res.code` / `res.data`，**不是** `res.data.code` / `res.data.data`（后者运行时恒为
 * `undefined`，会把成功分支静默跳过）。
 *
 * 泛型参数只写业务数据类型：`httpClient.get<OrderVO>(url)` 得到 `ApiResponse<OrderVO>`。
 * 注意两点：① 业务失败也可能是 HTTP 200 + `code !== 200`，必须判 `res.code`；
 * ② 导出类二进制流不经本客户端（拦截器会解包掉），请直接用 `fetch`。
 */
export interface HttpClient {
  get<T = any>(url: string, config?: AxiosRequestConfig): Promise<ApiResponse<T>>;
  post<T = any>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<ApiResponse<T>>;
  put<T = any>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<ApiResponse<T>>;
  patch<T = any>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<ApiResponse<T>>;
  delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<ApiResponse<T>>;
}

export function createHttpClient(baseURL: string, tokenProvider: TokenProvider): HttpClient {
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

  // 请求拦截器注入 Bearer Token 并规范化去除多余的 baseURL 前缀 (防止重复传入 /api/v1)
  instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    if (config.url && baseURL && config.url.startsWith(baseURL)) {
      config.url = config.url.substring(baseURL.length);
    }
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

  // 拦截器已把响应解包为 response.data，故用 axios 的第二个泛型参数（R）如实声明
  // resolve 类型就是业务返回体 R<T>，无需类型断言。
  return {
    get: <T = any>(url: string, config?: AxiosRequestConfig) =>
      instance.get<T, ApiResponse<T>>(url, config),
    post: <T = any>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
      instance.post<T, ApiResponse<T>>(url, data, config),
    put: <T = any>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
      instance.put<T, ApiResponse<T>>(url, data, config),
    patch: <T = any>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
      instance.patch<T, ApiResponse<T>>(url, data, config),
    delete: <T = any>(url: string, config?: AxiosRequestConfig) =>
      instance.delete<T, ApiResponse<T>>(url, config)
  };
}
