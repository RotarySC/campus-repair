package com.campus.repair.framework.util;

/**
 * 敏感字段掩码工具：只做「展示值」计算，不参与任何判定。
 *
 * <p>口径（必须与前端 {@code packages/shared/src/utils/format.ts#maskPhone} 逐字一致）：
 * <ul>
 *   <li>11 位手机号：保留前 3 位与后 4 位，中间固定 4 个星号，如 {@code 138****0000}；</li>
 *   <li>其他长度（含带区号的固话）：保留前 1/3 与后 1/3，中间 3 个星号；长度 ≤ 2 时整串掩码；</li>
 *   <li>空值 / 全空白：返回 {@code null}（不返回空串，避免前端出现「空的省略号」）。</li>
 * </ul>
 *
 * <p>调用方只应把结果放进 VO 的 {@code *Display} 字段；原始字段保留给处理人与管理侧，
 * 这样旧前端代码不会因为字段缺失而崩，新前端代码统一读 {@code *Display}。
 */
public final class SensitiveMasker {

    private static final int MOBILE_LENGTH = 11;

    private SensitiveMasker() {
    }

    /**
     * 掩码手机号。
     *
     * @param phone 原始号码，可为 null / 空白
     * @return 掩码后的展示值；入参为空时返回 null
     */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() == MOBILE_LENGTH) {
            return trimmed.substring(0, 3) + "****" + trimmed.substring(7);
        }
        if (trimmed.length() <= 2) {
            return "***";
        }
        int keep = trimmed.length() / 3;
        return trimmed.substring(0, keep) + "***" + trimmed.substring(trimmed.length() - keep);
    }

    /**
     * 判断号码是否已被掩码（含星号即视为不可拨号）。
     *
     * <p>前端用它决定「要不要生成 tel: 链接」；放后端是为了让探针与接口契约共用同一判据。
     */
    public static boolean isMasked(String phone) {
        return phone == null || phone.indexOf('*') >= 0;
    }
}
