export interface ApiResponse<T = any> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
}

export interface UserInfo {
  id: number;
  username: string;
  realName: string;
  phone: string;
  userType: 'SUPER_ADMIN' | 'LOGISTICS_ADMIN' | 'REPAIR_WORKER' | 'USER_STUDENT' | 'USER_TEACHER';
  roleCodes: string[];
  isFirstLogin: number;
  defaultSpaceId?: number;
  avatarUrl?: string;
}

export interface LoginResult {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  userInfo: UserInfo;
}

export interface CaptchaResult {
  captchaKey: string;
  imageBase64: string;
}
