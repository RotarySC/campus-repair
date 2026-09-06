import { describe, it, expect, vi, beforeEach } from 'vitest';
import axios from 'axios';
import { createHttpClient, TokenProvider } from '../src/api/http';

vi.mock('axios', async () => {
  const actual = await vi.importActual<any>('axios');
  return {
    ...actual,
    default: {
      ...actual.default,
      create: vi.fn(),
      post: vi.fn()
    }
  };
});

describe('createHttpClient - 401 Silent Token Refresh & Concurrency Queue', () => {
  let mockAxiosInstance: any;
  let requestInterceptor: any;
  let responseErrorInterceptor: any;
  let tokenProvider: TokenProvider;
  let currentAccessToken: string | null = 'old-access-token';
  let currentRefreshToken: string | null = 'valid-refresh-token';

  beforeEach(() => {
    currentAccessToken = 'old-access-token';
    currentRefreshToken = 'valid-refresh-token';

    tokenProvider = {
      getAccessToken: vi.fn(() => currentAccessToken),
      getRefreshToken: vi.fn(() => currentRefreshToken),
      setTokens: vi.fn((acc, ref) => {
        currentAccessToken = acc;
        currentRefreshToken = ref;
      }),
      onSessionExpired: vi.fn()
    };

    mockAxiosInstance = vi.fn().mockImplementation((config) => Promise.resolve({ data: 'success-data', config }));
    mockAxiosInstance.interceptors = {
      request: {
        use: vi.fn((fn) => {
          requestInterceptor = fn;
        })
      },
      response: {
        use: vi.fn((_, errorFn) => {
          responseErrorInterceptor = errorFn;
        })
      }
    };

    (axios.create as any).mockReturnValue(mockAxiosInstance);
  });

  it('应该在请求头中自动注入 Bearer Token', () => {
    createHttpClient('/api/v1', tokenProvider);
    const config = { headers: {} } as any;
    const resultConfig = requestInterceptor(config);

    expect(resultConfig.headers.Authorization).toBe('Bearer old-access-token');
  });

  it('遇到 401 错误时应发起刷新 Token 并无感重试原始请求', async () => {
    createHttpClient('/api/v1', tokenProvider);

    (axios.post as any).mockResolvedValueOnce({
      data: {
        code: 200,
        message: '成功',
        data: {
          accessToken: 'new-access-token',
          refreshToken: 'new-refresh-token'
        }
      }
    });

    const mockError = {
      response: { status: 401 },
      config: { headers: {} }
    };

    const retryPromise = responseErrorInterceptor(mockError);
    const result = await retryPromise;

    expect(axios.post).toHaveBeenCalledWith('/api/v1/auth/refresh-token', {
      refreshToken: 'valid-refresh-token'
    });
    expect(tokenProvider.setTokens).toHaveBeenCalledWith('new-access-token', 'new-refresh-token');
    expect(mockAxiosInstance).toHaveBeenCalled();
  });

  it('若 Refresh Token 也失效，应触发 onSessionExpired 回调', async () => {
    createHttpClient('/api/v1', tokenProvider);

    (axios.post as any).mockRejectedValueOnce(new Error('Refresh failed'));

    const mockError = {
      response: { status: 401 },
      config: { headers: {} }
    };

    await expect(responseErrorInterceptor(mockError)).rejects.toThrow();
    expect(tokenProvider.onSessionExpired).toHaveBeenCalled();
  });
});
