package com.campus.repair.common.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 演示占位照片生成器单测。
 *
 * <p>该链路的失败模式在浏览器侧表现为「工单详情 Visual Diff 两张图全裂 + 控制台 404」，
 * 纯后端接口测试查不出来，所以在这里把「清单完整 / 文件是真 JPEG / 不覆盖真实上传」三点锁死。
 */
class DemoImageSeederTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateAllDemoImagesAsDecodableJpeg() throws Exception {
        DemoImageSeeder seeder = new DemoImageSeeder();

        int created = seeder.seed(tempDir);

        assertEquals(DemoImageSeeder.DEMO_IMAGES.size(), created, "应生成清单中的全部占位图");
        for (String name : DemoImageSeeder.DEMO_IMAGES) {
            Path file = tempDir.resolve("demo").resolve(name);
            assertTrue(Files.exists(file), name + " 未生成");
            assertTrue(Files.size(file) > 0, name + " 为空文件");

            // 必须是真正能被解码的 JPEG（仅断言魔数不够：写坏一个字节也会通过魔数检查）
            BufferedImage decoded = ImageIO.read(file.toFile());
            assertNotNull(decoded, name + " 不是可解码的 JPEG");
            assertTrue(decoded.getWidth() > 0 && decoded.getHeight() > 0, name + " 尺寸非法");
        }
    }

    @Test
    void shouldNotOverwriteExistingFiles() throws Exception {
        DemoImageSeeder seeder = new DemoImageSeeder();
        Path demoDir = tempDir.resolve("demo");
        Files.createDirectories(demoDir);

        // 模拟一张「真实上传」的现场照片
        String first = DemoImageSeeder.DEMO_IMAGES.get(0);
        Path occupied = demoDir.resolve(first);
        byte[] sentinel = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
        Files.write(occupied, sentinel);

        int created = seeder.seed(tempDir);

        assertEquals(DemoImageSeeder.DEMO_IMAGES.size() - 1, created, "已存在的文件必须跳过");
        assertArrayEqualsSentinel(sentinel, Files.readAllBytes(occupied));

        // 幂等：第二次执行为 0
        assertEquals(0, seeder.seed(tempDir), "重复执行不应再创建任何文件");
    }

    private void assertArrayEqualsSentinel(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length, "占位图生成器覆盖了已存在的文件");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "第 " + i + " 字节被改写");
        }
    }

    @Test
    void demoListShouldMatchSeedDataReferences() throws IOException {
        // 清单必须与 data-demo.sql 实际引用的路径一一对应，否则仍会有 404
        String seedSql = Files.readString(Path.of("src/main/resources/data-demo.sql"));
        List<String> referenced = seedSql.lines()
                .flatMap(line -> java.util.regex.Pattern.compile("/uploads/demo/([A-Za-z0-9\\-]+\\.jpg)")
                        .matcher(line)
                        .results()
                        .map(m -> m.group(1)))
                .distinct()
                .sorted()
                .toList();
        List<String> declared = DemoImageSeeder.DEMO_IMAGES.stream().sorted().toList();
        assertEquals(referenced, declared, "占位图清单与 data-demo.sql 的引用不一致");
    }
}
