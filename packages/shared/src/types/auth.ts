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
  department?: string;
  userType: 'SUPER_ADMIN' | 'LOGISTICS_ADMIN' | 'REPAIR_WORKER' | 'USER_STUDENT' | 'USER_TEACHER';
  roleCodes: string[];
  isFirstLogin: number;
  defaultSpaceId?: number;
  avatarUrl?: string;
  /** 注册时间（个人中心「账户概览」）；老会话的 localStorage 快照里可能没有 */
  createdAt?: string;
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
