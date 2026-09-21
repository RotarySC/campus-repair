package com.campus.repair.modules.spatial.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.mapper.RepairCategoryMapper;
import com.campus.repair.modules.spatial.vo.RepairCategoryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RepairCategoryService {

    /** 助手同义词 / 自助排查提示两列的长度上限，与 `VARCHAR(255)` 对齐 */
    private static final int ASSISTANT_TEXT_MAX_LENGTH = 255;

    private final RepairCategoryMapper categoryMapper;

    public List<RepairCategoryVO> getCategoryTree(boolean onlyEnabled) {
        LambdaQueryWrapper<RepairCategory> query = new LambdaQueryWrapper<RepairCategory>()
                .orderByAsc(RepairCategory::getSortOrder)
                .orderByAsc(RepairCategory::getId);
        if (onlyEnabled) {
            query.eq(RepairCategory::getStatus, 1);
        }

        List<RepairCategory> allCategories = categoryMapper.selectList(query);
        return buildTree(allCategories, 0L);
    }

    private List<RepairCategoryVO> buildTree(List<RepairCategory> categories, Long parentId) {
        List<RepairCategoryVO> result = new ArrayList<>();
        for (RepairCategory cat : categories) {
            if (Objects.equals(cat.getParentId(), parentId)) {
                RepairCategoryVO vo = new RepairCategoryVO();
                BeanUtils.copyProperties(cat, vo);
                vo.setChildren(buildTree(categories, cat.getId()));
                result.add(vo);
            }
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public RepairCategory createCategory(RepairCategory category) {
        if (!StringUtils.hasText(category.getName())) {
            throw new BusinessException("分类名称不能为空");
        }

        if (category.getParentId() != null && category.getParentId() > 0) {
            RepairCategory parent = categoryMapper.selectById(category.getParentId());
            if (parent == null) throw new BusinessException("父级分类不存在");
            category.setLevel(2);
            if (!StringUtils.hasText(category.getWorkerSkillCode())) {
                throw new BusinessException("二级细类必须指定维修工种代码");
            }
        } else {
            category.setParentId(0L);
            category.setLevel(1);
        }

        if (category.getSlaHours() == null || category.getSlaHours() <= 0) {
            category.setSlaHours(24);
        }
        if (category.getStatus() == null) category.setStatus(1);
        if (category.getSortOrder() == null) category.setSortOrder(0);
        validateAssistantFields(category);

        categoryMapper.insert(category);
        return category;
    }

    public void updateCategory(RepairCategory category) {
        RepairCategory existing = categoryMapper.selectById(category.getId());
        if (existing == null) throw new BusinessException("分类不存在");

        existing.setName(category.getName());
        existing.setWorkerSkillCode(category.getWorkerSkillCode());
        if (category.getSlaHours() != null) existing.setSlaHours(category.getSlaHours());
        if (category.getSortOrder() != null) existing.setSortOrder(category.getSortOrder());
        if (category.getStatus() != null) existing.setStatus(category.getStatus());
        // 助手两列：传 null 表示"本次不修改"（updateById 会跳过 null 字段），传空串才是清空。
        // 字典页的输入框清空后给的是 ''，因此"清空同义词/自助提示"能真正落库。
        if (category.getKeywords() != null) existing.setKeywords(category.getKeywords());
        if (category.getSelfHelpTip() != null) existing.setSelfHelpTip(category.getSelfHelpTip());
        validateAssistantFields(existing);

        categoryMapper.updateById(existing);
    }

    /**
     * 助手同义词/自助排查提示的长度护栏。
     *
     * <p>两列都是 {@code VARCHAR(255)}：超长若放任写入，MySQL 严格模式会直接报错、
     * 非严格模式会**静默截断**（截断后同义词表会缺词，而且是"看起来正常"的静默失效），
     * 所以在这里显式拒绝。
     */
    private void validateAssistantFields(RepairCategory category) {
        if (tooLong(category.getKeywords(), ASSISTANT_TEXT_MAX_LENGTH)) {
            throw new BusinessException("助手同义词最长 " + ASSISTANT_TEXT_MAX_LENGTH + " 个字符");
        }
        if (tooLong(category.getSelfHelpTip(), ASSISTANT_TEXT_MAX_LENGTH)) {
            throw new BusinessException("自助排查提示最长 " + ASSISTANT_TEXT_MAX_LENGTH + " 个字符");
        }
    }

    private boolean tooLong(String text, int maxLength) {
        return text != null && text.length() > maxLength;
    }

    public void deleteCategory(Long id) {
        Long childCount = categoryMapper.selectCount(new LambdaQueryWrapper<RepairCategory>()
                .eq(RepairCategory::getParentId, id));
        if (childCount > 0) {
            throw new BusinessException("存在子级分类，无法直接删除");
        }
        categoryMapper.deleteById(id);
    }

    public RepairCategory getCategoryById(Long id) {
        return categoryMapper.selectById(id);
    }
}
