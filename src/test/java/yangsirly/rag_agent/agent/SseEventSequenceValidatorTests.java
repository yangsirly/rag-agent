package yangsirly.rag_agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;

import yangsirly.rag_agent.agent.protocol.SseEvent;
import yangsirly.rag_agent.agent.protocol.SseEventSequenceValidator;
import yangsirly.rag_agent.agent.support.AgentFixtures;

/**
 * SSE 事件顺序不变量（协议 2.8 节 INV-S1 ~ INV-S11）的校验器测试，覆盖
 * agent-test-cases.md 第 3 组 AGT-STREAM-001 ~ 007 中不依赖数据库的全部断言。
 * 落库类断言（如"半截正文必须已落库"）以 persisted* 建议值的形式在协议层验证，
 * 真正的持久化验证属于实现落地后的集成测试。
 *
 * <p>期望值全部来自 fixtures/agent/expectations/sse-stream.json，按文件名索引，不硬编码。
 *
 * <p>本类不加载 Spring 上下文，只用 JUnit 5 + AssertJ + Jackson，毫秒级完成。
 */
class SseEventSequenceValidatorTests {

	private final SseEventSequenceValidator validator = new SseEventSequenceValidator();

	static List<Path> sseStreamFixtures() {
		return AgentFixtures.filesIn("sse-stream", ".txt");
	}

	/**
	 * AGT-STREAM-001 ~ 006：6 个事件流样本逐一对照期望记录。
	 * 覆盖事件数、注释行数（心跳不是事件、不占 seq）、终止形态、结局、
	 * 首违例及其 seq（按流内顺序报告，不按内部检查顺序）、全部违例清单、
	 * 应落库的状态 / 错误码 / 正文。
	 */
	@ParameterizedTest
	@MethodSource("sseStreamFixtures")
	void judgesEverySseStreamFixtureExactlyAsTheExpectationsRecord(Path fixture) {
		String fileName = fixture.getFileName().toString();
		JsonNode expectation = AgentFixtures.expectationFor("sse-stream.json", fileName);

		SseEventSequenceValidator.Result result = validator.validate(AgentFixtures.text(fixture));

		assertThat(result.eventCount()).isEqualTo(expectation.path("eventCount").asInt(-1));
		assertThat(result.commentLineCount()).isEqualTo(expectation.path("commentLineCount").asInt(-1));
		assertThat(result.terminal().name()).isEqualTo(expectation.path("terminal").asText(null));
		assertThat(result.outcome().name()).isEqualTo(expectation.path("outcome").asText(null));

		if (expectation.hasNonNull("firstViolation")) {
			assertThat(result.firstViolation()).isNotNull();
			assertThat(result.firstViolation().invariant())
					.isEqualTo(expectation.get("firstViolation").asText());
			assertThat(result.firstViolation().seq())
					.isEqualTo(expectation.path("firstViolationSeq").asLong(-1));
		} else {
			assertThat(result.violations()).isEmpty();
		}
		List<String> expectedViolations = new ArrayList<>();
		for (int i = 0; i < expectation.path("violations").size(); i++) {
			expectedViolations.add(expectation.path("violations").get(i).asText());
		}
		assertThat(result.violations().stream().map(SseEventSequenceValidator.Violation::invariant).toList())
				.containsExactlyElementsOf(expectedViolations);

		if (expectation.hasNonNull("expectedMessageStatus")) {
			assertThat(result.persistedStatus().name())
					.isEqualTo(expectation.get("expectedMessageStatus").asText());
		} else {
			// truncated：此刻消息状态由服务端决定，消费端不得据此判 FAILED（INV-S11、7.3 节）。
			assertThat(result.persistedStatus()).isNull();
		}
		if (expectation.hasNonNull("expectedErrorCode")) {
			assertThat(result.persistedErrorCode()).isEqualTo(expectation.get("expectedErrorCode").asText());
		} else if (expectation.hasNonNull("expectedMessageStatus")) {
			// INV-M2：errorCode 非空 ⟺ FAILED。
			assertThat(result.persistedErrorCode()).isNull();
		}
		if (expectation.hasNonNull("expectedContent")) {
			assertThat(result.persistedContent()).isEqualTo(expectation.get("expectedContent").asText());
		}
		if (expectation.has("expectedCitationCount")) {
			assertThat(result.eventsOfType(SseEvent.EventType.CITATION))
					.hasSize(expectation.get("expectedCitationCount").asInt());
		}
		if (expectation.has("expectedStatusCode")) {
			// AGT-STREAM-003 断言 2：error 事件的 statusCode 与 retryable（2.7 节）。
			SseEvent error = result.eventsOfType(SseEvent.EventType.ERROR).get(0);
			assertThat(error.data().path("statusCode").asInt(-1))
					.isEqualTo(expectation.get("expectedStatusCode").asInt());
			assertThat(error.data().path("retryable").asBoolean(false))
					.isEqualTo(expectation.get("expectedRetryable").asBoolean(false));
		}
	}

