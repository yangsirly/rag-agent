package yangsirly.rag_agent.chat;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import yangsirly.rag_agent.authentication.AuthenticatedUser;

/**
 * 消息相关 HTTP 入口：发送（聊天闭环）与历史列表。
 *
 * <p>
 * 嵌套在会话路径下：{@code /conversations/{conversationId}/messages}，
 * 所有权校验在 Service 内通过 userId + conversationId 完成。
 * </p>
 */
@RestController
public class MessageController {

	private final MessageService messageService;

	public MessageController(MessageService messageService) {
		this.messageService = messageService;
	}

	/**
	 * POST /conversations/{conversationId}/messages
	 *
	 * <p>
	 * 首次成功 201，幂等重试 200；响应体均含 userMessage + assistantMessage。
	 * </p>
	 */
	@PostMapping("/conversations/{conversationId}/messages")
	public ResponseEntity<SendMessageResponse> send(
			@PathVariable String conversationId,
			@Valid @RequestBody SendMessageRequest request,
			Authentication authentication) {
		AuthenticatedUser user = requireUser(authentication);
		Long id = ConversationController.parseId(conversationId);

		// Web 输入 → 业务命令：身份来自 principal，不来自 body
		SendMessageCommand command = new SendMessageCommand(
				user.userId(),
				id,
				request.clientMessageId(),
				request.content());

		MessageService.SendResult result = messageService.send(command);
		return ResponseEntity.status(result.httpStatus()).body(result.toResponse());
	}

	/**
	 * GET /conversations/{conversationId}/messages?page=0&size=50
	 *
	 * <p>
	 * 默认 size=50；page=0 为最新一页，页内时间正序。
	 * </p>
	 */
	@GetMapping("/conversations/{conversationId}/messages")
	public ResponseEntity<MessageListResponse> list(
			@PathVariable String conversationId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size,
			Authentication authentication) {
		AuthenticatedUser user = requireUser(authentication);
		Long id = ConversationController.parseId(conversationId);

		MessageService.MessagePage messagePage = messageService.listMessages(user.userId(), id, page, size);
		return ResponseEntity.ok(messagePage.toResponse());
	}

	private static AuthenticatedUser requireUser(Authentication authentication) {
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
			throw new IllegalStateException("Authenticated user principal is required");
		}
		return user;
	}
}
