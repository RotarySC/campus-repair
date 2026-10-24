import type { AccountStatus } from './identity';

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
  /**
   * 账号状态：`0` 停用 / `1` 正常 / `2` 待核验（Phase 01）。
   * 与 `identityVerified` 是两个事实（见 `types/identity.ts`）；
   * 旧版后端 / 老 localStorage 快照里没有该字段，读取方需回退。
   */
  status?: AccountStatus;
  /** 身份核验是否已通过（Phase 01）；老快照里没有该字段时按已核验处理 */
  identityVerified?: boolean;
}

export interface LoginResult {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  userInfo: UserInfo;
  /**
   * 账号状态与核验状态（Phase 01 起 `LoginResponseVO` 在 data 顶层平铺这两个字段）。
   *
   * 刻意声明为可选：`PUT /users/password/change` 等「换票类」响应也复用本类型，
   * 它们并不承诺带核验字段；旧后端也有一个过渡窗口。读取方应以
   * `loginResult.identityVerified ?? userInfo.identityVerified ?? true` 的方式回退。
   */
  identityVerified?: boolean;
  accountStatus?: AccountStatus;
}

export interface CaptchaResult {
  captchaKey: string;
  imageBase64: string;
}
