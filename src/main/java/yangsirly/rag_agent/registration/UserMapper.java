package yangsirly.rag_agent.registration;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * users 表的 MyBatis-Plus 持久化边界。
 *
 * <p>
 * {@link BaseMapper} 根据 {@link UserEntity} 的表映射提供基础 CRUD。
 * {@link Mapper} 让 MyBatis 在启动时为该接口生成代理对象并注入 Spring。
 * </p>
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

	/**
	 * 按邮箱精确查找用户。
	 *
	 * <p>
	 * 返回 {@code null} 表示未找到，调用方需自行判空。
	 * 使用 {@link Wrappers#lambdaQuery} 构建类型安全的查询条件，
	 * 避免手写列名字符串与实体字段不同步。
	 * </p>
	 */
	default UserEntity findByEmail(String email) {
		return selectOne(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getEmail, email));
	}

	default UserEntity findById(Long id) {
		return selectOne(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getId, id));
	}
}
