package com.campus.repair.org;

import com.campus.repair.framework.util.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 单位组织字典（`GET /org/tree`）契约测试。
 *
 * <p>锁死三件事：① 树是**嵌套**结构且层级顺序为「年级在前、学院在后」；
 * ② {@code fullPathName} 用**空格**分隔，与 {@code sys_user.department} 是同一串
 * （前端靠整串精确匹配回显级联路径，一旦有人改成 `/` 或改格式，个人中心的回显会静默失效）；
 * ③ 停用节点只在 {@code onlyEnabled=false} 时出现。
 *
 * <p>只读用例，不加 {@code @Transactional}（种子来自 data-test.sql：2024级=1000 / 教职工=1010）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgTreeIntegrationTest {

    private static final long ADMIN_ID = 1L;
    private static final long WORKER_ID = 2L;
    private static final long STUDENT_ID = 3L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    private String bearer(long userId, String username, String... roles) {
        return "Bearer " + jwtUtils.generateAccessToken(userId, username, List.of(roles));
    }

    @Test
    @DisplayName("GET /org/tree 返回嵌套树，根层为年级在前、教职工在后，fullPathName 用空格分隔")
    void testOrgTreeShapeAndRootOrder() throws Exception {
        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "USER_STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 年级在前：根层第一个是年级，教职工排在年级之后
                .andExpect(jsonPath("$.data[0].name").value("2024级"))
                .andExpect(jsonPath("$.data[1].name").value("教职工"))
                // 年级 -> 学院 两级完整路径
                .andExpect(jsonPath("$.data[0].children[0].name").value("计算机学院"))
                .andExpect(jsonPath("$.data[0].children[0].levelType").value(2))
                .andExpect(jsonPath("$.data[0].children[0].fullPathName").value("2024级 计算机学院"))
                // 教职工可再下钻一级（处室 -> 班组）
                .andExpect(jsonPath("$.data[1].children[0].name").value("后勤维修中心"))
                .andExpect(jsonPath("$.data[1].children[0].children[0].name").value("电工班"))
                .andExpect(jsonPath("$.data[1].children[0].children[0].fullPathName")
                        .value("教职工 后勤维修中心 电工班"));
    }

    @Test
    @DisplayName("全路径一律用空格分隔且不含 / —— 与 sys_user.department 是同一套字面值")
    void testFullPathUsesSpaceSeparator() throws Exception {
        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "USER_STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..fullPathName", everyItem(not(containsString("/")))))
                .andExpect(jsonPath("$..fullPathName", hasItem("2024级 计算机学院")))
                .andExpect(jsonPath("$..fullPathName", hasItem("教职工 后勤维修中心 电工班")));
    }

    @Test
    @DisplayName("onlyEnabled=true 过滤停用节点，缺省 false 时包含")
    void testOnlyEnabledFilter() throws Exception {
        // 骨架里「已停用单位」排在教职工的第 3 个子节点（sort_order=99）
        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "USER_STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].children[2].name").value("已停用单位"))
                .andExpect(jsonPath("$.data[1].children[2].status").value(0));

        mockMvc.perform(get("/api/v1/org/tree")
                        .param("onlyEnabled", "true")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "USER_STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].children.length()").value(2))
                .andExpect(jsonPath("$..fullPathName", not(hasItem("教职工 已停用单位"))));
    }

    @Test
    @DisplayName("个人中心取字典不需要管理员角色，且未登录一律 401")
    void testVisibilityForAllRolesAndAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(WORKER_ID, "worker01", "REPAIR_WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(ADMIN_ID, "admin", "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/v1/org/tree"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("种子账号的 department 与字典叶子 fullPathName 逐字符一致（前端回显契约）")
    void testSeededDepartmentMatchesDictionaryPath() throws Exception {
        // data-test.sql 给 student01 种了「2024级 计算机学院」；它必须能在字典里被整串命中，
        // 否则个人中心的级联选择器会对已存值显示为空（回显失效）。
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "USER_STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.department").value("2024级 计算机学院"));

        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "USER_STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..fullPathName", hasItem("2024级 计算机学院")));
    }

    @Test
    @DisplayName("种子只在顶层分支标注 audience：年级=STUDENT、教职工=STAFF（子节点为 null）")
    void testSeedDeclaresAudienceOnRootBranchesOnly() throws Exception {
        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "USER_STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("2024级"))
                .andExpect(jsonPath("$.data[0].audience").value("STUDENT"))
                .andExpect(jsonPath("$.data[0].children[0].audience").value(nullValue()))
                .andExpect(jsonPath("$.data[1].name").value("教职工"))
                .andExpect(jsonPath("$.data[1].audience").value("STAFF"))
                .andExpect(jsonPath("$.data[1].children[0].audience").value(nullValue()));
    }

    @Test
    @DisplayName("audience=STUDENT 只留年级分支（学生看不到「教职工」），audience=STAFF 反之")
    void testAudiencePruning() throws Exception {
        // 学生：只有年级分支，且树里不应再出现「教职工」这个词
        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "STUDENT")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "USER_STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("2024级"))
                .andExpect(jsonPath("$..name", not(hasItem("教职工"))))
                .andExpect(jsonPath("$..fullPathName", hasItem("2024级 计算机学院")));

        // 教工/师傅：只有教职工分支，且树里不应再出现任何年级
        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "STAFF")
                        .header("Authorization", bearer(WORKER_ID, "worker01", "REPAIR_WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("教职工"))
                .andExpect(jsonPath("$..name", not(hasItem("2024级"))))
                .andExpect(jsonPath("$..fullPathName", hasItem("教职工 后勤维修中心 电工班")));

        // 不传 audience 时返回全量树（管理员/字典维护用），且大小写不敏感
        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "USER_STUDENT")))
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "staff")
                        .header("Authorization", bearer(WORKER_ID, "worker01", "REPAIR_WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("教职工"));
    }

    @Test
    @DisplayName("未知 audience 报参数错误（400），空串按不限制处理")
    void testUnknownAudienceRejected() throws Exception {
        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "PARENT")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "USER_STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "USER_STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2));
    }
}
