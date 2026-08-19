package yangsirly.rag_agent.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import yangsirly.rag_agent.authentication.AuthenticatedUser;

/**
 * 会话相关 HTTP 入口。
 *
 * <p>
 * 身份一律从 {@link Authentication} 的 principal 读取，
 * 禁止信任请求体或路径中的“用户 id”声明。
 * </p>
 *
 * <p>
 * 路径 id 解析：格式非法时抛出 {@link IllegalArgumentException}，
 * 由 {@link ChatExceptionHandler} 映射为 {@code INVALID_PATH_PARAMETER}。
 * </p>
 */
@RestController
public class ConversationController {

	private final ConversationService conversationService;

	public ConversationController(ConversationService conversationService) {
		this.conversationService = conversationService;
	}

	/**
	 * POST /conversations — 创建会话。
	 *
	 * <p>
	 * title 可选；校验失败由 Service 抛 IllegalArgumentException → 400。
	 * </p>
	 */
	@PostMapping("/conversations")
	public ResponseEntity<ConversationResponse> create(
			@RequestBody(required = false) CreateConversationRequest request,
			Authentication authentication) {
		AuthenticatedUser user = requireUser(authentication);
		String title = request == null ? null : request.title();
		ConversationService.ConversationView view =
				conversationService.create(new CreateConversationCommand(user.userId(), title));
		return ResponseEntity.status(HttpStatus.CREATED).body(ConversationService.toResponse(201, view));
	}

	/**
	 * GET /conversations/{conversationId} — 会话详情。
	 */
	@GetMapping("/conversations/{conversationId}")
	public ResponseEntity<ConversationResponse> get(
			@PathVariable String conversationId,
			Authentication authentication) {
		AuthenticatedUser user = requireUser(authentication);
		Long id = parseId(conversationId);
		ConversationService.ConversationView view = conversationService.getOwned(user.userId(), id);
		return ResponseEntity.ok(ConversationService.toResponse(200, view));
	}

	/**
	 * GET /conversations — 会话列表（里程碑 4 完整实现）。
	 */
	@GetMapping("/conversations")
	public ResponseEntity<?> list(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			Authentication authentication) {
		AuthenticatedUser user = requireUser(authentication);
		// TODO(里程碑 4)：conversationService.list + 列表响应 DTO
		throw new UnsupportedOperationException("TODO: implement list conversations (milestone 4)");
	}

	/**
	 * PATCH /conversations/{conversationId} — 修改标题（里程碑 4）。
	 */
	@PatchMapping("/conversations/{conversationId}")
	public ResponseEntity<ConversationResponse> rename(
			@PathVariable String conversationId,
			@RequestBody RenameConversationRequest request,
			Authentication authentication) {
		AuthenticatedUser user = requireUser(authentication);
		Long id = parseId(conversationId);
		// TODO(里程碑 4)：conversationService.rename(...)
		throw new UnsupportedOperationException("TODO: implement rename conversation (milestone 4)");
	}

	/**
	 * DELETE /conversations/{conversationId} — 物理删除（里程碑 4）。
	 */
	@DeleteMapping("/conversations/{conversationId}")
	public ResponseEntity<Void> delete(
			@PathVariable String conversationId,
			Authentication authentication) {
		AuthenticatedUser user = requireUser(authentication);
		Long id = parseId(conversationId);
		// TODO(里程碑 4)：conversationService.delete(...); return 204
		throw new UnsupportedOperationException("TODO: implement delete conversation (milestone 4)");
	}

	/** 从 SecurityContext 取出已认证用户；过滤器保证类型，这里再断言一次。 */
	private static AuthenticatedUser requireUser(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
			// 正常情况下过滤器链会先返回 401；此处防御编程，避免 NPE
			throw new IllegalStateException("Authenticated user principal is required");
		}
		return user;
	}

	/**
	 * 路径 id → long。非法格式返回 400 INVALID_PATH_PARAMETER（见异常处理器）。
	 */
	static Long parseId(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("Invalid path parameter");
		}
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException("Invalid path parameter", ex);
		}
	}

	/**
	 * 修改标题请求体（里程碑 4 使用；骨架先定义以免 Controller 引用缺失）。
	 */
	public record RenameConversationRequest(String title) {
	}
}
