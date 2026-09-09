-- P2.1 RAG baseline 本地开发语料。
-- 仅用于本地 rag_agent 数据库，不应作为生产 migration 执行。
-- 前置条件：users 中至少存在一个 role='EDITOR' 且 status='ACTIVE' 的用户。
-- 该脚本可重复执行：固定使用 KB id=900001、Document id=900001~900005。

SET @rag_eval_editor_id = (
    SELECT id
    FROM users
    WHERE role = 'EDITOR' AND status = 'ACTIVE'
    ORDER BY id
    LIMIT 1
);
SET @rag_eval_kb_id = 900001;

-- 如果没有 ACTIVE EDITOR，本条 INSERT 会插入 0 行，随后文档 INSERT 会因 creator_id 为 NULL 失败，
-- 以便显式暴露 seed 前置条件，而不是静默创建权限语义错误的数据。
INSERT INTO knowledge_bases (
    id,
    creator_id,
    name,
    description,
    editor_ids,
    reader_ids,
    created_at,
    updated_at,
    deleted_at
)
SELECT
    @rag_eval_kb_id,
    @rag_eval_editor_id,
    'P2 RAG 评测知识库',
    'P2.1 Retrieval baseline 使用的虚构公司差旅与报销制度语料。',
    JSON_ARRAY(),
    JSON_ARRAY(),
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6),
    NULL
WHERE @rag_eval_editor_id IS NOT NULL
ON DUPLICATE KEY UPDATE
    creator_id = VALUES(creator_id),
    name = VALUES(name),
    description = VALUES(description),
    deleted_at = NULL,
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO documents (
    id,
    knowledge_base_id,
    creator_id,
    title,
    summary,
    content,
    content_length,
    created_at,
    updated_at,
    deleted_at
)
VALUES
(
    900001,
    @rag_eval_kb_id,
    @rag_eval_editor_id,
    '差旅申请流程',
    '规定员工出差申请、审批和行程变更流程。',
    '一、出差申请\n员工因公出差前，应在出发前至少 2 个工作日提交出差申请，填写出差目的、地点、预计时间和预算。\n\n二、审批流程\n普通员工的出差申请先由直属负责人审批，再由部门负责人确认。涉及跨省且预算超过 5000 元的，还需提交分管负责人审批。\n\n三、行程变更\n已审批行程发生明显变化时，员工应在返程后补充变更说明，并在报销时附上原审批记录。',
    0,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6),
    NULL
),
(
    900002,
    @rag_eval_kb_id,
    @rag_eval_editor_id,
    '差旅住宿与交通标准',
    '规定住宿、城际交通和差旅补贴的基本标准。',
    '一、住宿标准\n出差住宿应优先选择公司协议酒店。一般城市住宿标准原则上不超过每晚 500 元，一线城市原则上不超过每晚 700 元。\n\n二、城际交通\n员工应根据行程合理选择高铁二等座、动车二等座或经济舱。因紧急业务需要升级交通席位的，应提前说明原因。\n\n三、差旅补贴\n差旅补贴按实际出差自然日计算，具体标准以财务部门当期发布口径为准。',
    0,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6),
    NULL
),
(
    900003,
    @rag_eval_kb_id,
    @rag_eval_editor_id,
    '费用报销管理办法',
    '规定报销提交时限、逾期材料和报销凭证要求。',
    '一、提交时限\n报销单应在费用发生后 30 天内提交。以费用发生日期次日开始计算，超过 30 天视为逾期提交。\n\n二、逾期处理\n确因项目结算、长期出差等原因无法在期限内提交的，逾期报销时需附部门负责人书面说明，并由财务复核。\n\n三、报销凭证\n报销人应提供合法有效发票或其他合规原始凭证，并附与费用对应的审批记录。缺少关键凭证且无法补正的，财务有权退回报销申请。',
    0,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6),
    NULL
),
(
    900004,
    @rag_eval_kb_id,
    @rag_eval_editor_id,
    '办公用品采购规定',
    '规定办公用品申请、采购和领用流程。',
    '一、采购申请\n常规办公用品由行政部门统一采购，各部门按月汇总需求。\n\n二、临时采购\n临时采购金额超过 1000 元时，需要部门负责人批准后再由行政部门执行。\n\n三、领用登记\n固定资产类物品领用时必须登记使用人、资产编号和领用日期。',
    0,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6),
    NULL
),
(
    900005,
    @rag_eval_kb_id,
    @rag_eval_editor_id,
    '实习生管理规定',
    '规定实习生考勤、费用和导师责任。',
    '一、考勤\n实习生应遵守所在部门的正常考勤制度，确需请假时应提前向导师说明。\n\n二、费用规则\n实习生上下班通勤费用由个人承担。实习期间发生的市内交通费不予报销；如因公司安排参加异地项目产生城际交通和住宿费用，应由导师提前发起专项申请。\n\n三、导师责任\n导师负责安排实习任务、进行日常指导并完成实习期评价。',
    0,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6),
    NULL
)
ON DUPLICATE KEY UPDATE
    knowledge_base_id = VALUES(knowledge_base_id),
    creator_id = VALUES(creator_id),
    title = VALUES(title),
    summary = VALUES(summary),
    content = VALUES(content),
    deleted_at = NULL,
    updated_at = CURRENT_TIMESTAMP(6);

-- 与项目 DocumentService 的 codePointCount 口径一致；对当前中文/ASCII 语料 CHAR_LENGTH 等价于字符数。
UPDATE documents
SET content_length = CHAR_LENGTH(content)
WHERE knowledge_base_id = @rag_eval_kb_id
  AND id BETWEEN 900001 AND 900005;

-- 执行后可用以下查询人工确认：
-- SELECT id, creator_id, name FROM knowledge_bases WHERE id = 900001;
-- SELECT id, title, content_length FROM documents WHERE knowledge_base_id = 900001 ORDER BY id;
