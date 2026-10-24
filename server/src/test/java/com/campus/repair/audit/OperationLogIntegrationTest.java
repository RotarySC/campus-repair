package com.campus.repair.audit;

import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.audit.constant.AuditConstants;
import com.campus.repair.modules.audit.entity.OperationLog;
import com.campus.repair.modules.audit.mapper.OperationLogMapper;
import com.campus.repair.modules.audit.service.OperationLogService;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 04 字典变更审计集成测试（10 条验收点，见阶段文档 §7.1）。
 *
 * <p>刻意**不加类级 {@code @Transactional}**：审计要与业务同事务提交，HTTP 请求走真实提交路径；
 * 「事务回滚不落审计」用 {@link TransactionTemplate} 显式构造（第 10 条）。
 *
 * <p>断言一律按 `targetId` 定位本用例创建的目标，避免与其它测试类通过 HTTP 写字典时
 * 留下的审计行互相干扰（它们同样会落审计，这是 Phase 04 的预期行为）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationLogIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private OperationLogMapper operationLogMapper;

    @Autowired
    private OperationLogService operationLogService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private com.campus.repair.modules.spatial.mapper.SysConfigMapper configMapper;

    // ------------------------------------------------------------------
    // ① 创建
    // ------------------------------------------------------------------

    @Test
    @DisplayName("① 创建空间节点 → 一行 CREATE 审计：after 含白名单字段，before 为空")
    void createSpatialNodeWritesCreateAudit() throws Exception {
        String name = "审计校区_" + System.nanoTime();
        long id = createNode(name);

        OperationLog log = latest(AuditConstants.TARGET_SPATIAL_NODE, id, AuditConstants.ACTION_CREATE);
        assertNotNull(log, "创建空间节点应落一条 CREATE 审计");
        assertEquals(AuditConstants.MODULE_SPATIAL, log.getModule());
        assertEquals("PLATFORM_ADMIN", log.getOperatorRole(), "空间字典由平台管理/调度写，按持有职能记");
        assertEquals("admin", log.getOperatorName());
        assertEquals(1L, log.getOperatorId());
        assertNull(log.getBeforeSnapshot(), "CREATE 的 before 必须为空");
        assertNotNull(log.getCreatedAt());

        JsonNode after = readJson(log.getAfterSnapshot());
        assertEquals(name, after.path("name").asText());
        assertEquals(0L, after.path("parentId").asLong());
        assertEquals(1, after.path("levelType").asInt());
        assertEquals(1, after.path("status").asInt());
    }

    // ------------------------------------------------------------------
    // ② 改名 / 分页 / 详情
    // ------------------------------------------------------------------

    @Test
    @DisplayName("② 改名 → UPDATE：before.name 与 after.name 不同；分页与详情都返回对象化前后值")
    void updateSpatialNodeWritesBeforeAfterSnapshots() throws Exception {
        String oldName = "旧名_" + System.nanoTime();
        long id = createNode(oldName);
        String newName = "新名_" + System.nanoTime();

        SpatialNode update = new SpatialNode();
        update.setId(id);
        update.setName(newName);
        update.setBuildingNature("TEACHING");
        update.setSortOrder(7);
        mockMvc.perform(put("/api/v1/spatial/nodes")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog log = latest(AuditConstants.TARGET_SPATIAL_NODE, id, AuditConstants.ACTION_UPDATE);
        assertNotNull(log);
        JsonNode before = readJson(log.getBeforeSnapshot());
        JsonNode after = readJson(log.getAfterSnapshot());
        assertEquals(oldName, before.path("name").asText());
        assertEquals(newName, after.path("name").asText());
        assertEquals("TEACHING", after.path("buildingNature").asText());
        assertEquals(7, after.path("sortOrder").asInt());
        assertEquals(newName, log.getTargetName(), "target_name 应冗余变更后名称，便于列表直接展示");

        // 分页查询：before/after 是**对象**而不是二次转义的 JSON 字符串
        mockMvc.perform(get("/api/v1/operation-logs")
                        .header("Authorization", bearer(adminToken()))
                        .param("module", AuditConstants.MODULE_SPATIAL)
                        .param("targetType", AuditConstants.TARGET_SPATIAL_NODE)
                        .param("targetId", String.valueOf(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].action").value(AuditConstants.ACTION_UPDATE))
                .andExpect(jsonPath("$.data.records[0].after.name").value(newName))
                .andExpect(jsonPath("$.data.records[0].before.name").value(startsWith("旧名_")));

        // 详情端点：完整前后快照 + 操作人
        mockMvc.perform(get("/api/v1/operation-logs/" + log.getId())
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.module").value(AuditConstants.MODULE_SPATIAL))
                .andExpect(jsonPath("$.data.after.name").value(newName))
                .andExpect(jsonPath("$.data.operatorRole").value("PLATFORM_ADMIN"));
    }

    // ------------------------------------------------------------------
    // ③ 改 SLA
    // ------------------------------------------------------------------

    @Test
    @DisplayName("③ 修改故障分类 SLA → UPDATE：before.slaHours=24、after.slaHours=12")
    void updateCategoryWritesSlaSnapshot() throws Exception {
        String name = "审计分类_" + System.nanoTime();
        long id = createCategory(name, 24);

        RepairCategory update = new RepairCategory();
        update.setId(id);
        update.setName(name);
        update.setSlaHours(12);
        mockMvc.perform(put("/api/v1/categories")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog log = latest(AuditConstants.TARGET_REPAIR_CATEGORY, id, AuditConstants.ACTION_UPDATE);
        assertNotNull(log, "改 SLA 应落一条 UPDATE 审计");
        assertEquals(AuditConstants.MODULE_CATEGORY, log.getModule());
        JsonNode before = readJson(log.getBeforeSnapshot());
        JsonNode after = readJson(log.getAfterSnapshot());
        assertEquals(24, before.path("slaHours").asInt());
        assertEquals(12, after.path("slaHours").asInt());
        assertEquals(name, before.path("name").asText());
        assertEquals(name, after.path("name").asText());
    }

    // ------------------------------------------------------------------
    // ④ 删除
    // ------------------------------------------------------------------

    @Test
    @DisplayName("④ 删除分类 → DELETE：target_name 仍可读、after 为空")
    void deleteCategoryKeepsReadableTargetName() throws Exception {
        String name = "待删分类_" + System.nanoTime();
        long id = createCategory(name, 24);

        mockMvc.perform(delete("/api/v1/categories/" + id)
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog log = latest(AuditConstants.TARGET_REPAIR_CATEGORY, id, AuditConstants.ACTION_DELETE);
        assertNotNull(log, "删除分类应落一条 DELETE 审计");
        assertEquals(name, log.getTargetName());
        assertEquals(id, log.getTargetId());
        assertNull(log.getAfterSnapshot(), "DELETE 的 after 必须为空");
        JsonNode before = readJson(log.getBeforeSnapshot());
        assertEquals(name, before.path("name").asText());
    }

    // ------------------------------------------------------------------
    // ⑤ 启停 + 操作人职能取自当前用户
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑤ 启停空间节点 → STATUS；操作人职能按当前用户持有职能记 DISPATCHER")
    void toggleNodeStatusWritesStatusAuditWithDispatcherRole() throws Exception {
        long id = createNode("启停校区_" + System.nanoTime());
        String dispatcherToken = jwtUtils.generateAccessToken(1L, "dispatcher-probe", List.of("DISPATCHER"));

        mockMvc.perform(put("/api/v1/spatial/nodes/" + id + "/status")
                        .header("Authorization", bearer(dispatcherToken))
                        .param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog log = latest(AuditConstants.TARGET_SPATIAL_NODE, id, AuditConstants.ACTION_STATUS);
        assertNotNull(log, "启停应落一条 STATUS 审计");
        assertEquals("DISPATCHER", log.getOperatorRole());
        JsonNode before = readJson(log.getBeforeSnapshot());
        JsonNode after = readJson(log.getAfterSnapshot());
        assertEquals(1, before.path("status").asInt());
        assertEquals(0, after.path("status").asInt());
        assertNotNull(log.getRemark());
        assertTrue(log.getRemark().contains("1"));
        assertTrue(log.getRemark().contains("0"));
    }

    // ------------------------------------------------------------------
    // ⑥ 改配置（新建 + 更新两个分支）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑥ 修改系统配置 → CONFIG/SYS_CONFIG 审计：先 CREATE、再 UPDATE 的前后值")
    void updateConfigWritesConfigAudit() throws Exception {
        String key = "test.audit.config." + System.nanoTime();

        mockMvc.perform(put("/api/v1/configs/" + key)
                        .header("Authorization", bearer(adminToken()))
                        .param("value", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        OperationLog created = latest(AuditConstants.TARGET_SYS_CONFIG,
                configId(key), AuditConstants.ACTION_CREATE);
        assertNotNull(created, "首次写入配置应落 CREATE 审计");
        assertEquals(AuditConstants.MODULE_CONFIG, created.getModule());
        assertEquals(key, created.getTargetName());
        assertEquals("PLATFORM_ADMIN", created.getOperatorRole(), "系统参数仅平台管理可写");
        assertNull(created.getBeforeSnapshot());
        assertEquals("42", readJson(created.getAfterSnapshot()).path("configValue").asText());

        mockMvc.perform(put("/api/v1/configs/" + key)
                        .header("Authorization", bearer(adminToken()))
                        .param("value", "43"))
                .andExpect(status().isOk());

        OperationLog updated = latest(AuditConstants.TARGET_SYS_CONFIG,
                configId(key), AuditConstants.ACTION_UPDATE);
        assertNotNull(updated, "再次写入应落 UPDATE 审计");
        assertEquals("42", readJson(updated.getBeforeSnapshot()).path("configValue").asText());
        assertEquals("43", readJson(updated.getAfterSnapshot()).path("configValue").asText());
    }

    // ------------------------------------------------------------------
    // ⑦ 报修人无权读审计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑦ REPORTER 查询审计 → 403")
    void reporterCannotReadOperationLogs() throws Exception {
        String reporterToken = jwtUtils.generateAccessToken(3L, "student01", List.of("REPORTER"));
        mockMvc.perform(get("/api/v1/operation-logs")
                        .header("Authorization", bearer(reporterToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    // ------------------------------------------------------------------
    // ⑧ 无写端点：PUT/DELETE → 405 且不新增审计行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑧ 对 /operation-logs 发 PUT/DELETE → 405，且审计行数不变（append-only）")
    void operationLogsHaveNoWriteEndpoints() throws Exception {
        long before = operationLogMapper.selectCount(null);

        mockMvc.perform(put("/api/v1/operation-logs")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"module\":\"SPATIAL\"}"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(delete("/api/v1/operation-logs/1")
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isMethodNotAllowed());

        assertEquals(before, operationLogMapper.selectCount(null), "审计表不可写：行数必须保持不变");
    }

    // ------------------------------------------------------------------
    // ⑨ 快照白名单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑨ 快照只含白名单字段：不含 deleted / 时间戳 / 主键等内部字段")
    void snapshotsOnlyContainWhitelistedFields() throws Exception {
        String name = "白名单分类_" + System.nanoTime();
        RepairCategory category = new RepairCategory();
        category.setName(name);
        category.setLevel(1);
        category.setKeywords("水管,漏水");
        category.setSelfHelpTip("关闭角阀|拍照留存");
        String body = mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(category)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).path("data").path("id").asLong();

        JsonNode after = readJson(
                latest(AuditConstants.TARGET_REPAIR_CATEGORY, id, AuditConstants.ACTION_CREATE)
                        .getAfterSnapshot());
        Set<String> keys = fieldNames(after);
        assertEquals(Set.of("name", "parentId", "level", "workerSkillCode", "slaHours",
                "keywords", "selfHelpTip", "sortOrder", "status"), keys,
                "分类快照必须与白名单逐字段一致（实体加字段时要同步更新白名单与用例）");
        assertFalse(keys.contains("deleted"));
        assertFalse(keys.contains("createdAt"));
        assertFalse(keys.contains("id"));
        assertEquals("水管,漏水", after.path("keywords").asText());

        long nodeId = createNode("白名单空间_" + System.nanoTime());
        JsonNode nodeAfter = readJson(
                latest(AuditConstants.TARGET_SPATIAL_NODE, nodeId, AuditConstants.ACTION_CREATE)
                        .getAfterSnapshot());
        assertEquals(Set.of("name", "parentId", "levelType", "buildingNature", "fullPathName",
                "sortOrder", "status"), fieldNames(nodeAfter));
    }

    // ------------------------------------------------------------------
    // ⑩ 事务回滚
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑩ 业务事务回滚时审计行不落库（审计与业务同事务）")
    void auditRollsBackWithBusinessTransaction() {
        long before = operationLogMapper.selectCount(null);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> template.execute(status -> {
            operationLogService.record(AuditConstants.MODULE_CATEGORY, AuditConstants.ACTION_UPDATE,
                    AuditConstants.TARGET_REPAIR_CATEGORY, 999_999L, "回滚用例",
                    Map.of("name", "before"), Map.of("name", "after"),
                    1L, "admin", "PLATFORM_ADMIN", "模拟业务失败");
            throw new IllegalStateException("模拟更新失败");
        }));

        assertEquals(before, operationLogMapper.selectCount(null),
                "业务回滚后不应留下「改成功」的假审计");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String adminToken() {
        return jwtUtils.generateAccessToken(1L, "admin", List.of("PLATFORM_ADMIN"));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private long createNode(String name) throws Exception {
        SpatialNode node = new SpatialNode();
        node.setName(name);
        node.setLevelType(1);
        String body = mockMvc.perform(post("/api/v1/spatial/nodes")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private long createCategory(String name, int slaHours) throws Exception {
        RepairCategory category = new RepairCategory();
        category.setName(name);
        category.setLevel(1);
        category.setSlaHours(slaHours);
        String body = mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(category)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private OperationLog latest(String targetType, long targetId, String action) {
        List<OperationLog> rows = operationLogMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OperationLog>()
                        .eq(OperationLog::getTargetType, targetType)
                        .eq(OperationLog::getTargetId, targetId)
                        .eq(OperationLog::getAction, action)
                        .orderByDesc(OperationLog::getId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long configId(String key) {
        com.campus.repair.modules.spatial.entity.SysConfig config = configMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.campus.repair.modules.spatial.entity.SysConfig>()
                        .eq(com.campus.repair.modules.spatial.entity.SysConfig::getConfigKey, key));
        assertNotNull(config, "配置项应已写入: " + key);
        return config.getId();
    }

    private JsonNode readJson(String raw) throws Exception {
        assertNotNull(raw, "快照 JSON 不应为空");
        return objectMapper.readTree(raw);
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
