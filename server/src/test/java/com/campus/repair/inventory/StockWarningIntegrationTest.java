package com.campus.repair.inventory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.inventory.dto.MaterialConsumeDTO;
import com.campus.repair.modules.inventory.dto.SparePartInboundRequest;
import com.campus.repair.modules.inventory.entity.SparePart;
import com.campus.repair.modules.inventory.mapper.SparePartMapper;
import com.campus.repair.modules.inventory.service.InventoryService;
import com.campus.repair.modules.inventory.service.StockWarningService;
import com.campus.repair.modules.inventory.vo.StockWarningScanResultVO;
import com.campus.repair.modules.notification.entity.SysNotification;
import com.campus.repair.modules.notification.mapper.SysNotificationMapper;
import com.campus.repair.modules.spatial.service.SysConfigService;
import com.campus.repair.modules.user.entity.SysRole;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.SysUserRole;
import com.campus.repair.modules.user.mapper.SysRoleMapper;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 04 安全库存主动预警集成测试（8 条验收点，见阶段文档 §7.1）。
 *
 * <p>每个用例自建**专属调度账号与专属物料**（唯一编码后缀）：
 * 测试库被所有类共享，H2 里既有其它用例留下的低库存物料，之前也已有 admin 这个收件人；
 * 断言只按「本用例的 dispatcher + 本物料的 partCode」计数，避免被全局数据带偏。
 *
 * <p>冷却/开关是全局配置，{@link #restoreWarningConfig()} 在每个用例后复位。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StockWarningIntegrationTest {

    private static final String CATEGORY = "STOCK_WARNING";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private StockWarningService stockWarningService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private SparePartMapper sparePartMapper;

    @Autowired
    private SysNotificationMapper notificationMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysUserRoleMapper userRoleMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    @Autowired
    private SysConfigService configService;

    @AfterEach
    void restoreWarningConfig() {
        configService.updateConfig(StockWarningService.CONFIG_ENABLED, "1", null);
        configService.updateConfig(StockWarningService.CONFIG_COOLDOWN_HOURS, "24", null);
    }

    // ------------------------------------------------------------------
    // ① 扫描 → 调度收到 1 条，冷却水位写入
    // ------------------------------------------------------------------

    @Test
    @DisplayName("① 低库存物料 + 调度账号 → 立即扫描端点：收到 1 条 STOCK_WARNING，last_warning_at 写入")
    void scanEndpointNotifiesDispatcherAndMarksCooldown() throws Exception {
        SysUser dispatcher = createDispatcher();
        SparePart part = createPart(2, 5, 1);

        mockMvc.perform(post("/api/v1/inventory/stock-warning/scan")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.scanned", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.notified", greaterThanOrEqualTo(1)));

        assertEquals(1, notificationCount(dispatcher.getId(), part.getPartCode()));
        SysNotification notification = latestNotification(dispatcher.getId());
        assertNotNull(notification);
        assertEquals(CATEGORY, notification.getCategory());
        assertNull(notification.getRelatedOrderId(), "库存预警不是工单流转通知，related_order_id 必须为空");
        assertTrue(notification.getTitle().contains("备件库存预警"));
        assertTrue(notification.getContent().contains(part.getPartCode()), "正文应带物料编码便于定位");
        assertTrue(notification.getContent().contains("5"), "正文应带安全库存数字");

        // 只发通知，不改库存
        assertEquals(2, sparePartMapper.selectById(part.getId()).getStockQuantity());
        assertNotNull(sparePartMapper.selectById(part.getId()).getLastWarningAt(),
                "告警后应写入冷却水位");
    }

    // ------------------------------------------------------------------
    // ② 冷却期内不重复
    // ------------------------------------------------------------------

    @Test
    @DisplayName("② 冷却期内再扫 → notified=0，同一物料不再收到第二条")
    void secondScanWithinCooldownSendsNothing() {
        SysUser dispatcher = createDispatcher();
        SparePart part = createPart(1, 5, 1);

        assertTrue(stockWarningService.scanLowStock().getNotified() >= 1);
        assertEquals(1, notificationCount(dispatcher.getId(), part.getPartCode()));

        StockWarningScanResultVO second = stockWarningService.scanLowStock();
        assertEquals(0, second.getNotified(), "冷却期内不应重复推送");
        assertEquals(1, notificationCount(dispatcher.getId(), part.getPartCode()));
    }

    // ------------------------------------------------------------------
    // ③ 冷却改成 0 后可再提醒
    // ------------------------------------------------------------------

    @Test
    @DisplayName("③ 冷却小时数改成 0 后再扫 → 再发 1 条")
    void zeroCooldownAllowsSecondWarning() {
        SysUser dispatcher = createDispatcher();
        SparePart part = createPart(1, 5, 1);

        stockWarningService.scanLowStock();
        assertEquals(1, notificationCount(dispatcher.getId(), part.getPartCode()));

        configService.updateConfig(StockWarningService.CONFIG_COOLDOWN_HOURS, "0", null);
        assertTrue(stockWarningService.scanLowStock().getNotified() >= 1);
        assertEquals(2, notificationCount(dispatcher.getId(), part.getPartCode()));
    }

    // ------------------------------------------------------------------
    // ④ 停用物料不发
    // ------------------------------------------------------------------

    @Test
    @DisplayName("④ 停用物料（status=0）→ 不推送、不写冷却")
    void disabledPartIsIgnored() {
        SysUser dispatcher = createDispatcher();
        SparePart part = createPart(0, 5, 0);

        stockWarningService.scanLowStock();

        assertEquals(0, notificationCount(dispatcher.getId(), part.getPartCode()));
        assertNull(sparePartMapper.selectById(part.getId()).getLastWarningAt());
    }

    // ------------------------------------------------------------------
    // ⑤ 入库补回阈值以上清冷却
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑤ 入库补回阈值以上 → 清空 last_warning_at；再跌破可立即再提醒")
    void inboundAboveThresholdClearsCooldown() {
        SysUser dispatcher = createDispatcher();
        SparePart part = createPart(2, 5, 1);

        stockWarningService.scanLowStock();
        assertEquals(1, notificationCount(dispatcher.getId(), part.getPartCode()));
        assertNotNull(sparePartMapper.selectById(part.getId()).getLastWarningAt());

        SparePartInboundRequest inbound = new SparePartInboundRequest();
        inbound.setPartId(part.getId());
        inbound.setQuantity(10);
        inbound.setUnitPrice(new BigDecimal("1.00"));
        inventoryService.registerInbound(1L, "admin", inbound);

        assertEquals(12, sparePartMapper.selectById(part.getId()).getStockQuantity());
        assertNull(sparePartMapper.selectById(part.getId()).getLastWarningAt(),
                "补回阈值以上应清空冷却水位");

        // 再次跌破：直接原子扣回 9（12 → 3 ≤ 5），然后扫描应重新提醒
        sparePartMapper.deductStockAtomic(part.getId(), 9);
        stockWarningService.scanLowStock();
        assertEquals(2, notificationCount(dispatcher.getId(), part.getPartCode()),
                "冷却被清空后，当天再次跌破应能再提醒一次");
    }

    // ------------------------------------------------------------------
    // ⑥ 核销扣减 → AFTER_COMMIT 即时提醒
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑥ 完工核销把库存跌破阈值 → 事务提交后立即收到告警（不依赖定时任务）")
    void deductionTriggersAfterCommitWarning() {
        SysUser dispatcher = createDispatcher();
        SparePart part = createPart(10, 5, 1);

        // 不调用扫描；deductMaterialForOrder 内部发布事件，AFTER_COMMIT 监听器负责即时检查
        inventoryService.deductMaterialForOrder(9_900_001L, "MAT-WARN-ORDER", 1L,
                List.of(new MaterialConsumeDTO(part.getId(), 6)));

        SparePart after = sparePartMapper.selectById(part.getId());
        assertEquals(4, after.getStockQuantity());
        assertEquals(1, notificationCount(dispatcher.getId(), part.getPartCode()),
                "核销跌破安全线应即时推送，且只推一次");
        assertNotNull(after.getLastWarningAt());
    }

    // ------------------------------------------------------------------
    // ⑦ 总开关关闭
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑦ inventory.warning_enabled=0 → 不发通知、不写冷却")
    void disabledByConfigSendsNothing() {
        SysUser dispatcher = createDispatcher();
        SparePart part = createPart(1, 5, 1);
        configService.updateConfig(StockWarningService.CONFIG_ENABLED, "0", null);

        StockWarningScanResultVO result = stockWarningService.scanLowStock();
        assertEquals(0, result.getNotified());
        assertEquals(0, notificationCount(dispatcher.getId(), part.getPartCode()));
        assertNull(sparePartMapper.selectById(part.getId()).getLastWarningAt());
    }

    // ------------------------------------------------------------------
    // ⑧ 并发扫描不重复
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⑧ 并发重复调用扫描（模拟多入口同时触发）→ 冷却内每物料最多 1 条")
    void concurrentScansDoNotDuplicate() throws Exception {
        SysUser dispatcher = createDispatcher();
        SparePart part = createPart(1, 5, 1);

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    stockWarningService.scanLowStock();
                } catch (Exception ignored) {
                    // 拿不到扫描锁的线程直接跳过，用例只关心「不重复发」
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发扫描应在超时前结束");
        executor.shutdown();

        assertEquals(1, notificationCount(dispatcher.getId(), part.getPartCode()),
                "同一物料在冷却期内只能有 1 条告警");
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

    /**
     * 自建一个 DISPATCHER 账号：收件人查询按 sys_user_role join sys_role 取 role_code，
     * 所以必须真实落库（不能只造 token）。
     */
    private SysUser createDispatcher() {
        String suffix = String.valueOf(System.nanoTime());
        SysUser user = new SysUser();
        user.setUsername("stock_dispatch_" + suffix);
        user.setPhone("137" + String.format("%08d", Math.abs(suffix.hashCode() % 100_000_000)));
        user.setPassword("$2a$10$Zil1dAKT0PuD4Enm9NC27OnIAgVGF4HLsRtAE37pduiHlQ6Xb6TwK");
        user.setRealName("库存告警调度");
        user.setUserType("LOGISTICS");
        user.setStatus(1);
        user.setIsFirstLogin(0);
        userMapper.insert(user);

        SysRole dispatcherRole = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, "DISPATCHER"));
        assertNotNull(dispatcherRole, "测试种子必须含 DISPATCHER 职能");
        SysUserRole binding = new SysUserRole();
        binding.setUserId(user.getId());
        binding.setRoleId(dispatcherRole.getId());
        userRoleMapper.insert(binding);
        return user;
    }

    private SparePart createPart(int stock, int threshold, int status) {
        String suffix = String.valueOf(System.nanoTime());
        SparePart part = new SparePart();
        part.setPartCode("MAT-WARN-" + suffix);
        part.setPartName("库存预警测试件-" + suffix);
        part.setCategoryType("其他综合");
        part.setSpecification("TEST");
        part.setUnit("个");
        part.setGuidePrice(new BigDecimal("1.00"));
        part.setStockQuantity(stock);
        part.setSafetyStockThreshold(threshold);
        part.setStatus(status);
        sparePartMapper.insert(part);
        return part;
    }

    private long notificationCount(Long userId, String partCode) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getUserId, userId)
                .eq(SysNotification::getCategory, CATEGORY)
                .like(SysNotification::getContent, partCode));
    }

    private SysNotification latestNotification(Long userId) {
        List<SysNotification> list = notificationMapper.selectList(
                new LambdaQueryWrapper<SysNotification>()
                        .eq(SysNotification::getUserId, userId)
                        .eq(SysNotification::getCategory, CATEGORY)
                        .orderByDesc(SysNotification::getId));
        return list.isEmpty() ? null : list.get(0);
    }
}
