package yangsirly.rag_agent.registration;

/** 数据库拒绝写入重复邮箱时对外抛出的稳定业务异常。 */
public class EmailAlreadyRegisteredException extends RuntimeException {

	public EmailAlreadyRegisteredException(Throwable cause) {
		// 不把邮箱或数据库错误细节写入对外消息。
		super("Email is already registered", cause);
	}
}