	/**
	 * INV-S9 的反例：拼接 delta 与 done.content 不一致必须被判违例。六个 fixture 全是
	 * S9 一致的流，没有这条反例，"校验器实现了 S9"就只能靠被测代码自己作证。
	 */
	@Test
	void rejectsDoneContentThatDiffersFromConcatenatedDeltas() {
		SseEventSequenceValidator.Result result = validator.validate(List.of(
				delta(1, "m", "报销单最多 30 天。"), done(2, "m", "报销单最多 15 天。")));

		assertThat(result.outcome()).isEqualTo(SseEventSequenceValidator.Outcome.PROTOCOL_VIOLATION);
		assertThat(invariants(result)).containsExactly("INV-S9");
		assertThat(result.firstViolation().seq()).isEqualTo(2);
	}

	/**
	 * AGT-STREAM-006 断言 2："done 之后的事件一律丢弃"。duplicate-done.txt 的两个 done
	 * 内容逐字节相同，检出 INV-S2 后继续做语义处理的实现也能蒙混过关——这里用内容不同的
	 * 后续事件钉死丢弃语义：done 之后的 delta 不得再进入拼接结果。
	 */
	@Test
	void discardsEventsAfterTerminalInsteadOfProcessingThem() {
		SseEventSequenceValidator.Result result = validator.validate(List.of(
				delta(1, "m", "正文A"), done(2, "m", "正文A"), delta(3, "m", "正文B")));

		assertThat(invariants(result)).containsExactly("INV-S2");
		assertThat(result.concatenatedDeltaText()).isEqualTo("正文A");
		assertThat(result.persistedContent()).isEqualTo("正文A");
	}

	/**
	 * 2.1 节帧格式表把两条规则的违反处理明确落在消费方：data 必须是单行 JSON、
	 * id 行必须等于 payload 中的 seq。二者都按 PROTOCOL_VIOLATION 处理。
	 */
	@Test
	void rejectsMultiLineDataAndIdSeqMismatchPerFrameFormatRules() {
		SseEvent multiLine = new SseEvent(SseEvent.EventType.DELTA, "delta", 1,
				AgentFixtures.MAPPER.readTree("{\"seq\":1,\"messageId\":\"m\",\"text\":\"x\"}"), true);
		SseEventSequenceValidator.Result multiLineResult = validator.validate(List.of(
				multiLine, done(2, "m", "x")));
		assertThat(invariants(multiLineResult))
				.contains(SseEventSequenceValidator.DATA_NOT_SINGLE_LINE);

		SseEvent idMismatch = new SseEvent(SseEvent.EventType.DELTA, "delta", 7,
				AgentFixtures.MAPPER.readTree("{\"seq\":1,\"messageId\":\"m\",\"text\":\"x\"}"), false);
		SseEventSequenceValidator.Result idMismatchResult = validator.validate(List.of(
				idMismatch, done(2, "m", "x")));
		assertThat(invariants(idMismatchResult))
				.contains(SseEventSequenceValidator.ID_SEQ_MISMATCH);
	}

