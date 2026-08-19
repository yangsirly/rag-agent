package yangsirly.rag_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用程序的启动入口。
 *
 * <p>{@link SpringBootApplication} 组合了配置声明、自动配置和组件扫描。
 * Spring 会从当前包向下查找 Controller、Service 等组件，并把它们交给容器管理。</p>
 */
@SpringBootApplication
public class RagAgentApplication {

	public static void main(String[] args) {
		// 创建 Spring 容器并启动内嵌 Web 服务器，之后应用才能接收 HTTP 请求。
		SpringApplication.run(RagAgentApplication.class, args);
	}

}
