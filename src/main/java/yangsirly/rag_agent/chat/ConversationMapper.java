package yangsirly.rag_agent.chat;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * conversations 表的持久化边界。
 *
 * <p>复杂分页/排序查询可在后续用 default 方法或 XML 补充；
 * 骨架阶段先声明所有权相关的查找入口，便于 Service 流程引用。</p>
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

	/**
	 * 按主键与所属用户查找会话。
	 *
	 * <p>同时带上 userId 条件，避免“先按 id 查出再判断是否本人”的两步写法，
	 * 从查询层面落实“只能操作自己的会话”。</p>
	 *
	 * @return 找不到时返回 {@code null}（含不存在与非本人）
	 */
	default ConversationEntity findByIdAndUserId(Long id, Long userId) {
		return selectOne(Wrappers.<ConversationEntity>lambdaQuery()
				.eq(ConversationEntity::getId, id)
				.eq(ConversationEntity::getUserId, userId));
	}

	// TODO(里程碑 4)：listByUserId(userId, page, size) — updatedAt DESC, id DESC
	// TODO(里程碑 4)：countByUserId(userId)
}