	/**
	 * INV-S8 对镜像输入必须给出同一结论：首事件 messageId 缺失、次事件携带 messageId
	 * 的流同样是"两轮的流被串在了一起"，不得因为基准取的是"首个非 null 值"而漏报。
	 */
	@Test
	void rejectsMessageIdMismatchEvenWhenFirstEventOmitsIt() {
		SseEventSequenceValidator.Result result = validator.validate(List.of(
				SseEvent.of("delta", "{\"seq\":1,\"text\":\"报\"}"),
				delta(2, "m1", "销"), done(3, "m1", "报销")));

		assertThat(invariants(result)).contains("INV-S8");
	}

	/**
	 * fixtures/agent/README.md 第 3 节：解析器必须同时接受 LF 与 CRLF（以及 CR）。
	 * Windows 上 core.autocrlf 可能在检出时改写换行，依赖字节形态的实现会在
	 * 别人的机器上莫名其妙地红。
	 */
	@Test
	void acceptsCrlfAndCrLineTerminators() {
		String lf = AgentFixtures.text(AgentFixtures.root().resolve("sse-stream/normal.txt"))
				.replace("\r\n", "\n");
		SseEventSequenceValidator.Result fromLf = validator.validate(lf);
		SseEventSequenceValidator.Result fromCrlf = validator.validate(lf.replace("\n", "\r\n"));
		SseEventSequenceValidator.Result fromCr = validator.validate(lf.replace("\n", "\r"));

		for (SseEventSequenceValidator.Result result : List.of(fromLf, fromCrlf, fromCr)) {
			assertThat(result.outcome()).isEqualTo(SseEventSequenceValidator.Outcome.COMPLETED);
			assertThat(result.eventCount()).isEqualTo(4);
			assertThat(result.violations()).isEmpty();
		}
	}

	/** AGT-STREAM-002 断言 7（INV-C1 的协议层形态）：citation.marker=1 在 done.content 中以 [^1] 出现。 */
	@Test
	void citationMarkerAppearsInlineInDoneContent() {
		SseEventSequenceValidator.Result result = validator
				.validate(AgentFixtures.text(AgentFixtures.root().resolve("sse-stream/with-tool.txt")));

		assertThat(result.violations()).isEmpty();
		List<SseEvent> citations = result.eventsOfType(SseEvent.EventType.CITATION);
		assertThat(citations).hasSize(1);
		assertThat(citations.get(0).marker()).isEqualTo(1);
		assertThat(result.persistedContent()).contains("[^1]");
		// INV-S9：内联标记计入正文，拼接 delta 必须逐字符等于 done.content。
		assertThat(result.concatenatedDeltaText()).isEqualTo(result.persistedContent());
	}

	/**
	 * AGT-STREAM-003 断言 4 的协议层形态：error 事件声明 partialContentPersisted=true 时，
	 * 流内确实存在已产出的部分正文。字段与事实不符比字段不存在更糟——客户端据此决定
	 * 刷新后是保留还是清除已显示的文字。
	 */
	@Test
	void errorEventPartialContentClaimIsConsistentWithStreamContent() {
		SseEventSequenceValidator.Result result = validator
				.validate(AgentFixtures.text(AgentFixtures.root().resolve("sse-stream/error-midway.txt")));

		SseEvent error = result.eventsOfType(SseEvent.EventType.ERROR).get(0);
		assertThat(error.data().path("partialContentPersisted").asBoolean(false)).isTrue();
		assertThat(result.persistedContent()).isNotEmpty();
		assertThat(error.data().path("retryable").asBoolean(false)).isTrue();
		assertThat(error.data().path("statusCode").asInt(-1)).isEqualTo(504);
	}

	/** AGT-STREAM-007 断言 1：delta.text 为空字符串或 null → INV-S7。空 delta 掩盖真正的空回复。 */
	@Test
	void rejectsEmptyOrNullDeltaText() {
		SseEventSequenceValidator.Result emptyText = validator.validate(List.of(
				SseEvent.of("delta", "{\"seq\":1,\"messageId\":\"m\",\"text\":\"\"}"),
				done(2, "m", "")));
		assertThat(invariants(emptyText)).contains("INV-S7");

		SseEventSequenceValidator.Result nullText = validator.validate(List.of(
				SseEvent.of("delta", "{\"seq\":1,\"messageId\":\"m\",\"text\":null}"),
				done(2, "m", "")));
		assertThat(invariants(nullText)).contains("INV-S7");
	}

