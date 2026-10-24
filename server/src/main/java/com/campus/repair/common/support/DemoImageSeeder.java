package com.campus.repair.common.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 本地演示占位照片生成器（仅 local profile）。
 *
 * <p>背景：{@code data-demo.sql} 的工单/评价/审计记录引用了 19 个
 * {@code /uploads/demo/xxx.jpg} 形式的演示照片路径，但 {@code uploads/} 目录是运行时产物
 * （已在 .gitignore 中忽略），因此干净克隆后这些路径必然 404：
 * 工单详情页的「现场维修实况对比（Visual Diff）」两张照片会双双裂图、控制台持续报 404。
 *
 * <p>做法：应用就绪后按固定清单补写确定性的占位 JPEG（渐变底色 + 图注），
 * 已存在的文件一律跳过，绝不覆盖真实上传的现场照片。删除该目录或注释掉本组件即可回到「裂图」状态。
 *
 * <p>用 Java 而非一次性脚本/二进制素材入库，是为了：① 不把二进制图片塞进 git；
 * ② 该链路可被 {@code DemoImageSeederTest} 持续验证（脚本无法被测试覆盖）。
 */
@Component
@Profile("local")
@Slf4j
public class DemoImageSeeder {

    /**
     * 与 data-demo.sql 中的引用逐一对应（fault-01~09 为报修实况，finish-09~19 为完工实况）。
     *
     * <p>fault-09 是 Phase 02 新增的「内容初筛命中 → 待复核」演示单（1021）的现场照片：
     * 该单需要一张附件图，复核页的附件缩略图/图片抽检演示才有素材。
     */
    static final List<String> DEMO_IMAGES = List.of(
            "fault-01.jpg", "fault-02.jpg", "fault-03.jpg", "fault-04.jpg",
            "fault-05.jpg", "fault-06.jpg", "fault-07.jpg", "fault-08.jpg",
            "fault-09.jpg",
            "finish-09.jpg", "finish-10.jpg", "finish-11.jpg", "finish-12.jpg",
            "finish-13.jpg", "finish-14.jpg", "finish-15.jpg", "finish-16.jpg",
            "finish-17.jpg", "finish-19.jpg"
    );

    /** 占位图尺寸：与手机现场拍照的比例接近，便于 Visual Diff 左图/右图对齐 */
    private static final int WIDTH = 480;
    private static final int HEIGHT = 640;

    @Value("${app.file.upload-dir:./uploads/}")
    private String uploadDir;

    @EventListener(ApplicationReadyEvent.class)
    public void seedDemoImagesOnStartup() {
        try {
            int created = seed(Paths.get(uploadDir).toAbsolutePath().normalize());
            if (created > 0) {
                log.info("[DemoImageSeeder] 已生成 {} 张演示占位照片 -> {}/demo/", created, uploadDir);
            }
        } catch (Exception e) {
            // 占位图失败不应影响应用启动
            log.warn("[DemoImageSeeder] 生成演示占位照片失败（不影响业务）: {}", e.getMessage());
        }
    }

    /**
     * 写入缺失的占位图，返回新建数量；已存在的文件跳过。
     *
     * @param absoluteUploadDir 已归一化为绝对路径的上传根目录
     */
    int seed(Path absoluteUploadDir) throws Exception {
        Path demoDir = absoluteUploadDir.resolve("demo");
        int created = 0;
        for (String name : DEMO_IMAGES) {
            Path target = demoDir.resolve(name);
            if (Files.exists(target)) {
                continue;
            }
            Files.createDirectories(demoDir);
            BufferedImage image = render(name);
            if (!ImageIO.write(image, "jpg", target.toFile())) {
                throw new IllegalStateException("当前 JVM 缺少 JPEG 编码器，无法生成 " + name);
            }
            created++;
        }
        return created;
    }

    /** 画一张确定性的渐变占位图（颜色由图名散列决定，同一图名每次生成一致） */
    private BufferedImage render(String name) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            float hue = Math.abs(name.hashCode() % 360) / 360f;
            Color top = Color.getHSBColor(hue, 0.28f, 0.92f);
            Color bottom = Color.getHSBColor(hue, 0.45f, 0.66f);
            g.setPaint(new GradientPaint(0, 0, top, 0, HEIGHT, bottom));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            g.setColor(new Color(255, 255, 255, 200));
            g.setStroke(new BasicStroke(3f));
            g.drawRect(18, 18, WIDTH - 37, HEIGHT - 37);

            g.setColor(new Color(31, 41, 55));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            g.drawString("DEMO", 40, 90);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            g.drawString("演示占位图（非真实现场照片）", 40, 128);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 26));
            g.drawString(name, 40, 172);

            String tip = name.startsWith("fault") ? "报修实况占位" : "完工实况占位";
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            g.drawString(tip, 40, HEIGHT - 48);
        } finally {
            g.dispose();
        }
        return image;
    }
}
