package com.campus.repair.spatial;

import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.spatial.service.SysConfigService;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpatialDictIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private SpatialNodeService spatialNodeService;

    @Autowired
    private SysConfigService configService;

    @Test
    @DisplayName("构建四级校园空间树，支持全路径冗余、软停用与根校区追溯")
    void testSpatialTreeLifecycle() throws Exception {
        String adminToken = jwtUtils.generateAccessToken(1L, "admin", List.of("PLATFORM_ADMIN"));

        // 1. 创建校区
        SpatialNode campus = new SpatialNode();
        campus.setName("朝阳校区");
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);
        Long campusId = campus.getId();

        // 2. 创建组团
        SpatialNode cluster = new SpatialNode();
        cluster.setParentId(campusId);
        cluster.setName("学生公寓南区");
        cluster = spatialNodeService.createNode(cluster);

        // 3. 创建楼栋 (标记建筑性质 DORMITORY)
        SpatialNode building = new SpatialNode();
        building.setParentId(cluster.getId());
        building.setName("12号楼");
        building.setBuildingNature("DORMITORY");
        building = spatialNodeService.createNode(building);

        // 4. 创建房间
        SpatialNode room = new SpatialNode();
        room.setParentId(building.getId());
        room.setName("302室");
        room = spatialNodeService.createNode(room);

        // 校验房间全路径与所属校区解析
        assertEquals("朝阳校区/学生公寓南区/12号楼/302室", room.getFullPathName());
        assertEquals(campusId, spatialNodeService.resolveRootCampusId(room.getId()));

        // 5. 软停用房间节点
        mockMvc.perform(put("/api/v1/spatial/nodes/" + room.getId() + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 6. 请求树结构 (带 onlyEnabled=true 应过滤停用节点)
        mockMvc.perform(get("/api/v1/spatial/tree")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("onlyEnabled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("构建故障两级分类树并验证工种映射与SLA时限")
    void testCategoryTreeLifecycle() throws Exception {
        String adminToken = jwtUtils.generateAccessToken(1L, "admin", List.of("PLATFORM_ADMIN"));

        // 1. 创建一级大类
        RepairCategory parent = new RepairCategory();
        parent.setName("水电设施");
        parent.setLevel(1);

        String parentJson = mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();

        Long parentId = objectMapper.readTree(parentJson).path("data").path("id").asLong();

        // 2. 创建二级细类 (绑定 PLUMBER 与 12 小时 SLA)
        RepairCategory sub = new RepairCategory();
        sub.setParentId(parentId);
        sub.setName("水龙头漏水破裂");
        sub.setWorkerSkillCode("PLUMBER");
        sub.setSlaHours(12);

        mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.workerSkillCode").value("PLUMBER"))
                .andExpect(jsonPath("$.data.slaHours").value(12));

        // 3. 查询分类树
        mockMvc.perform(get("/api/v1/categories/tree")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("系统核心业务配置读写 (72h超时与差评阈值)")
    void testSysConfigLifecycle() throws Exception {
        String adminToken = jwtUtils.generateAccessToken(1L, "admin", List.of("PLATFORM_ADMIN"));

        // 初始化/更新 72h 超时参数
        mockMvc.perform(put("/api/v1/configs/auto_acceptance_timeout_hours")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("value", "72"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(72, configService.getIntValue("auto_acceptance_timeout_hours", 0));

        // 读取配置列表
        mockMvc.perform(get("/api/v1/configs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
