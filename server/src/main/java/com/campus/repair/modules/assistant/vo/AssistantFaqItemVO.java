package com.campus.repair.modules.assistant.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 制度问答的一条。答案里的时效/阈值都来自系统参数，不硬编码。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssistantFaqItemVO implements Serializable {

    private String question;
    private String answer;
}
