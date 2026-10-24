package com.campus.repair.modules.identity.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.constant.IdentityCodes;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.identity.dto.IdentityRosterImportRow;
import com.campus.repair.modules.identity.entity.IdentityRoster;
import com.campus.repair.modules.identity.mapper.IdentityRosterMapper;
import com.campus.repair.modules.identity.vo.IdentityImportResultVO;
import com.campus.repair.modules.identity.vo.IdentityRosterVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 校内人员名单维护：分页查询、Excel 导入（按 username 幂等 upsert）、在册/离校切换。
 *
 * <p>刻意**没有删除**能力：离校/离职只置 {@code status=0}，行必须留下——审核轨迹要能回指。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdentityRosterService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final DateTimeFormatter BATCH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final IdentityRosterMapper rosterMapper;
    private final IdentityRegistrationService registrationService;

    public IPage<IdentityRosterVO> page(int page, int size, String keyword, Integer status) {
        Page<IdentityRoster> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<IdentityRoster> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            String trimmed = keyword.trim();
            query.and(w -> w.like(IdentityRoster::getUsername, trimmed)
                    .or().like(IdentityRoster::getRealName, trimmed)
                    .or().like(IdentityRoster::getPhone, trimmed));
        }
        if (status != null) {
            query.eq(IdentityRoster::getStatus, status);
        }
        query.orderByDesc(IdentityRoster::getId);

        IPage<IdentityRoster> raw = rosterMapper.selectPage(pageParam, query);
        Page<IdentityRosterVO> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(this::toVO).toList());
        return result;
    }

    /**
     * 导入名单 Excel。
     *
     * <p>幂等口径：按 {@code username}（大小写不敏感）判断是新增还是覆盖更新；同一份文件重复导入
     * 只会得到 {@code updated}，不会产生重复行。非法行跳过并逐行回报，**不影响**其余行入库。
     */
    @Transactional(rollbackFor = Exception.class)
    public IdentityImportResultVO importRoster(MultipartFile file, Long operatorId, String batch) {
        List<IdentityRosterImportRow> rows = readRows(file);
        IdentityImportResultVO result = new IdentityImportResultVO();
        result.setTotal(rows.size());

        String importBatch = StringUtils.hasText(batch)
                ? batch.trim()
                : "import-" + BATCH_FORMATTER.format(LocalDateTime.now());

        // 行号从 2 起：Excel 第 1 行是表头（与 UserExcelImportListener 的 context 行号口径一致）
        int rowNumber = 1;
        for (IdentityRosterImportRow row : rows) {
            rowNumber++;
            Integer currentRow = rowNumber;
            try {
                String error = upsertRow(row, operatorId, importBatch, result);
                if (error != null) {
                    result.recordError(currentRow, error);
                }
            } catch (Exception e) {
                log.warn("[IdentityRosterService] 名单导入第 {} 行失败: {}", currentRow, e.getMessage());
                result.recordError(currentRow, "写库失败: " + e.getMessage());
            }
        }

        log.info("[IdentityRosterService] 名单导入完成 batch={} total={} created={} updated={} skipped={}",
                importBatch, result.getTotal(), result.getCreated(), result.getUpdated(), result.getSkipped());
        return result;
    }

    /** 处理一行；返回 null 表示成功，返回字符串表示该行被跳过及原因。 */
    private String upsertRow(IdentityRosterImportRow row, Long operatorId, String importBatch,
                             IdentityImportResultVO result) {
        String username = row.getUsername() == null ? null : row.getUsername().trim();
        String realName = row.getRealName() == null ? null : row.getRealName().trim();

        if (!StringUtils.hasText(username) || !StringUtils.hasText(realName)) {
            return "学号/工号与真实姓名均为必填项";
        }

        String userType = StringUtils.hasText(row.getUserType())
                ? row.getUserType().trim().toUpperCase(Locale.ROOT)
                : null;
        if (userType == null) {
            return "组织条线为必填项";
        }
        if (!IdentityCodes.ALL_BRANCHES.contains(userType)) {
            return "所属条线不合法: " + row.getUserType();
        }

        String phone = StringUtils.hasText(row.getPhone()) ? row.getPhone().trim() : null;
        if (phone != null && !PHONE_PATTERN.matcher(phone).matches()) {
            return "手机号格式不合法: " + phone;
        }

        String department = StringUtils.hasText(row.getDepartment()) ? row.getDepartment().trim() : null;
        LocalDateTime now = LocalDateTime.now();
        IdentityRoster existing = registrationService.findByUsername(username);

        if (existing == null) {
            IdentityRoster roster = new IdentityRoster();
            roster.setUsername(username);
            roster.setRealName(realName);
            roster.setUserType(userType);
            roster.setDepartment(department);
            roster.setPhone(phone);
            roster.setStatus(1);
            roster.setImportBatch(importBatch);
            roster.setImportedBy(operatorId);
            roster.setCreatedAt(now);
            roster.setUpdatedAt(now);
            rosterMapper.insert(roster);
            result.setCreated(result.getCreated() + 1);
            return null;
        }

        // 覆盖更新：离校行被重新导入视为「重新在册」（导入人明确要恢复它）
        existing.setUsername(username);
        existing.setRealName(realName);
        existing.setUserType(userType);
        existing.setDepartment(department);
        existing.setPhone(phone);
        existing.setStatus(1);
        existing.setImportBatch(importBatch);
        existing.setImportedBy(operatorId);
        existing.setUpdatedAt(now);
        rosterMapper.updateById(existing);
        result.setUpdated(result.getUpdated() + 1);
        return null;
    }

    /** 标记在册/离校（不删行）。status 只允许 0/1。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "名单状态只允许 0-离校/离职 或 1-在册");
        }
        IdentityRoster roster = rosterMapper.selectById(id);
        if (roster == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "名单行不存在");
        }
        roster.setStatus(status);
        roster.setUpdatedAt(LocalDateTime.now());
        rosterMapper.updateById(roster);
    }

    private IdentityRosterVO toVO(IdentityRoster roster) {
        return IdentityRosterVO.builder()
                .id(roster.getId())
                .username(roster.getUsername())
                .realName(roster.getRealName())
                .userType(roster.getUserType())
                .department(roster.getDepartment())
                .phone(roster.getPhone())
                .status(roster.getStatus())
                .importBatch(roster.getImportBatch())
                .importedBy(roster.getImportedBy())
                .createdAt(roster.getCreatedAt())
                .updatedAt(roster.getUpdatedAt())
                .build();
    }

    /** 一次性读全表：名单文件通常几千行，流式写库会放大事务，直接内存处理更简单。 */
    private List<IdentityRosterImportRow> readRows(MultipartFile file) {
        List<IdentityRosterImportRow> rows = new ArrayList<>();
        try (InputStream inputStream = file.getInputStream()) {
            EasyExcel.read(inputStream, IdentityRosterImportRow.class,
                    new AnalysisEventListener<IdentityRosterImportRow>() {
                        @Override
                        public void invoke(IdentityRosterImportRow data, AnalysisContext context) {
                            rows.add(data);
                        }

                        @Override
                        public void doAfterAllAnalysed(AnalysisContext context) {
                            // no-op
                        }
                    }).sheet().doRead();
            return rows;
        } catch (Exception e) {
            log.error("[IdentityRosterService] 解析名单 Excel 失败", e);
            throw new BusinessException("导入 Excel 文件解析异常: " + e.getMessage());
        }
    }
}