	/** AGT-STREAM-007 断言 2：seq 缺号（1, 2, 4）→ INV-S1。缺号意味着丢帧。 */
	@Test
	void rejectsSequenceNumberGap() {
		SseEventSequenceValidator.Result result = validator.validate(List.of(
				delta(1, "m", "报"), delta(2, "m", "销"), delta(4, "m", "单"),
				done(5, "m", "报销单")));

		assertThat(result.outcome()).isEqualTo(SseEventSequenceValidator.Outcome.PROTOCOL_VIOLATION);
		assertThat(result.firstViolation().invariant()).isEqualTo("INV-S1");
		assertThat(result.firstViolation().seq()).isEqualTo(4);
	}

	/** AGT-STREAM-007 断言 3：同一 callId 出现两个 tool_call，或两个 tool_result → INV-S4。 */
	@Test
	void rejectsDuplicateToolCallOrToolResultForSameCallId() {
		SseEventSequenceValidator.Result duplicateCall = validator.validate(List.of(
				toolCall(1, "m", "call_1", 1), toolCall(2, "m", "call_1", 1),
				toolResult(3, "m", "call_1"), done(4, "m", "")));
		assertThat(invariants(duplicateCall)).contains("INV-S4");

		SseEventSequenceValidator.Result duplicateResult = validator.validate(List.of(
				toolCall(1, "m", "call_1", 1), toolResult(2, "m", "call_1"),
				toolResult(3, "m", "call_1"), done(4, "m", "")));
		assertThat(invariants(duplicateResult)).contains("INV-S4");
	}

	/** AGT-STREAM-007 断言 4：同一条流中出现两个不同的 messageId → INV-S8（两轮的流被串在了一起）。 */
	@Test
	void rejectsMessageIdChangeMidStream() {
		SseEventSequenceValidator.Result result = validator.validate(List.of(
				delta(1, "m1", "报"), delta(2, "m2", "销"), done(3, "m1", "报销")));

		assertThat(invariants(result)).contains("INV-S8");
		assertThat(result.violations().get(0).seq()).isEqualTo(2);
	}

	/** AGT-STREAM-007 断言 5：citation.marker 跳号（1, 3）或重复（1, 1）→ INV-S6。 */
	@Test
	void rejectsCitationMarkerGapOrDuplicate() {
		SseEventSequenceValidator.Result gap = validator.validate(List.of(
				delta(1, "m", "正文[^1][^3]"), citation(2, "m", 1), citation(3, "m", 3),
				done(4, "m", "正文[^1][^3]")));
		assertThat(invariants(gap)).contains("INV-S6");

		SseEventSequenceValidator.Result duplicate = validator.validate(List.of(
				delta(1, "m", "正文[^1]"), citation(2, "m", 1), citation(3, "m", 1),
				done(4, "m", "正文[^1]")));
		assertThat(invariants(duplicate)).contains("INV-S6");
	}

	/** AGT-STREAM-007 断言 6：tool_call.step 递减（2 之后出现 1）→ INV-S10。 */
	@Test
	void rejectsDecreasingToolCallStep() {
		SseEventSequenceValidator.Result result = validator.validate(List.of(
				toolCall(1, "m", "call_1", 1), toolResult(2, "m", "call_1"),
				toolCall(3, "m", "call_2", 2), toolResult(4, "m", "call_2"),
				toolCall(5, "m", "call_3", 1), toolResult(6, "m", "call_3"),
				done(7, "m", "")));

		assertThat(invariants(result)).containsExactly("INV-S10");
		assertThat(result.firstViolation().seq()).isEqualTo(5);
	}

