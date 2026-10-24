/**
 * 个人中心（`/profile`）相关视图对象。
 * 与后端 `modules/user/vo/RepairSummaryVO`、`MyWorkerProfileVO` 一一对应。
 */

import type { AccountStatus } from './identity';
import type { UserInfo } from './auth';

/** 师生「我的报修概览」；avgRating 无评价时为 null（前端渲染 `—`，不做兜底假值） */
export interface RepairSummaryVO {
  totalCount: number;
  /** 待派单 = PENDING_DISPATCH + MANUAL_DISPATCH */
  pendingDispatchCount: number;
  /** 在办 = ASSIGNED + IN_PROGRESS + SUSPENDED + REWORK_PENDING */
  processingCount: number;
  /** 待验收 = PENDING_ACCEPTANCE */
  pendingAcceptanceCount: number;
  /** 已结单 = COMPLETED */
  completedCount: number;
  /** 已作废 = CANCELLED */
  cancelledCount: number;
  avgRating: number | null;
}

/**
 * 单位组织字典节点（`GET /org/tree`）。
 * 与后端 `modules/org/vo/OrgNodeVO` 一一对应。
 *
 * `fullPathName` 用**空格**分隔各级名称（如 `2024级 计算机学院`），且与
 * `UserInfo.department` 存的是**同一个字符串**——个人中心靠整串精确匹配把已存文案
 * 回显成级联路径，因此这里**不要**改成 `/` 分隔或做分隔符解析（改了回显会静默失效）。
 */
export interface OrgNodeVO {
  id: number;
  parentId: number;
  name: string;
  /** 1-年级/所属单位, 2-学院/处室, 3-班级/班组 */
  levelType: number;
  /**
   * 组织条线：`'STUDENT'` / `'FACULTY'` / `'LOGISTICS'` / `'ADMIN'`（= `UserInfo.userType`，
   * 同一个值、不做二次映射）或 `null`（不限，子节点继承父级）。
   * 可在任意层级声明：当前种子把教职工侧的条线打在**二级单位**上，年级分支打在年级节点上。
   */
  audience: string | null;
  fullPathName: string;
  sortOrder: number;
  /** 1-正常, 0-停用（前端取数一律带 onlyEnabled=true） */
  status: number;
  children: OrgNodeVO[];
}

/**
 * 当前登录用户的完整资料（`GET /users/me`，同时是登录响应的 `userInfo`）。
 *
 * 与后端 `modules/user/vo/UserProfileVO` 一一对应：Phase 01 起新增
 * `status`（`0/1/2`）与 `identityVerified`，两者与 `UserInfo` 里的同名字段同源。
 * `GET /users/me` 是**待核验账号唯一放行的资料端点**（见阶段文档 §4.4 白名单）。
 */
export interface UserProfileVO extends UserInfo {
  status: AccountStatus;
  identityVerified: boolean;
}

/** 师傅「我的作业概览」；无 worker_profile 档案时后端返回 data=null */
export interface MyWorkerProfileVO {
  skillCode: string | null;
  campusId: number | null;
  campusName: string | null;
  maxLoad: number;
  currentLoad: number;
  /** 0~100，与调度中心技师负荷看板同口径 */
  loadRate: number;
  /** 1-在岗, 0-休息（本轮只读展示，不支持自助切换） */
  onDutyStatus: number;
  ratingAvg: number | null;
  totalCompletedCount: number;
  todayCompletedCount: number;
}
