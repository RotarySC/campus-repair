package com.campus.repair.workorder;

import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.workorder.dto.DuplicateCheckRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private SpatialNodeService spatialNodeService;

    @Autowired
    private RepairCategoryService categoryService;

    @Test
    @DisplayName("报修提单成功生成BX单号并触发同空间同分类防重阻断")
    void testOrderSubmitAndDuplicatePrevention() throws Exception {
        String studentToken = jwtUtils.generateAccessToken(3L, "student01", List.of("REPORTER"));

        // 1. 初始化空间与分类
        SpatialNode room = new SpatialNode();
        room.setName("101宿舍");
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);

        RepairCategory cat = new RepairCategory();
        cat.setName("电路漏电开关跳闸");
        cat.setLevel(2);
        cat.setWorkerSkillCode("ELECTRICIAN");
        cat.setSlaHours(6);
        cat = categoryService.createCategory(cat);

        // 2. 提单前失焦防重检查 -> 应返回 hasActiveOrder: false
        DuplicateCheckRequest checkReq = new DuplicateCheckRequest();
        checkReq.setSpaceId(room.getId());
        checkReq.setCategoryId(cat.getId());

        mockMvc.perform(post("/api/v1/work-orders/check-duplicate")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(checkReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.hasActiveOrder").value(false));

        // 3. 正常提交报修
        WorkOrderSubmitRequest submitReq = new WorkOrderSubmitRequest();
        submitReq.setSpaceId(room.getId());
        submitReq.setCategoryId(cat.getId());
        submitReq.setDescription("宿舍强电箱总闸跳闸合不上，有轻微焦糊味");
        submitReq.setFaultImages(List.of("/uploads/20260906/fault1.jpg"));
        submitReq.setUrgencyLevel(2); // 加急
        submitReq.setAppointmentPeriod("上午08:30-11:30");

        mockMvc.perform(post("/api/v1/work-orders/submit")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo", startsWith("BX")))
                .andExpect(jsonPath("$.data.status").value("PENDING_DISPATCH"));

        // 4. 再次触发防重检查 -> 应返回 hasActiveOrder: true 并提示既有单号 (由于监听派单事件无可用师傅，已由引擎降级为 MANUAL_DISPATCH)
        mockMvc.perform(post("/api/v1/work-orders/check-duplicate")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(checkReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.hasActiveOrder").value(true))
                .andExpect(jsonPath("$.data.activeOrderNo", startsWith("BX")))
                .andExpect(jsonPath("$.data.activeOrderStatus").value(anyOf(equalTo("PENDING_DISPATCH"), equalTo("MANUAL_DISPATCH"))));

        // 5. 校验工单流转审计时间轴首节点
        String submitResp = mockMvc.perform(post("/api/v1/work-orders/submit")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitReq)))
                .andReturn().getResponse().getContentAsString();
        Long newOrderId = objectMapper.readTree(submitResp).path("data").path("orderId").asLong();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/work-orders/" + newOrderId + "/timeline")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data[0].actionType").value("SUBMIT"))
                // 批次 4 起审计词表统一为职能码：提单人写 REPORTER（不再是组织条线 STUDENT）
                .andExpect(jsonPath("$.data[0].operatorRole").value("REPORTER"))
                .andExpect(jsonPath("$.data[0].fromStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].toStatus").value("PENDING_DISPATCH"));
    }
}
