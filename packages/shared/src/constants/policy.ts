/**
 * 账户安全策略常量。
 *
 * ⚠️ 后端 `PasswordChangeRequest` 的 `@Size(min=8,max=32)` + `@Pattern("^(?=.*[A-Za-z])(?=.*\\d).+$")`
 * 与本文件规则**必须同步修改**：后端拆成长度注解 + 双 lookahead，前端把两者合成一个正则，
 * 语义等价；只改一处会出现「前端放行、后端 400」这类口径不一致。
 */

/** 新密码强度：8~32 位，且同时包含字母与数字 */
export const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).{8,32}$/;

export const PASSWORD_RULE_MESSAGE = '新密码需 8~32 位且同时包含字母与数字';
