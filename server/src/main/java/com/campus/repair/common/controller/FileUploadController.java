package com.campus.repair.common.controller;

import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Tag(name = "通用文件上传服务", description = "提供现场报修与完工照片上传映射")
@RestController
@RequestMapping("/api/v1/common")
@Slf4j
public class FileUploadController {

    @Value("${app.file.upload-dir:./uploads/}")
    private String uploadDir;

    @Operation(summary = "单图片上传")
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public R<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        }

        if (!ext.matches("^\\.(jpg|jpeg|png|webp)$")) {
            throw new BusinessException("仅支持 JPG, PNG, WEBP 格式图片");
        }

        try {
            String dateFolder = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            File targetFolder = new File(uploadDir, dateFolder);
            if (!targetFolder.exists()) {
                targetFolder.mkdirs();
            }

            String filename = UUID.randomUUID().toString().replace("-", "") + ext;
            File targetFile = new File(targetFolder, filename);
            file.transferTo(targetFile);

            String fileUrl = "/uploads/" + dateFolder + "/" + filename;
            return R.success("上传成功", Map.of("url", fileUrl));
        } catch (Exception e) {
            log.error("保存上传文件失败", e);
            throw new BusinessException("保存文件失败: " + e.getMessage());
        }
    }
}
