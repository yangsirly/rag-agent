package yangsirly.rag_agent.registration;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册功能的 HTTP 入口，负责接收请求、调用业务层并组装响应。
 *
 * <p>{@code @RestController} 表示方法返回值会直接写入 HTTP 响应体，
 * record 等 Java 对象会由 Spring 自动序列化为 JSON。</p>
 */
@RestController
public class RegisterController {

	// 使用 final 保证 Controller 创建完成后，它依赖的 Service 不会被替换。
	private final RegisterService registerService;

	// 当前类只有一个构造器，Spring 会自动使用它注入 RegisterService Bean。
	public RegisterController(RegisterService registerService) {
		this.registerService = registerService;
	}

	/**
	 * 处理 POST /register 请求。
	 *
	 * @param request {@code @RequestBody} 把 JSON 转成 Java 对象；{@code @Valid} 随后执行字段校验
	 * @return 注册成功时返回 HTTP 201 和对应的 JSON 响应体
	 */
	@PostMapping("/register")
	public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
		// Web 层请求对象转换成业务层命令，避免业务逻辑直接依赖 HTTP 输入模型。
		RegisterCommand command = new RegisterCommand(
				request.email(),
				request.password());

		// Service 在事务内完成密码哈希和用户写入；重复邮箱会由全局异常处理器转为 409。
		registerService.register(command);

		// 只有注册核心流程正常完成后，才向客户端返回 201 Created。
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new RegisterResponse(HttpStatus.CREATED.value()));
	}
}
