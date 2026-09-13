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
  /**
   * **组织条线**（单值）——回答「你是谁、归哪条组织线」，同时就是单位字典 `org_node.audience`
   * 的取值。口径见仓库根 `CONTEXT.md`；**不要**再用它判断权限（那要用 `roleCodes`）。
   */
  userType: 'STUDENT' | 'FACULTY' | 'LOGISTICS' | 'ADMIN' | string;
  /** **职能**（可多值）——回答「能干什么」，与后端 `@PreAuthorize` 一一对应 */
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
