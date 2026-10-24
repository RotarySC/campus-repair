package com.campus.repair.modules.review.dto;

/**
 * 一条「可命中」的敏感词规则：内存快照的条目形态。
 *
 * <p>{@code word} 已经过 {@code ContentTextNormalizer.normalizeWord} 归一化（小写、去零宽、
 * 气泡空白折叠），{@code screen()} 直接拿归一化正文做 {@code contains} 即可，
 * 不再在热路径上做字符串变换。
 *
 * <p>刻意与实体 {@code SysSensitiveWord} 分开：初筛是纯函数，只依赖这个不可变值对象，
 * 单测不需要启动 Spring/数据库。
 */
public record SensitiveWordRule(String word, String category) {
}
