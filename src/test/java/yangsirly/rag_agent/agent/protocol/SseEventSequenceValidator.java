package yangsirly.rag_agent.agent.protocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SSE 事件顺序不变量校验器，完整规格是协议 2.8 节（INV-S1 ~ INV-S11）。
 *
 * <p>【临时寄放】本类型位于 test 源码树，因为批次 4 的硬约束是零 main 改动
 * （见 docs/plans/agent-test-plan.md 5.3 节）。实现落地时把 protocol/ 下四个文件移到
 * src/main/java/yangsirly/rag_agent/agent/protocol/，测试只改 import 的包名。
 *
 * <p>三条设计决定，均直接来自协议文本：
 * <ul>
 * <li>违例按<b>流内顺序</b>报告，不按内部检查顺序——同一份输入永远得到同一个首违例，
 * 否则测试会随实现的遍历顺序变绿变红（fixtures/agent/README.md 第 3 节）；</li>
 * <li>检出违例即拒绝整个流，不得"猜"出正确顺序去修补——协议违例意味着服务端有缺陷，
 * 容忍它等于让缺陷永远不被发现（2.8 节）；</li>
 * <li>截断（INV-S11）<b>不是</b>违例：截断是网络的正常故障，协议违例是代码缺陷，
 * 混为一谈会让真正的 bug 淹没在网络噪声里。截断时本校验器不给出落库建议
 * （{@link Result#persistedStatus()} 为 null）——服务端生成不依赖客户端连接存活，
 * 此刻的消息状态由服务端决定，消费端不得据此把消息判为 FAILED（2.9、7.3 节）。</li>
 * </ul>
 */
public final class SseEventSequenceValidator {

	/**
	 * 消费端视角的流结局。前四个值的口径同 fixtures/agent/expectations/sse-stream.json 的
	 * outcome 字段；CANCELLED 是取消流（error 且 code=AGENT_CANCELLED）的结局——取消不是
	 * 错误（2.7 节 statusCode 200），归入 FAILED 会在监控上被统计成故障（INV-M9 的违反时）。
	 */
	public enum Outcome {
		COMPLETED, FAILED, CANCELLED, TRUNCATED, PROTOCOL_VIOLATION
	}

	/** 流的终止事件形态。 */
	public enum Terminal {
		DONE, ERROR, NONE
	}

	/** 依据流内容推导出的应落库状态（3.1 节状态集合中的终态子集）。 */
	public enum PersistedStatus {
		COMPLETED, FAILED, CANCELLED
	}

	/**
	 * 一条违例：不变量编号 + 违例事件的 seq。多数违例对应 2.8 节的 INV-S* 编号；
	 * 2.1 节帧格式表里两条没有 INV 编号但同样要求消费方按 PROTOCOL_VIOLATION 处理的
	 * 规则（data 必须单行、id 必须等于 seq），用章节号前缀标识。
	 */
	public record Violation(String invariant, long seq) {
	}

	/** 2.1 节：data 不是单行 JSON。 */
	public static final String DATA_NOT_SINGLE_LINE = "2.1-DATA-NOT-SINGLE-LINE";

	/** 2.1 节：id 行与 payload 中的 seq 不一致。 */
	public static final String ID_SEQ_MISMATCH = "2.1-ID-SEQ-MISMATCH";

	/**
	 * 校验结果。persisted* 三个字段是"消费端据此落库"的建议值：
	 * <ul>
	 * <li>COMPLETED 时 content 取 done.content（2.6 节的权威值）；</li>
	 * <li>FAILED / CANCELLED 时 content 取已拼接的 delta——已产出的部分正文必须保留
	 * （INV-M7），可为空字符串但不得为 null；</li>
	 * <li>CANCELLED 时 errorCode 恒为 null——取消不是错误（INV-M2、INV-M9）；</li>
	 * <li>TRUNCATED 时三者均为 null，见类注释第三条。</li>
	 * </ul>
	 */
	public record Result(Outcome outcome, Terminal terminal, List<SseEvent> events, int commentLineCount,
			List<Violation> violations, String concatenatedDeltaText, PersistedStatus persistedStatus,
			String persistedErrorCode, String persistedContent) {

		public Violation firstViolation() {
			return violations.isEmpty() ? null : violations.get(0);
		}

		public int eventCount() {
			return events.size();
		}

		public List<SseEvent> eventsOfType(SseEvent.EventType type) {
			return events.stream().filter(event -> event.type() == type).toList();
		}
	}

	public Result validate(String rawStream) {
		return validate(SseEvent.parseStream(rawStream));
	}

	public Result validate(List<SseEvent> events) {
		return validate(new SseEvent.Stream(List.copyOf(events), 0));
	}

	public Result validate(SseEvent.Stream stream) {
		List<Violation> violations = new ArrayList<>();
		StringBuilder concatenated = new StringBuilder();
		long expectedSeq = 1;
		boolean messageIdSeen = false;
		String messageId = null;
		SseEvent terminalEvent = null;
		Terminal terminal = Terminal.NONE;
		// callId → 是否已有 tool_result 配对。LinkedHashMap 保证 INV-S5 的报告顺序稳定。
		Map<String, Boolean> toolCallPaired = new LinkedHashMap<>();
		Set<String> toolResultSeen = new HashSet<>();
		long previousStep = -1;
		long expectedMarker = 1;

		for (SseEvent event : stream.events()) {
			long seq = event.seq();
			// INV-S1：seq 从 1 开始，严格递增，步长恒为 1。违例后按实际值重新对齐，
			// 让一次缺号只报一条而不是把后续全部报红。
			if (seq != expectedSeq) {
				violations.add(new Violation("INV-S1", seq));
			}
			expectedSeq = seq + 1;

			// 2.1 节的两条帧格式规则，违反时消费方按 PROTOCOL_VIOLATION 处理。
			if (event.multiLineData()) {
				violations.add(new Violation(DATA_NOT_SINGLE_LINE, seq));
			}
			if (event.sseId() >= 0 && event.sseId() != seq) {
				violations.add(new Violation(ID_SEQ_MISMATCH, seq));
			}

			// INV-S8：所有事件的 messageId 相同，以首个事件为基准——包括首事件 messageId
			// 缺失的情况，否则 (null, "101") 与 ("101", null) 两条镜像流会得到不同结论。
			if (!messageIdSeen) {
				messageIdSeen = true;
				messageId = event.messageId();
			} else if (!Objects.equals(messageId, event.messageId())) {
				violations.add(new Violation("INV-S8", seq));
			}

			// INV-S2：done|error 必须是最后一个事件。其后的事件一律违例，且不再做语义处理
			// ——它们本就该被丢弃（2.8 节）。
			if (terminalEvent != null) {
				violations.add(new Violation("INV-S2", seq));
				continue;
			}

			switch (event.type()) {
				case DELTA -> {
					String text = event.deltaText();
					if (text == null || text.isEmpty()) {
						// INV-S7：空 delta 没有任何语义，只会掩盖真正的空回复（INV-M4）。
						violations.add(new Violation("INV-S7", seq));
					} else {
						concatenated.append(text);
					}
				}
				case TOOL_CALL -> {
					String callId = event.callId();
					if (toolCallPaired.containsKey(callId)) {
						violations.add(new Violation("INV-S4", seq));
					} else {
						toolCallPaired.put(callId, false);
					}
					// INV-S10：step 从 1 开始、单调不减（同一 step 可含多个并行 tool_call）。
					long step = event.step();
					if (previousStep < 0 ? step != 1 : step < previousStep) {
						violations.add(new Violation("INV-S10", seq));
					}
					previousStep = Math.max(previousStep, step);
				}
				case TOOL_RESULT -> {
					String callId = event.callId();
					if (!toolResultSeen.add(callId)) {
						violations.add(new Violation("INV-S4", seq));
					} else if (Boolean.FALSE.equals(toolCallPaired.get(callId))) {
						toolCallPaired.put(callId, true);
					} else {
						// INV-S3：没有同 callId 的先行 tool_call——孤儿 tool_result 不得落库。
						violations.add(new Violation("INV-S3", seq));
					}
				}
				case CITATION -> {
					// INV-S6：marker 从 1 开始严格递增、不跳号、不重复。违例后重新对齐。
					long marker = event.marker();
					if (marker != expectedMarker) {
						violations.add(new Violation("INV-S6", seq));
					}
					expectedMarker = marker + 1;
				}
				case DONE -> {
					terminalEvent = event;
					terminal = Terminal.DONE;
					// INV-S5：以 done 结束时不允许存在未配对的 tool_call。
					// 以 error 结束时允许——轮次被中断属正常，所以只在这里检查。
					if (toolCallPaired.containsValue(Boolean.FALSE)) {
						violations.add(new Violation("INV-S5", seq));
					}
					// INV-S9：拼接全部 delta.text 必须逐字符等于 done.content。
					if (!concatenated.toString().equals(event.content())) {
						violations.add(new Violation("INV-S9", seq));
					}
				}
				case ERROR -> {
					terminalEvent = event;
					terminal = Terminal.ERROR;
				}
				case UNKNOWN -> {
					// 2.1 节：未知事件类型忽略并记录，不得中断，也不是违例（前向兼容）。
					// 它仍占用 seq、仍受 INV-S1/S8 约束——上面的公共检查已覆盖。
				}
			}
		}

		return buildResult(stream, violations, concatenated.toString(), terminal, terminalEvent);
	}

	private Result buildResult(SseEvent.Stream stream, List<Violation> violations, String concatenated,
			Terminal terminal, SseEvent terminalEvent) {
		Outcome outcome;
		PersistedStatus persistedStatus;
		String persistedErrorCode;
		String persistedContent;
		if (!violations.isEmpty()) {
			// 协议违例：拒绝整个流，消息落库 FAILED、errorCode=PROTOCOL_VIOLATION（第 6 节）。
			outcome = Outcome.PROTOCOL_VIOLATION;
			persistedStatus = PersistedStatus.FAILED;
			persistedErrorCode = "PROTOCOL_VIOLATION";
			persistedContent = concatenated;
		} else if (terminal == Terminal.DONE) {
			outcome = Outcome.COMPLETED;
			persistedStatus = PersistedStatus.COMPLETED;
			persistedErrorCode = null;
			persistedContent = terminalEvent.content();
		} else if (terminal == Terminal.ERROR) {
			// 3.1、3.6 节：取消走 error 通道（保住 INV-S2 的形状），但结局与落库都必须区分
			// "失败"和"用户不想要了"——CANCELLED 且 errorCode 为 null（INV-M9、INV-M2）。
			if ("AGENT_CANCELLED".equals(terminalEvent.errorCode())) {
				outcome = Outcome.CANCELLED;
				persistedStatus = PersistedStatus.CANCELLED;
				persistedErrorCode = null;
			} else {
				outcome = Outcome.FAILED;
				persistedStatus = PersistedStatus.FAILED;
				persistedErrorCode = terminalEvent.errorCode();
			}
			persistedContent = concatenated;
		} else {
			// INV-S11：截断不是违例。落库建议一律为 null，理由见类注释。
			outcome = Outcome.TRUNCATED;
			persistedStatus = null;
			persistedErrorCode = null;
			persistedContent = null;
		}
		return new Result(outcome, terminal, stream.events(), stream.commentLineCount(),
				List.copyOf(violations), concatenated, persistedStatus, persistedErrorCode, persistedContent);
	}
}
