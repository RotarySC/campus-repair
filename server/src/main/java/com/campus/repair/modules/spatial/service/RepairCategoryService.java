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

        categoryMapper.updateById(existing);
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