	/**
	 * AGT-STREAM-007 断言 7：未知的 event 类型必须被忽略并记录，不得中断，也不是违例
	 * ——把未知事件当违例会让日后新增事件类型时所有旧客户端一起崩掉（2.1 节前向兼容）。
	 * 未知事件仍占用 seq，仍受 INV-S1 / INV-S8 约束。
	 */
	@Test
	void ignoresUnknownEventTypesForForwardCompatibility() {
		SseEventSequenceValidator.Result result = validator.validate(List.of(
				delta(1, "m", "报销单"),
				SseEvent.of("usage_report", "{\"seq\":2,\"messageId\":\"m\",\"promptTokens\":312}"),
				done(3, "m", "报销单")));

		assertThat(result.violations()).isEmpty();
		assertThat(result.outcome()).isEqualTo(SseEventSequenceValidator.Outcome.COMPLETED);
		assertThat(result.eventCount()).isEqualTo(3);
	}

	/**
	 * 3.1、3.6 节：取消走 error 通道（保住 INV-S2 的形状），但结局与落库都必须区分
	 * "失败"和"用户不想要了"——outcome 与状态为 CANCELLED、errorCode 为 null
	 * （INV-M9、INV-M2），已产出的部分正文保留（INV-M7）。
	 * 否则取消会在监控上被统计成故障，掩盖真实故障率。
	 */
	@Test
	void mapsAgentCancelledErrorToCancelledStatusWithNullErrorCodeAndKeptContent() {
		SseEventSequenceValidator.Result result = validator.validate(List.of(
				delta(1, "m", "报销单最多"),
				SseEvent.of("error", "{\"seq\":2,\"messageId\":\"m\",\"statusCode\":200,"
						+ "\"code\":\"AGENT_CANCELLED\",\"message\":\"已取消。\","
						+ "\"retryable\":true,\"partialContentPersisted\":true}")));

		assertThat(result.violations()).isEmpty();
		assertThat(result.terminal()).isEqualTo(SseEventSequenceValidator.Terminal.ERROR);
		assertThat(result.outcome()).isEqualTo(SseEventSequenceValidator.Outcome.CANCELLED);
		assertThat(result.persistedStatus()).isEqualTo(SseEventSequenceValidator.PersistedStatus.CANCELLED);
		assertThat(result.persistedErrorCode()).isNull();
		assertThat(result.persistedContent()).isEqualTo("报销单最多");
	}

	private static List<String> invariants(SseEventSequenceValidator.Result result) {
		return result.violations().stream().map(SseEventSequenceValidator.Violation::invariant).toList();
	}

	private static SseEvent delta(long seq, String messageId, String text) {
		return SseEvent.of("delta", "{\"seq\":" + seq + ",\"messageId\":\"" + messageId
				+ "\",\"text\":\"" + text + "\"}");
	}

	private static SseEvent toolCall(long seq, String messageId, String callId, long step) {
		return SseEvent.of("tool_call", "{\"seq\":" + seq + ",\"messageId\":\"" + messageId
				+ "\",\"callId\":\"" + callId + "\",\"step\":" + step
				+ ",\"name\":\"kb_search\",\"arguments\":{\"query\":\"报销\",\"knowledgeBaseId\":\"1\"}}");
	}

	private static SseEvent toolResult(long seq, String messageId, String callId) {
		return SseEvent.of("tool_result", "{\"seq\":" + seq + ",\"messageId\":\"" + messageId
				+ "\",\"callId\":\"" + callId + "\",\"status\":\"OK\",\"durationMs\":1,\"result\":{}}");
	}

	private static SseEvent citation(long seq, String messageId, long marker) {
		return SseEvent.of("citation", "{\"seq\":" + seq + ",\"messageId\":\"" + messageId
				+ "\",\"marker\":" + marker + ",\"docId\":\"1/3\",\"chunkId\":null,"
				+ "\"snippet\":\"报销单应在费用发生后 30 天内提交\",\"score\":0.83,\"knowledgeBaseId\":\"1\"}");
	}

	private static SseEvent done(long seq, String messageId, String content) {
		return SseEvent.of("done", "{\"seq\":" + seq + ",\"messageId\":\"" + messageId
				+ "\",\"status\":\"COMPLETED\",\"finishReason\":\"STOP\",\"steps\":1,"
				+ "\"content\":\"" + content + "\"}");
	}
}
