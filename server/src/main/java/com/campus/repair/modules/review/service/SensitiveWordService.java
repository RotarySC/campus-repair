package com.campus.repair.modules.review.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.review.constant.ContentReviewCodes;
import com.campus.repair.modules.review.dto.SensitiveWordRule;
import com.campus.repair.modules.review.entity.SysSensitiveWord;
import com.campus.repair.modules.review.mapper.SysSensitiveWordMapper;
import com.campus.repair.modules.review.support.ContentTextNormalizer;
import com.campus.repair.modules.review.vo.SensitiveWordVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 敏感词表的读写与内存快照。
 *
 * <p>初筛是每次提单都会走的热路径，不能每次都查表：启用的词在这里维护成一份
 * {@code volatile List<SensitiveWordRule>} 快照，{@link #activeRules()} 零锁读；
 * 写操作完成后 {@link #refresh()} 重建快照（用 {@code volatile} 而非缓存框架，
 * 词表量级小、失效语义简单，避免为一张几十行的字典引入 Redis/缓存一致性）。
 *
 * <p><b>事务边界</b>：刷新安排在事务<b>完成之后</b>（{@link TransactionSynchronization#afterCompletion}）。
 * 若在事务内刷新，会把「尚未提交、可能回滚」的词写进全局快照——其他请求短暂拿着一条数据库里
 * 不存在的词做拦截。没有事务时直接刷新。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SensitiveWordService {

    private final SysSensitiveWordMapper wordMapper;

    /** 启用词的内存快照；null 表示还没加载过（懒加载，避免启动早期表还没建好）。 */
    private volatile List<SensitiveWordRule> rules;

    // ------------------------------------------------------------------
    // 快照
    // ------------------------------------------------------------------

    /** 当前启用词快照（按分类+词排序，命中顺序稳定，便于测试与复核页解释）。 */
    public List<SensitiveWordRule> activeRules() {
        List<SensitiveWordRule> snapshot = this.rules;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (this.rules == null) {
                refresh();
            }
            return this.rules == null ? List.of() : this.rules;
        }
    }

    /** 词表里是否有任一启用词出现在归一化正文中（纯内存，无数据库访问）。 */
    public boolean containsHit(String normalizedText) {
        if (!StringUtils.hasText(normalizedText)) {
            return false;
        }
        String text = ContentTextNormalizer.normalize(normalizedText);
        for (SensitiveWordRule rule : activeRules()) {
            if (text.contains(rule.word())) {
                return true;
            }
        }
        return false;
    }

    /** 从数据库重建内存快照；启动就绪后预热一次，写操作后由 {@link #refreshAfterWrite()} 触发。 */
    public synchronized void refresh() {
        List<SysSensitiveWord> rows = wordMapper.selectList(null);
        this.rules = buildActiveRules(rows);
        log.info("[SensitiveWordService] 敏感词快照刷新完成：启用 {} 条", this.rules.size());
    }

    /** 启动预热；失败不阻塞启动（首次调用 {@link #activeRules()} 会再试一次）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            refresh();
        } catch (Exception e) {
            log.warn("[SensitiveWordService] 启动预热敏感词失败，将在首次初筛时重试: {}", e.getMessage());
        }
    }

    /**
     * 纯函数：把数据库行转成「可命中规则」快照。单测直接拿它验证
     * 「停用词不参与命中」，不必启动 Spring。
     */
    public static List<SensitiveWordRule> buildActiveRules(List<SysSensitiveWord> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(row -> row != null && StringUtils.hasText(row.getWord()))
                .filter(row -> row.getEnabled() == null || row.getEnabled() != 0)
                .map(row -> new SensitiveWordRule(
                        ContentTextNormalizer.normalizeWord(row.getWord()),
                        row.getCategory()))
                .filter(rule -> StringUtils.hasText(rule.word()) && StringUtils.hasText(rule.category()))
                .distinct()
                .sorted(Comparator.comparing(SensitiveWordRule::category)
                        .thenComparing(SensitiveWordRule::word))
                .toList();
    }

    // ------------------------------------------------------------------
    // 词表 CRUD（PLATFORM_ADMIN）
    // ------------------------------------------------------------------

    public IPage<SensitiveWordVO> page(int page, int size, String category, Integer enabled) {
        LambdaQueryWrapper<SysSensitiveWord> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(category)) {
            query.eq(SysSensitiveWord::getCategory, category.trim().toUpperCase());
        }
        if (enabled != null) {
            query.eq(SysSensitiveWord::getEnabled, enabled > 0 ? 1 : 0);
        }
        query.orderByDesc(SysSensitiveWord::getCreatedAt).orderByDesc(SysSensitiveWord::getId);

        IPage<SysSensitiveWord> raw = wordMapper.selectPage(new Page<>(page, size), query);
        Page<SensitiveWordVO> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(SensitiveWordService::toVO).toList());
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public SensitiveWordVO create(String word, String category, Long createdBy) {
        String trimmedWord = validateWord(word);
        String normalizedCategory = validateCategory(category);
        assertWordNotExists(trimmedWord, null);

        SysSensitiveWord row = new SysSensitiveWord();
        row.setWord(trimmedWord);
        row.setCategory(normalizedCategory);
        row.setEnabled(1);
        row.setCreatedBy(createdBy);
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            wordMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw duplicateWord();
        }
        refreshAfterWrite();
        return toVO(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public SensitiveWordVO update(Long id, String word, String category, Integer enabled) {
        SysSensitiveWord row = requireWord(id);
        if (word != null) {
            String trimmedWord = validateWord(word);
            assertWordNotExists(trimmedWord, id);
            row.setWord(trimmedWord);
        }
        if (category != null) {
            row.setCategory(validateCategory(category));
        }
        if (enabled != null) {
            row.setEnabled(enabled > 0 ? 1 : 0);
        }
        row.setUpdatedAt(LocalDateTime.now());
        try {
            wordMapper.updateById(row);
        } catch (DuplicateKeyException e) {
            throw duplicateWord();
        }
        refreshAfterWrite();
        return toVO(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireWord(id);
        wordMapper.deleteById(id);
        refreshAfterWrite();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private SysSensitiveWord requireWord(Long id) {
        SysSensitiveWord row = id == null ? null : wordMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "敏感词不存在");
        }
        return row;
    }

    private String validateWord(String word) {
        if (!StringUtils.hasText(word)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "敏感词不能为空");
        }
        String trimmed = word.trim();
        if (trimmed.length() > ContentReviewCodes.MAX_WORD_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                    "敏感词不能超过 " + ContentReviewCodes.MAX_WORD_LENGTH + " 个字");
        }
        if (ContentTextNormalizer.normalizeWord(trimmed).isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "敏感词不能只包含空白/零宽字符");
        }
        return trimmed;
    }

    private String validateCategory(String category) {
        if (!StringUtils.hasText(category)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "敏感词分类不能为空");
        }
        String normalized = category.trim().toUpperCase();
        if (!ContentReviewCodes.WORD_CATEGORIES.contains(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                    "敏感词分类只能是 AD / ABUSE / PRIVACY / IRRELEVANT");
        }
        return normalized;
    }

    /** 重复词给业务错误而不是 500：UNIQUE(word) 在 MySQL 与 H2 的报错文案不同，先自己判。 */
    private void assertWordNotExists(String word, Long excludeId) {
        String normalized = ContentTextNormalizer.normalizeWord(word);
        List<SysSensitiveWord> all = wordMapper.selectList(null);
        for (SysSensitiveWord row : all) {
            if (excludeId != null && excludeId.equals(row.getId())) {
                continue;
            }
            if (row.getWord() == null) {
                continue;
            }
            if (row.getWord().equalsIgnoreCase(word)
                    || ContentTextNormalizer.normalizeWord(row.getWord()).equals(normalized)) {
                throw duplicateWord();
            }
        }
    }

    private static BusinessException duplicateWord() {
        return new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "该敏感词已存在，请勿重复添加");
    }

    private void refreshAfterWrite() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    try {
                        refresh();
                    } catch (Exception e) {
                        log.warn("[SensitiveWordService] 写后刷新快照失败: {}", e.getMessage());
                    }
                }
            });
        } else {
            refresh();
        }
    }

    private static SensitiveWordVO toVO(SysSensitiveWord row) {
        return SensitiveWordVO.builder()
                .id(row.getId())
                .word(row.getWord())
                .category(row.getCategory())
                .enabled(row.getEnabled())
                .createdAt(row.getCreatedAt())
                .updatedAt(row.getUpdatedAt())
                .build();
    }
}
