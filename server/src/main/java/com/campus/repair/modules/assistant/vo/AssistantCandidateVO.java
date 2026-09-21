package com.campus.repair.modules.assistant.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条故障候选。
 *
 * <p><b>两个"名称"不是一回事，刻意分开命名</b>：
 * <ul>
 *   <li>{@link #categoryName} 是**叶子名**（如「水龙头漏水」）——与
 *       {@code repair_order.category_full_name} 落库的是同一串；</li>
 *   <li>{@link #categoryPath} 是带大类的**显示路径**（如「水暖管道 / 水龙头漏水」）——
 *       分隔符与提单页 {@code findPathName} 的 {@code " / "} 一致。</li>
 * </ul>
 */
@Data
public class AssistantCandidateVO implements Serializable {

    private Long categoryId;
    private String categoryName;
    private String categoryPath;

    private Long parentId;
    private String parentName;

    /** 该细类对应的维修工种（决定派单给谁） */
    private String skillCode;

    /** 承诺解决时效（小时），来自字典 */
    private Integer slaHours;

    /** HIGH / MEDIUM / LOW —— 规则引擎按命中权重分档，**不给百分比**（避免编造精度） */
    private String confidence;

    /** 命中的词，供用户理解"为什么推荐它" */
    private List<String> matchedKeywords = new ArrayList<>();
}
