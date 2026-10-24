package com.campus.repair.modules.identity.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 名单导入结果（{@code POST /identity/roster/import}）。
 *
 * <p>{@code errors} 是**字符串数组**（前端直接逐行展示），元素形如
 * 「第3行：所属条线不合法 NOT_A_BRANCH」——行号是 Excel 的 1-based 行号（含表头行），
 * 与 {@code UserImportResultVO.failureDetails} 的口径不同，这里刻意保持简单。
 */
@Data
public class IdentityImportResultVO implements Serializable {

    /** 本次读到的数据行数 */
    private int total;

    /** 新增行数 */
    private int created;

    /** 覆盖更新行数（按 username 幂等） */
    private int updated;

    /** 跳过行数（非法条线/缺必填/写库失败） */
    private int skipped;

    private List<String> errors = new ArrayList<>();

    public void recordError(int rowNumber, String reason) {
        skipped++;
        errors.add("第" + rowNumber + "行：" + reason);
    }
}
