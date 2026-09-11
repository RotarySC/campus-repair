package com.campus.repair.framework.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 静态资源映射配置。
 *
 * <p>上传接口 {@code POST /api/v1/common/upload} 返回的是 {@code /uploads/{yyyyMMdd}/{uuid}.jpg} 形式的相对 URL，
 * 报修实况照片、完工防伪照片与时间轴九宫格均以 {@code <img :src="...">} 直接引用该 URL。
 * 若不做映射，这些图片即使已成功落盘也无法被浏览器取到，Visual Diff 前后对比与存证照片会全部裂图。</p>
 */
@Configuration
@Slf4j
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.file.upload-dir:./uploads/}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        String location = uploadPath.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }

        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
        log.info("[WebMvcConfig] 上传目录静态资源映射已注册: /uploads/** -> {}", location);
    }
}
