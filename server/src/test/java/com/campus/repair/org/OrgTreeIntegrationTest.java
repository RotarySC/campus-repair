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
 * <p>只读用例，不加 {@code @Transactional}（种子来自 data-test.sql：2024级=1000 / 所属单位=1010）。
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
    @DisplayName("GET /org/tree 返回嵌套树，根层为年级在前、所属单位在后，fullPathName 用空格分隔")
    void testOrgTreeShapeAndRootOrder() throws Exception {
        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 年级在前：根层第一个是年级，「所属单位」排在年级之后
                .andExpect(jsonPath("$.data[0].name").value("2024级"))
                .andExpect(jsonPath("$.data[1].name").value("所属单位"))
                // 年级 -> 学院 两级完整路径
                .andExpect(jsonPath("$.data[0].children[0].name").value("计算机学院"))
                .andExpect(jsonPath("$.data[0].children[0].levelType").value(2))
                .andExpect(jsonPath("$.data[0].children[0].fullPathName").value("2024级 计算机学院"))
                // 所属单位可再下钻一级（处室 -> 班组）
                .andExpect(jsonPath("$.data[1].children[0].name").value("后勤维修中心"))
                .andExpect(jsonPath("$.data[1].children[0].children[0].name").value("电工班"))
                .andExpect(jsonPath("$.data[1].children[0].children[0].fullPathName")
                        .value("所属单位 后勤维修中心 电工班"));
    }

    @Test
    @DisplayName("全路径一律用空格分隔且不含 / —— 与 sys_user.department 是同一套字面值")
    void testFullPathUsesSpaceSeparator() throws Exception {
        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..fullPathName", everyItem(not(containsString("/")))))
                .andExpect(jsonPath("$..fullPathName", hasItem("2024级 计算机学院")))
                .andExpect(jsonPath("$..fullPathName", hasItem("所属单位 后勤维修中心 电工班")));
    }

    @Test
    @DisplayName("onlyEnabled=true 过滤停用节点，缺省 false 时包含")
    void testOnlyEnabledFilter() throws Exception {
        // 骨架里「已停用单位」排在「所属单位」的子节点末位（sort_order=99）
        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].children[3].name").value("已停用单位"))
                .andExpect(jsonPath("$.data[1].children[3].status").value(0));

        mockMvc.perform(get("/api/v1/org/tree")
                        .param("onlyEnabled", "true")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].children.length()").value(3))
                .andExpect(jsonPath("$..fullPathName", not(hasItem("所属单位 已停用单位"))));
    }

    @Test
    @DisplayName("个人中心取字典不需要管理员角色，且未登录一律 401")
    void testVisibilityForAllRolesAndAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(WORKER_ID, "worker01", "WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(ADMIN_ID, "admin", "PLATFORM_ADMIN")))
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
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.department").value("2024级 计算机学院"));

        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..fullPathName", hasItem("2024级 计算机学院")));
    }

    @Test
    @DisplayName("seed 把条线标在二级单位上：根节点不限、年级标 STUDENT、单位标各自的条线、班组为 null 继承")
    void testSeedDeclaresAudienceOnBranchesAndUnits() throws Exception {
        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                // 根层顺序由 sort_order 决定：2024级(20) → 所属单位(40)
                .andExpect(jsonPath("$.data[0].name").value("2024级"))
                .andExpect(jsonPath("$.data[0].audience").value("STUDENT"))
                .andExpect(jsonPath("$.data[0].children[0].audience").value(nullValue()))
                // 教职工侧的根节点名刻意不是「教职工」（那是身份词）；根节点本身不限条线
                .andExpect(jsonPath("$.data[1].name").value("所属单位"))
                .andExpect(jsonPath("$.data[1].audience").value(nullValue()))
                // 二级单位各自声明条线（顺序由 sort_order 决定），其子节点（班组）留 null 继承
                .andExpect(jsonPath("$.data[1].children[0].name").value("后勤维修中心"))
                .andExpect(jsonPath("$.data[1].children[0].audience").value("LOGISTICS"))
                .andExpect(jsonPath("$.data[1].children[0].children[0].name").value("电工班"))
                .andExpect(jsonPath("$.data[1].children[0].children[0].audience").value(nullValue()))
                .andExpect(jsonPath("$.data[1].children[1].name").value("计算机学院"))
                .andExpect(jsonPath("$.data[1].children[1].audience").value("FACULTY"))
                .andExpect(jsonPath("$.data[1].children[2].name").value("信息中心"))
                .andExpect(jsonPath("$.data[1].children[2].audience").value("ADMIN"));
    }

    @Test
    @DisplayName("按组织条线剪枝：教师看不到后勤班组，后勤/师傅看不到学院（堆叠现象回归）")
    void testAudiencePruningPerBranch() throws Exception {
        // 学生：只有年级分支
        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "STUDENT")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("2024级"))
                // 空壳不出现：所属单位的三条线都不匹配 STUDENT，整根被剪掉
                .andExpect(jsonPath("$..name", not(hasItem("所属单位"))))
                .andExpect(jsonPath("$..fullPathName", hasItem("2024级 计算机学院")));

        // 教学科研（教师）：**只**看到学院——看不到后勤维修中心与其班组
        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "FACULTY")
                        .header("Authorization", bearer(STUDENT_ID, "teacher", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("所属单位"))
                .andExpect(jsonPath("$..fullPathName", hasItem("所属单位 计算机学院")))
                .andExpect(jsonPath("$..name", not(hasItem("后勤维修中心"))))
                .andExpect(jsonPath("$..name", not(hasItem("电工班"))))
                .andExpect(jsonPath("$..name", not(hasItem("2024级"))));

        // 后勤保障（师傅/调度）：**只**看到后勤单位——看不到任何学院
        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "LOGISTICS")
                        .header("Authorization", bearer(WORKER_ID, "worker01", "WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("所属单位"))
                .andExpect(jsonPath("$..fullPathName", hasItem("所属单位 后勤维修中心 电工班")))
                .andExpect(jsonPath("$..name", not(hasItem("计算机学院"))))
                .andExpect(jsonPath("$..name", not(hasItem("土木工程学院"))));

        // 行政信息化：只看到信息中心
        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "ADMIN")
                        .header("Authorization", bearer(ADMIN_ID, "admin", "PLATFORM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..fullPathName", hasItem("所属单位 信息中心")))
                .andExpect(jsonPath("$..name", not(hasItem("计算机学院"))))
                .andExpect(jsonPath("$..name", not(hasItem("后勤维修中心"))));

        // **用户报的现象的直接回归**：教师与后勤拿到的是两棵互不重叠的树
        String facultyTree = mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "FACULTY")
                        .header("Authorization", bearer(STUDENT_ID, "teacher", "REPORTER")))
                .andReturn().getResponse().getContentAsString();
        String logisticsTree = mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "LOGISTICS")
                        .header("Authorization", bearer(WORKER_ID, "worker01", "WORKER")))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(facultyTree).doesNotContain("后勤维修中心");
        org.assertj.core.api.Assertions.assertThat(logisticsTree).doesNotContain("学院");

        // 不传 audience 时返回全量树（字典维护用）：测试库只加载 data-test.sql，故为 2 个根
        mockMvc.perform(get("/api/v1/org/tree")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "faculty")
                        .header("Authorization", bearer(STUDENT_ID, "teacher", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("所属单位"));
    }
    @Test
    @DisplayName("未知 audience 报参数错误（400），空串按不限制处理")
    void testUnknownAudienceRejected() throws Exception {
        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "PARENT")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("注册选项端点匿名可读，但暴露范围**严格小于** /org/tree（不含行政信息化）")
    void testRegisterOrgOptionsIsAnonymousButNarrower() throws Exception {
        // ① 匿名可读（注册页在登录前就要用它）——刻意**不带 Authorization 头**
        mockMvc.perform(get("/api/v1/auth/register-org-options")
                        .param("audience", "STUDENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("2024级"));

        // ② 教工两条条线同样匿名可读
        mockMvc.perform(get("/api/v1/auth/register-org-options")
                        .param("audience", "FACULTY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..fullPathName", hasItem("所属单位 计算机学院")));

        mockMvc.perform(get("/api/v1/auth/register-org-options")
                        .param("audience", "LOGISTICS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..fullPathName", hasItem("所属单位 后勤维修中心")));

        // ③ 关键安全断言：行政信息化**不在**公开端点里。
        //    「没人能自助注册到 ADMIN 条线」⇒ 它的子树没理由匿名可见；
        //    这是本端点相对 /org/tree 收窄的那一条，必须锁住，否则将来"统一一下"
        //    改成直接转调 /org/tree 就悄悄扩大了公开面。
        mockMvc.perform(get("/api/v1/auth/register-org-options")
                        .param("audience", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // ④ 缺参数 / 未知条线一律参数错误，不静默退化成"返回全量树"
        mockMvc.perform(get("/api/v1/auth/register-org-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/v1/auth/register-org-options")
                        .param("audience", "PARENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // ⑤ 对照自证：放开的是**新端点**，不是 /org/tree——后者匿名仍然 401。
        //    没有这一条，③④ 在"整个 /org/** 都被放开了"的世界里依然全绿。
        mockMvc.perform(get("/api/v1/org/tree")
                        .param("audience", "STUDENT"))
                .andExpect(status().isUnauthorized());
    }
}
