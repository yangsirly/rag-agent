package yangsirly.rag_agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 最基础的 Spring Boot 启动测试。
 *
 * <p>{@code @SpringBootTest} 会加载完整应用上下文；如果 Bean 创建、依赖注入或配置有误，
 * 测试会在启动阶段失败。</p>
 */
@SpringBootTest
class RagAgentApplicationTests {

	@Test
	void contextLoads() {
		// 方法体无需断言：Spring 上下文能够成功加载本身就是本测试验证的行为。
	}

}
