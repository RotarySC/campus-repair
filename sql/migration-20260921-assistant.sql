-- ============================================================================
-- 迁移脚本：智能助手（故障类别判断 / 自助排查提示）
--   适用：已上线/已有数据的 MySQL 库
--   （H2 本地库是内存库，重启即按 schema-test.sql + data-demo.sql 重建，无需执行本脚本）
--   幂等：可重复执行——加列走 information_schema 判定，知识补齐是"改到目标值"的写法
--
--   前置：`repair_category` 表必须已存在。注意生产 `sql/schema.sql` 本身并不完整
--         （它只有 12 张表，连 repair_category / repair_order / sys_config 都没有），
--         所以真实库多半是靠历史建表脚本建的；执行前请先 `SHOW TABLES LIKE 'repair_category'`。
--   执行前请备份：repair_category、sys_config
--
--   口径（见仓库根 CONTEXT.md）：
--     · keywords      助手同义词，逗号/顿号/分号/空白分隔；**匹配时长度 < 2 的词一律忽略**
--                     所以这里刻意不写「水」「灯」「门」这类单字词
--     · self_help_tip 提交前自助排查提示，多个步骤用 | 分隔；只对二级细类（level=2）有意义
--     · 打分权重：叶子名命中 +10 > 关键词命中 +5 > 大类名命中 +3
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 加两列（幂等：已存在则跳过）
-- ---------------------------------------------------------------------------
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `repair_category` ADD COLUMN `keywords` VARCHAR(255) DEFAULT NULL COMMENT ''助手同义词，逗号/顿号/分号/空白分隔；匹配时长度<2的词一律忽略''',
    'SELECT ''keywords 列已存在，跳过'' AS msg')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'repair_category' AND COLUMN_NAME = 'keywords'
);
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `repair_category` ADD COLUMN `self_help_tip` VARCHAR(255) DEFAULT NULL COMMENT ''提交前自助排查提示，多个步骤用 | 分隔''',
    'SELECT ''self_help_tip 列已存在，跳过'' AS msg')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'repair_category' AND COLUMN_NAME = 'self_help_tip'
);
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------------
-- 2. 补齐一级大类（5 个）的同义词：命中权重最低，用于兜底
--    按 name 匹配而不是按 id——真实库的 id 未必与演示种子一致
-- ---------------------------------------------------------------------------
UPDATE `repair_category` SET `keywords` = '水暖,水管,上水,下水,供水,排水,龙头,地漏'
  WHERE `level` = 1 AND `name` = '水暖管道';
UPDATE `repair_category` SET `keywords` = '电气,用电,照明,电路,电线,配电,插座'
  WHERE `level` = 1 AND `name` = '电气照明';
UPDATE `repair_category` SET `keywords` = '暖通,空调,制冷,供暖,暖气'
  WHERE `level` = 1 AND `name` = '暖通空调';
UPDATE `repair_category` SET `keywords` = '门窗,门锁,玻璃,纱窗,窗户,门框'
  WHERE `level` = 1 AND `name` = '门窗锁具';
UPDATE `repair_category` SET `keywords` = '弱电,网络,网线,电话,监控,宽带'
  WHERE `level` = 1 AND `name` = '弱电网络';

-- ---------------------------------------------------------------------------
-- 3. 补齐二级细类（7 个）的同义词与自助排查提示
--    self_help_tip 一律 3 步（含 2 个 | 分隔符），与自检判据对应
-- ---------------------------------------------------------------------------
UPDATE `repair_category`
   SET `keywords` = '水龙头,龙头,漏水,滴水,渗水,关不紧,关不住,一直流,水管,跑水,水漫',
       `self_help_tip` = '先关闭角阀或入户水阀|用盆桶接水防止浸泡地面与楼下|拍照留存现场后提交报修'
 WHERE `level` = 2 AND `name` = '水龙头漏水';

UPDATE `repair_category`
   SET `keywords` = '下水道,地漏,堵塞,堵了,返水,冒水,反味,排水慢,马桶,蹲便,洗手池,积水',
       `self_help_tip` = '停止使用该排水口并清理表面毛发杂物|可先用皮搋子简易疏通，切勿倒入强酸|若已返水，先垫高地面物品'
 WHERE `level` = 2 AND `name` = '下水道堵塞';

UPDATE `repair_category`
   SET `keywords` = '灯管,灯泡,不亮,闪烁,熄灭,灯罩,筒灯,吸顶灯,声控灯,走廊灯,教室灯',
       `self_help_tip` = '先确认是否为整层停电或总闸跳闸|若仅单灯不亮，可确认灯管是否松动|更换灯泡请先断电'
 WHERE `level` = 2 AND `name` = '灯具损坏';

