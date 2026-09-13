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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守卫用例：源码里**不得再出现旧角色码**（2026-09-13 身份模型改名，见
 * `docs/20260913-1118-campus-repair-system-identity-role-model-design.md` §11 批次 1）。
 *
 * <p>为什么需要它：44 处 `@PreAuthorize` 由脚本机械改名，**漏改一处就是静默的放权或降权**
 * ——测试很可能仍然是绿的（例如某端点少了一个角色、或某个端点仍然放行旧码对应的权限）。
 * 本用例把「漏改」变成编译期之后立刻可见的失败。
 *
 * <p>扫描范围只含 Java 源码的**非注释行**：注释里提到旧码属于解释历史（例如说明 P0 成因），
 * 不该被判失败；SQL 种子里的旧码是历史审计数据的兼容问题，另由批次 4 处理。
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

    @Test
    @DisplayName("server/src 下的 Java 源码不得再出现旧角色码（非注释行）")
    void testNoLegacyRoleCodesInSource() throws IOException {
        Path base = resolveSourceRoot();
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(base)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String trimmed = lines.get(i).trim();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        continue; // 注释：允许解释历史
                    }
                    for (String code : LEGACY_CODES) {
                        if (lines.get(i).contains(code)) {
                            offenders.add(base.relativize(file) + ":" + (i + 1) + "  " + trimmed);
                        }
                    }
                }
            }
        }

        assertThat(offenders)
                .as("发现旧角色码，说明改名漏改（旧码 -> 新职能码/条线码见设计文档 §4）")
                .isEmpty();
    }

    /** surefire 的工作目录是模块根（server/），但为稳妥起见两种布局都试。 */
    private Path resolveSourceRoot() {
        for (String candidate : List.of("src/main/java", "server/src/main/java")) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        throw new IllegalStateException("找不到 Java 源码根目录（src/main/java）");
    }
}
