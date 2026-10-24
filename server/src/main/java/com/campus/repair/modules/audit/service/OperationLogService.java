package com.campus.repair.modules.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.audit.dto.OperationLogQueryRequest;
import com.campus.repair.modules.audit.entity.OperationLog;
import com.campus.repair.modules.audit.mapper.OperationLogMapper;
import com.campus.repair.modules.audit.vo.OperationLogVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 字典/参数变更审计的写入与查询。
 *
 * <p>设计取舍（`phase-04` D1/D2）：**显式记录而不是 AOP 切面**——切面拿不到「改前值」；
 * 记录调用与业务更新在**同一事务**里，更新失败就不会留下「改成功」的假日志。
 * 审计表 append-only：本类只做 {@code insert} 与分页读。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private static final DateTimeFormatter SPACE_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final TypeReference<Map<String, Object>> SNAPSHOT_TYPE = new TypeReference<>() {
    };

    private final OperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * 写入一行审计。**不吞异常**：快照/插入失败时让外层事务一起回滚，
     * 宁可这次改动没成功，也不要出现「业务改了、日志没落」的静默缺口。
     *
     * <p>签名刻意保持扁平（而不是传实体）：调用点必须显式决定
     * module/action/targetType 与前后值，读代码就能看出这次改动会留下什么证据。
     */
    @Transactional(rollbackFor = Exception.class)
    public void record(String module, String action, String targetType, Long targetId, String targetName,
                       Object before, Object after, Long operatorId, String operatorName, String operatorRole,
                       String remark) {
        OperationLog log = new OperationLog();
        log.setModule(module);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setTargetName(truncate(targetName, 128));
        log.setBeforeSnapshot(toJson(before));
        log.setAfterSnapshot(toJson(after));
        log.setOperatorId(operatorId);
        log.setOperatorName(truncate(operatorName, 32));
        log.setOperatorRole(operatorRole);
        log.setRemark(truncate(remark, 255));
        log.setCreatedAt(LocalDateTime.now());
        operationLogMapper.insert(log);
    }

    /**
     * 便捷重载：没有操作人上下文（如系统内部调用、存量测试直接调 Service）时**不落审计**，
     * 避免凭空造一个 operator_id=0 的假记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public void record(String module, String action, String targetType, Long targetId, String targetName,
                       Object before, Object after, OperationActor actor, String remark) {
        if (actor == null) {
            return;
        }
        record(module, action, targetType, targetId, targetName, before, after,
                actor.id(), actor.name(), actor.role(), remark);
    }

    /** 分页查询（默认按时间倒序，与「最近改了什么」的阅读顺序一致）。 */
    public Page<OperationLogVO> page(OperationLogQueryRequest request) {
        OperationLogQueryRequest query = request != null ? request : new OperationLogQueryRequest();
        int pageNo = query.getPage() != null && query.getPage() > 0 ? query.getPage() : 1;
        int pageSize = query.getSize() != null && query.getSize() > 0
                ? Math.min(query.getSize(), MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getModule()) && !"ALL".equalsIgnoreCase(query.getModule().trim())) {
            wrapper.eq(OperationLog::getModule, query.getModule().trim());
        }
        if (StringUtils.hasText(query.getTargetType())) {
            wrapper.eq(OperationLog::getTargetType, query.getTargetType().trim());
        }
        if (query.getTargetId() != null) {
            wrapper.eq(OperationLog::getTargetId, query.getTargetId());
        }
        LocalDateTime start = parseStart(query.getStart());
        if (start != null) {
            wrapper.ge(OperationLog::getCreatedAt, start);
        }
        LocalDateTime end = parseEnd(query.getEnd());
        if (end != null) {
            wrapper.le(OperationLog::getCreatedAt, end);
        }
        wrapper.orderByDesc(OperationLog::getCreatedAt).orderByDesc(OperationLog::getId);

        Page<OperationLog> entityPage = operationLogMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        List<OperationLogVO> records = entityPage.getRecords().stream().map(this::toVo).toList();
        Page<OperationLogVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    public OperationLogVO detail(Long id) {
        OperationLog entity = id != null ? operationLogMapper.selectById(id) : null;
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "审计记录不存在");
        }
        return toVo(entity);
    }

    private OperationLogVO toVo(OperationLog entity) {
        OperationLogVO vo = new OperationLogVO();
        vo.setId(entity.getId());
        vo.setModule(entity.getModule());
        vo.setAction(entity.getAction());
        vo.setTargetType(entity.getTargetType());
        vo.setTargetId(entity.getTargetId());
        vo.setTargetName(entity.getTargetName());
        vo.setBefore(parseSnapshot(entity.getBeforeSnapshot()));
        vo.setAfter(parseSnapshot(entity.getAfterSnapshot()));
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorName(entity.getOperatorName());
        vo.setOperatorRole(entity.getOperatorRole());
        vo.setRemark(entity.getRemark());
        vo.setCreatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(SPACE_DATETIME) : null);
        return vo;
    }

    private String toJson(Object snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("审计快照序列化失败", e);
        }
    }

    private Map<String, Object> parseSnapshot(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SNAPSHOT_TYPE);
        } catch (Exception e) {
            // 历史/人工写入的脏 JSON 不应让整个列表 500：降级返回原始文本，前端仍能展示
            log.warn("[OperationLog] 快照 JSON 解析失败，降级为原文展示: {}", e.getMessage());
            return Map.of("_raw", json);
        }
    }

    /** 起始时间：只给日期时取当日 00:00:00。 */
    private LocalDateTime parseStart(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim();
        if (text.length() <= 10) {
            return LocalDateTimes.parseDate(text).atStartOfDay();
        }
        return LocalDateTimes.parseDateTime(text);
    }

    /** 结束时间：只给日期时取当日 23:59:59，保证「选一天」能覆盖整天的记录。 */
    private LocalDateTime parseEnd(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim();
        if (text.length() <= 10) {
            return LocalDateTimes.parseDate(text).atTime(23, 59, 59);
        }
        return LocalDateTimes.parseDateTime(text);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * 时间解析小工具：把「日期串 / 空格时间 / ISO 时间」统一成 LocalDateTime，
     * 解析不了时回 400（而不是 500），前端筛选参数写错能一眼看出来。
     */
    private static final class LocalDateTimes {

        private LocalDateTimes() {
        }

        static java.time.LocalDate parseDate(String text) {
            try {
                return java.time.LocalDate.parse(text, DATE_ONLY);
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                        "时间筛选参数格式不正确（应为 yyyy-MM-dd）: " + text);
            }
        }

        static LocalDateTime parseDateTime(String text) {
            try {
                return LocalDateTime.parse(text, SPACE_DATETIME);
            } catch (DateTimeParseException ignored) {
                // 再试 ISO（前端 Date.toISOString() / 日期选择器 datetime 的默认格式）
            }
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                        "时间筛选参数格式不正确（应为 yyyy-MM-dd HH:mm:ss）: " + text);
            }
        }
    }
}