UPDATE `repair_category`
   SET `keywords` = '插座,跳闸,断电,没电,空开,空气开关,漏电,冒烟,焦味,发烫,插排,插座松动',
       `self_help_tip` = '先断开该插座上的所有电器，再复位空气开关|若复位后立即再次跳闸，请勿反复合闸|闻到焦糊味或见冒烟请立即远离并报修'
 WHERE `level` = 2 AND `name` = '插座跳闸';

UPDATE `repair_category`
   SET `keywords` = '空调,不制冷,不凉,不冷,制冷差,外机,内机,空调漏水,异响,遥控器,风小,温度不够',
       `self_help_tip` = '先确认电源与遥控器模式是否为「制冷」|清洗过滤网后静置 10 分钟再开机|室外温度过高时制冷偏弱属正常现象'
 WHERE `level` = 2 AND `name` = '空调不制冷';

UPDATE `repair_category`
   SET `keywords` = '门锁,锁芯,钥匙,打不开,关不上,锁不上,卡死,门把手,插销,防盗门,柜锁',
       `self_help_tip` = '请勿强行扭动钥匙，以免断在锁芯内|电子锁可先更换电池或用应急钥匙|确认是否为门框变形导致关不严'
 WHERE `level` = 2 AND `name` = '门锁损坏';

UPDATE `repair_category`
   SET `keywords` = '断网,没网,网线,端口,无线,宽带,路由器,交换机,上不了网,信号弱,频繁断线',
       `self_help_tip` = '先重启路由器与电脑网卡（禁用再启用）|确认网线两端插紧、指示灯正常|若同层多人同时断网，请一并注明'
 WHERE `level` = 2 AND `name` = '网络不通';

-- ---------------------------------------------------------------------------
-- 4. 助手四个系统参数（幂等：INSERT IGNORE 依赖 sys_config.config_key 的唯一键）
--    代码侧对这四个键都带默认值（1 / 0 / 3 / 10），所以即使这里没插进去，功能也照常可用
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `sys_config` (`config_key`, `config_value`, `description`) VALUES
('assistant_enabled',        '1', '智能助手总开关：0=前端不渲染入口且推荐接口返回空'),
('assistant_llm_enabled',    '0', '助手的大模型增强开关（默认关）：需同时注入 ASSISTANT_LLM_* 才生效'),
('assistant_max_candidates', '3', '助手一次最多返回的故障候选数量'),
('assistant_llm_escalate_below', '10', '大模型升级阈值：规则最高分低于它才调模型（10=非HIGH就升级，1=只noMatch，0=每次都调）');

-- ---------------------------------------------------------------------------
-- 5. 迁移后自检（预期值见每行注释）
-- ---------------------------------------------------------------------------
-- 5.1 两列已存在（预期 2）
-- SELECT COUNT(*) FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'repair_category'
--    AND COLUMN_NAME IN ('keywords','self_help_tip');

-- 5.2 所有启用叶子都有同义词（预期 0）
-- SELECT COUNT(*) FROM `repair_category`
--  WHERE `deleted` = 0 AND `status` = 1 AND `level` = 2
--    AND (`keywords` IS NULL OR `keywords` = '');

-- 5.3 所有启用叶子都有至少 2 步自助提示（预期 0）
-- SELECT COUNT(*) FROM `repair_category`
--  WHERE `deleted` = 0 AND `status` = 1 AND `level` = 2
--    AND (`self_help_tip` IS NULL
--         OR (CHAR_LENGTH(`self_help_tip`) - CHAR_LENGTH(REPLACE(`self_help_tip`, '|', ''))) < 2);

-- 5.4 关键词里没有单字噪声词（预期 0；匹配时虽会忽略，但脏词表会误导维护者）
-- SELECT COUNT(*) FROM `repair_category`
--  WHERE `deleted` = 0
--    AND (CONCAT(',', REPLACE(REPLACE(`keywords`, '，', ','), '、', ','), ',') LIKE '%,水,%'
--      OR CONCAT(',', REPLACE(REPLACE(`keywords`, '，', ','), '、', ','), ',') LIKE '%,灯,%'
--      OR CONCAT(',', REPLACE(REPLACE(`keywords`, '，', ','), '、', ','), ',') LIKE '%,门,%');

-- 5.5 四个配置键已就位（预期 4；第 4 节的 INSERT 就是四个，含 assistant_llm_escalate_below）
-- SELECT COUNT(*) FROM `sys_config` WHERE `config_key` LIKE 'assistant%';
