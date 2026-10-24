package com.campus.repair.modules.review.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 旧端点 {@code POST /api/v1/notifications/broadcast} 的兼容返回体。
 *
 * <p>语义已从「立即向全校扇出」改为「创建公告草稿」：路径与权限不变，前端旧入口不会 404，
 * 但「先审后发」不可绕过。调用方拿 {@code announcementId} 去公告管理页继续提交/审核/发布。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementDraftVO {

    private Long announcementId;
}
