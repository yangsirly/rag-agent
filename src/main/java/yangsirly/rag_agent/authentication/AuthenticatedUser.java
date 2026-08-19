package yangsirly.rag_agent.authentication;

import yangsirly.rag_agent.registration.User;

/**
 * 已通过认证的用户主体。
 *
 * <p>该对象表示“系统当前信任的登录用户”，会被放入 Spring Security 的
 * {@code SecurityContext}。后续会话、知识库等模块应从这里读取 userId 和角色，
 * 而不是信任客户端自行声明的身份。</p>
 *
 * @param userId 数据库用户主键
 * @param email 规范化后的邮箱，便于日志关联（仍需注意脱敏策略）
 * @param role 用户角色，用于授权判断
 * @param status 用户状态；DISABLED 用户在核心登录逻辑中应被拒绝
 */
public record AuthenticatedUser(
		Long userId,
		String email,
		User.Role role,
		User.Status status) {
}
