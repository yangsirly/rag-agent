package yangsirly.rag_agent.registration;

/**
 * 注册流程中准备持久化的用户数据。
 *
 * <p>该 record 只保留 BCrypt 哈希，不保留用户提交的明文密码。
 * id 和时间字段由后续持久化步骤及数据库生成。</p>
 */
public record User(
		String email,
		String phone,
		String passwordHash,
		Role role,
		Status status) {

	/** 注册接口只能创建 CUSTOMER，EDITOR 应由受信任的管理流程授予。 */
	public enum Role {
		CUSTOMER,
		EDITOR
	}

	/** ACTIVE 可正常使用系统，DISABLED 表示账号被禁用。 */
	public enum Status {
		ACTIVE,
		DISABLED
	}
}
