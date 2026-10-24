package com.campus.repair.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守卫用例：源码里**不得再出现旧角色码**（2026-09-13 身份模型改名，批次 1）。
 *
 * <p>为什么需要它：44 处 `@PreAuthorize` 由脚本机械改名，**漏改一处就是静默的放权或降权**
 * ——测试很可能仍然是绿的（例如某端点少了一个角色、或某个端点仍然放行旧码对应的权限）。
 * 本用例把「漏改」变成编译期之后立刻可见的失败。
 *
 * <p>扫描范围：① `src/main/java` 的**非注释行**（注释里提到旧码属于解释历史，例如说明 P0 成因）；
 * ② 前端 `apps/admin-web/src` 的 `.ts` / `.vue`；
 * ③ 共享包 `packages/shared/src` 的 `.ts`——**这条是 2026-09-13 补的**：前两处都扫了，唯独
 * 共享包没扫，而 `OperatorRoleLabels` 恰好住在那里，等于给旧码留了一个「扫不到」的后门。
 * **历史词表的展示层除外**，见 {@link #LEGACY_DISPLAY_ALLOWLIST} / {@link #SHARED_DISPLAY_ALLOWLIST}。
 * SQL 种子里的旧码已随批次 4 改写为新职能码（`data-demo.sql`），生产库的历史行由展示层兼容翻译。
 */
class NoLegacyRoleCodeTest {

    /** 用拼接构造，避免本文件自身命中。 */
    private static final List<String> LEGACY_CODES = List.of(
            "SUPER" + "_ADMIN",
            "LOGISTICS" + "_ADMIN",
            "REPAIR" + "_WORKER",
            "USER" + "_STUDENT",
            "USER" + "_TEACHER"
    );

    /**
     * 允许保留旧码的**展示层**文件（相对 `apps/admin-web/src`）。
     *
     * <p>时间轴要渲染**改名之前**落库的历史审计行，这些行的 `operator_role` 就是旧角色码；
     * 把旧码从它的配色表与文案表里删掉，历史日志会直接显示裸码并丢掉配色——所以这类
     * 「只把历史数据翻译成人话」的文件必须豁免。它们不参与任何身份/权限判定。
     *
     * <p>白名单**刻意只列这一个文件**：任何**新增**文件出现旧码（路由、守卫、组合式、视图）
     * 都应让本用例失败。
     */
    private static final Set<String> LEGACY_DISPLAY_ALLOWLIST = Set.of(
            "components/WorkOrderTimeline.vue"
    );

    /**
     * 共享包里允许保留旧码的**展示层**文件（相对 `packages/shared/src`）。
     *
     * <p>与上面同理：`OperatorRoleLabels` 是把「改名之前落库的历史审计行」翻译成人话的唯一映射表，
     * 删掉旧键历史日志就会显示裸码。它同样不参与任何身份/权限判定。
     * 白名单**刻意只列这一个文件**——`types/*.ts`、`constants/*.ts`、`api/*.ts` 出现旧码必须失败。
     */
    private static final Set<String> SHARED_DISPLAY_ALLOWLIST = Set.of(
            "utils/format.ts"
    );

    @Test
    @DisplayName("server/src 下的 Java 源码不得再出现旧角色码（非注释行）")
    void testNoLegacyRoleCodesInJavaSource() throws IOException {
        List<String> offenders = scan(resolveJavaSourceRoot(), Set.of(), ".java");
        assertThat(offenders)
                .as("发现旧角色码，说明改名漏改（旧码 -> 新职能码/条线码见 `CONTEXT.md`）")
                .isEmpty();
    }

    @Test
    @DisplayName("前端源码不得再出现旧角色码（历史词表展示层除外）")
    void testNoLegacyRoleCodesInFrontend() throws IOException {
        List<String> offenders = scan(resolveFrontendSourceRoot(), LEGACY_DISPLAY_ALLOWLIST, ".ts", ".vue");
        assertThat(offenders)
                .as("前端出现旧角色码：身份/权限判定一律用职能（useIdentity 的 has('职能') / roleCodes），"
                        + "只有历史词表展示层可保留旧码")
                .isEmpty();
    }

    @Test
    @DisplayName("共享包 packages/shared/src 不得再出现旧角色码（仅 OperatorRoleLabels 兼容表除外）")
    void testNoLegacyRoleCodesInSharedPackage() throws IOException {
        List<String> offenders = scan(resolveSharedSourceRoot(), SHARED_DISPLAY_ALLOWLIST, ".ts");
        assertThat(offenders)
                .as("共享包出现旧角色码：只有 utils/format.ts 的历史词表兼容映射可以保留，"
                        + "类型定义/常量/请求层出现旧码会让下游误以为它还是有效取值")
                .isEmpty();
    }

    private List<String> scan(Path root, Set<String> allowlist, String... extensions) throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> endsWithAny(p, extensions)).toList()) {
                if (allowlist.contains(relative(root, file))) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String trimmed = lines.get(i).trim();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        continue; // 注释：允许解释历史
                    }
                    for (String code : LEGACY_CODES) {
                        if (lines.get(i).contains(code)) {
                            offenders.add(relative(root, file) + ":" + (i + 1) + "  " + trimmed);
                        }
                    }
                }
            }
        }
        return offenders;
    }

    private static boolean endsWithAny(Path path, String[] extensions) {
        String name = path.toString();
        for (String extension : extensions) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** surefire 的工作目录是模块根（server/），但为稳妥起见两种布局都试。 */
    private Path resolveJavaSourceRoot() {
        return resolve("src/main/java", "server/src/main/java");
    }

    /** 前端扫描根：同样兼容「工作目录 = server/」与「工作目录 = 仓库根」两种布局。 */
    private Path resolveFrontendSourceRoot() {
        return resolve("../apps/admin-web/src", "apps/admin-web/src");
    }

    /** 共享包扫描根：同上，兼容两种工作目录布局。 */
    private Path resolveSharedSourceRoot() {
        return resolve("../packages/shared/src", "packages/shared/src");
    }

    private Path resolve(String... candidates) {
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        throw new IllegalStateException("找不到源码根目录：" + String.join(" / ", candidates));
    }
}
