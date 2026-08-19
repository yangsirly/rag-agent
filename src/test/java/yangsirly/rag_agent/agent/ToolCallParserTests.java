package yangsirly.rag_agent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;

import yangsirly.rag_agent.agent.protocol.ToolCall;
import yangsirly.rag_agent.agent.protocol.ToolCallParser;
import yangsirly.rag_agent.agent.support.AgentFixtures;

/**
 * 模型响应解析与 5.4 节 7 步校验的协议层测试，覆盖 agent-test-cases.md 第 1 组
 * AGT-TOOL-001 ~ 011（例外见下），外加三条能在协议层落地的硬门禁切片：
 * AGT-INJ-902 的"工具名精确匹配"、AGT-AUTH-005 的"身份字段命中 UNKNOWN_PROPERTY"、
 * AGT-STEP-002/001 的"规范化判定死循环"。
 *
 * <p><b>已知未覆盖</b>：AGT-TOOL-003 的断言 3（三个工具乱序返回结果时按 callId 而不是
 * 按下标配对）。结果配对发生在工具执行器里，protocol/ 四个类中不存在该组件，凭空造一个
 * 只为测试的配对器验证不了未来实现——留待实现落地后的集成测试。流内配对的对应不变量
 * （INV-S3 / INV-S4）已由 {@code SseEventSequenceValidatorTests} 覆盖。
 *
 * <p>期望值全部来自 fixtures/agent/expectations/model-response.json，按文件名索引，
 * 测试不硬编码——这样 SDK 选型（待定项 T-1）调整 fixture 结构时只需要改数据文件。
 * 规范化结果用<b>顺序敏感的直接序列化</b>比较（不在比较前再做一次 canonicalize，
 * 那会让"规范化没实现"也能通过——比较器不得替被测代码完成被测的工作）。
 *
 * <p>本类不加载 Spring 上下文，只用 JUnit 5 + AssertJ + Jackson，毫秒级完成。
 */
class ToolCallParserTests {

	private final ToolCallParser parser = new ToolCallParser();

	private final Map<String, ToolCallParser.ToolContract> registry = ToolCallParser.builtinRegistry();

	static List<Path> modelResponseFixtures() {
		return AgentFixtures.filesIn("model-response", ".json");
	}

	/**
	 * AGT-TOOL-001 ~ 011：11 个单次模型响应样本逐一对照期望记录。
	 * 覆盖解析（工具调用数、finishReason、轮次判定）与校验（code、reason、details、
	 * argumentsInvalid、规范化结果）两层。
	 */
	@ParameterizedTest
	@MethodSource("modelResponseFixtures")
	void judgesEveryModelResponseFixtureExactlyAsTheExpectationsRecord(Path fixture) {
		String fileName = fixture.getFileName().toString();
		JsonNode expectation = AgentFixtures.expectationFor("model-response.json", fileName);

		ToolCallParser.ModelTurn turn = parser.parse(AgentFixtures.json(fixture));

		assertThat(turn.toolCalls()).hasSize(expectation.path("expectedToolCallCount").asInt(-1));
		assertThat(enumName(turn.finishReason()))
				.isEqualTo(textOrNull(expectation, "expectedFinishReason"));
		assertThat(turn.outcome().name()).isEqualTo(expectation.path("expectedTurnOutcome").asText(null));
		assertThat(turn.errorCode()).isEqualTo(textOrNull(expectation, "expectedErrorCode"));
		if (turn.outcome() == ToolCallParser.TurnOutcome.COMPLETED) {
			// INV-M4 的协议层形态：判定为 COMPLETED 的轮次 content 必须非空。
			assertThat(turn.content()).isNotEmpty();
		}
		if (expectation.hasNonNull("expectedContent")) {
			// AGT-TOOL-010 断言 4：拒答时 content 必须取 refusal 字段文本，不是任意非空占位。
			assertThat(turn.content()).isEqualTo(expectation.get("expectedContent").asText());
		}

		JsonNode expectedCalls = expectation.path("toolCalls");
		for (int i = 0; i < expectedCalls.size(); i++) {
			JsonNode expectedCall = expectedCalls.get(i);
			ToolCall call = turn.toolCalls().get(i);
			assertThat(call.callId()).isEqualTo(expectedCall.path("callId").asText(null));
			assertThat(call.name()).isEqualTo(expectedCall.path("name").asText(null));

			ToolCallParser.Validation validation = parser.validate(call, registry);
			assertThat(validation.valid()).isEqualTo(expectedCall.path("valid").asBoolean(false));
			assertThat(validation.code()).isEqualTo(textOrNull(expectedCall, "code"));
			assertThat(validation.reason()).isEqualTo(textOrNull(expectedCall, "reason"));
			if (expectedCall.has("details")) {
				List<String> expectedDetails = new ArrayList<>();
				for (int j = 0; j < expectedCall.get("details").size(); j++) {
					expectedDetails.add(expectedCall.get("details").get(j).asText());
				}
				assertThat(validation.details()).containsExactlyElementsOf(expectedDetails);
			}
			if (expectedCall.has("argumentsInvalid")) {
				assertThat(call.argumentsInvalid())
						.isEqualTo(expectedCall.get("argumentsInvalid").asBoolean(false));
			}
			if (expectedCall.has("emittedArguments") && expectedCall.get("emittedArguments").isNull()) {
				// 2.3 节：参数无法解析时 tool_call 事件仍要发出，arguments 为 null。
				assertThat(call.arguments()).isNull();
			}
			if (expectedCall.has("normalizedArguments")) {
				// 顺序敏感的直接序列化比较：期望记录里的属性名已按字典序书写，规范化结果
				// 必须逐字节一致。经 canonicalize 再比较会自我修复，测不出规范化缺失。
				assertThat(AgentFixtures.MAPPER.writeValueAsString(validation.normalizedArguments()))
						.isEqualTo(AgentFixtures.MAPPER
								.writeValueAsString(expectedCall.get("normalizedArguments")));
			}
			// 任何校验结果的回喂文案与日志摘要都不得携带参数值（5.4 序 3 日志约束、5.6 节）。
			if (call.rawArguments() != null && !validation.valid()) {
				assertThat(validation.logSummary()).doesNotContain("expense-report");
			}
		}
	}

	/** AGT-TOOL-004 断言 5：TOOL_NOT_FOUND 的回喂文案不透露注册表中实际有哪些工具。 */
	@Test
	void doesNotLeakRegistryContentsWhenToolIsUnknown() {
		ToolCallParser.ModelTurn turn = parser
				.parse(AgentFixtures.json(AgentFixtures.root().resolve("model-response/unknown-tool.json")));
		ToolCallParser.Validation validation = parser.validate(turn.toolCalls().get(0), registry);

		assertThat(validation.code()).isEqualTo("TOOL_NOT_FOUND");
		// 工具名原样回显（2.3 节），但注册表内容一个都不能出现。
		assertThat(validation.message()).contains("send_email");
		assertThat(validation.message())
				.doesNotContain("kb_search")
				.doesNotContain("doc_fetch")
				.doesNotContain("conversation_title_set");
	}

	/**
	 * AGT-TOOL-008 断言 2、3：长度按 Unicode 码点计数而不是 UTF-16 长度；
	 * 日志摘要只含工具名、属性名、长度，不含参数值本身。
	 */
	@Test
	void countsOversizedArgumentInCodePointsAndKeepsValueOutOfLogSummary() {
		ToolCallParser.ModelTurn turn = parser
				.parse(AgentFixtures.json(AgentFixtures.root().resolve("model-response/oversized-arg.json")));
		ToolCall call = turn.toolCalls().get(0);
		String query = call.arguments().get("query").asText();
		int codePoints = query.codePointCount(0, query.length());

		ToolCallParser.Validation validation = parser.validate(call, registry);

		assertThat(validation.reason()).isEqualTo("TOO_LARGE");
		assertThat(validation.logSummary())
				.contains("kb_search")
				.contains("query")
				.contains(String.valueOf(codePoints))
				.doesNotContain("expense-report");
		assertThat(validation.message()).doesNotContain("expense-report");
	}

	/**
	 * AGT-TOOL-008 断言 2 的判别用例：300 个非 BMP 字符（每个占 2 个 UTF-16 单元）
	 * 的 query，码点数 300 ≤ maxLength 500，必须通过校验。
	 * 按 UTF-16 长度计数的实现会在这里把 600 > 500 误判为超长。
	 */
	@Test
	void treatsSchemaLengthLimitsAsUnicodeCodePointsNotUtf16Units() {
		// 判别序 7（maxLength=500）：300 码点 ≤ 500 必须通过；按 UTF-16 计数会把 600 > 500 误判。
		String query = "😀".repeat(300); // 300 个 emoji：300 码点、600 UTF-16 单元
		ToolCallParser.Validation validation = parser.validate(
				kbSearchCall("{\"query\": \"" + query + "\", \"knowledgeBaseId\": \"1\"}"), registry);

		assertThat(validation.valid()).isTrue();

		// 判别序 3（单属性 2000 码点上限，AGT-TOOL-008 断言 2 真正所指）：1100 码点 ≤ 2000
		// 不触发 TOO_LARGE，只触发 maxLength=500 的 CONSTRAINT_VIOLATION；在序 3 里按
		// UTF-16 计数的实现会把 2200 > 2000 误判成 TOO_LARGE，reason 就变了。
		String longQuery = "😀".repeat(1100); // 1100 码点、2200 UTF-16 单元、4400 UTF-8 字节
		ToolCallParser.Validation oversizedByUtf16Only = parser.validate(
				kbSearchCall("{\"query\": \"" + longQuery + "\", \"knowledgeBaseId\": \"1\"}"), registry);

		assertThat(oversizedByUtf16Only.valid()).isFalse();
		assertThat(oversizedByUtf16Only.reason()).isEqualTo("CONSTRAINT_VIOLATION");
	}

	/**
	 * 协议第 6 节 CONTENT_FILTERED：finish_reason=content_filter 的响应必须判 FAILED 且
	 * 错误码为 CONTENT_FILTERED（retryable=false）——判成 COMPLETED 会把被过滤的输出落成
	 * 成功回答；归入 MODEL_UNAVAILABLE（retryable=true）会触发注定失败的自动重试。
	 */
	@Test
	void mapsContentFilterFinishReasonToContentFilteredFailure() {
		ToolCallParser.ModelTurn filtered = parser.parse(AgentFixtures.MAPPER.readTree("""
				{"choices":[{"index":0,"message":{"role":"assistant","content":"部分正文",
				 "refusal":null,"tool_calls":null},"finish_reason":"content_filter"}]}
				"""));

		assertThat(filtered.outcome()).isEqualTo(ToolCallParser.TurnOutcome.FAILED);
		assertThat(filtered.errorCode()).isEqualTo("CONTENT_FILTERED");
		// INV-M7：已产出的部分正文保留，落库层据此存半截正文而不是丢弃。
		assertThat(filtered.content()).isEqualTo("部分正文");

		ToolCallParser.ModelTurn filteredEmpty = parser.parse(AgentFixtures.MAPPER.readTree("""
				{"choices":[{"index":0,"message":{"role":"assistant","content":"",
				 "refusal":null,"tool_calls":null},"finish_reason":"content_filter"}]}
				"""));
		assertThat(filteredEmpty.errorCode()).isEqualTo("CONTENT_FILTERED");
	}

	/**
	 * 5.3 节的子集明确支持 items 与嵌套 object，因此 5.4 节的校验必须递归执行：
	 * 只查顶层会让嵌套层的未知属性绕过 L2 拦截层（"多塞一个越权参数"从嵌套对象进来）、
	 * 让数组元素的类型与长度完全失守。注册期同理：嵌套 object 不写
	 * additionalProperties=false 也必须启动失败。
	 */
	@Test
	void validatesNestedSchemasRecursivelyNotJustTopLevel() {
		Map<String, ToolCallParser.ToolContract> nestedRegistry = Map.of("kb_filter_search",
				ToolCallParser.newContract("kb_filter_search", """
						{"type":"object","additionalProperties":false,
						 "required":["query"],
						 "properties":{
						   "query":{"type":"string","minLength":1,"maxLength":500},
						   "tags":{"type":"array","items":{"type":"string","maxLength":10},"maxItems":5},
						   "filter":{"type":"object","additionalProperties":false,
						     "properties":{"department":{"type":"string","maxLength":20}}}}}
						""", "CUSTOMER", "KNOWLEDGE_BASE_GRANT", false, true, 3000,
						ToolCallParser.FailureMode.RETRYABLE));

		// 嵌套对象里的未知属性 → UNKNOWN_PROPERTY，detail 用点号路径。
		ToolCallParser.Validation nestedUnknown = parser.validate(nestedCall(
				"{\"query\": \"报销\", \"filter\": {\"department\": \"财务部\", \"userId\": \"7\"}}"),
				nestedRegistry);
		assertThat(nestedUnknown.valid()).isFalse();
		assertThat(nestedUnknown.reason()).isEqualTo("UNKNOWN_PROPERTY");
		assertThat(nestedUnknown.details()).contains("filter.userId");

		// 数组元素类型不符 → TYPE_MISMATCH，detail 用下标路径。
		ToolCallParser.Validation wrongItemType = parser.validate(nestedCall(
				"{\"query\": \"报销\", \"tags\": [\"a\", 5]}"), nestedRegistry);
		assertThat(wrongItemType.reason()).isEqualTo("TYPE_MISMATCH");
		assertThat(wrongItemType.details()).contains("tags[1]");

		// 数组元素超出 items 的 maxLength → CONSTRAINT_VIOLATION。
		ToolCallParser.Validation itemTooLong = parser.validate(nestedCall(
				"{\"query\": \"报销\", \"tags\": [\"0123456789A\"]}"), nestedRegistry);
		assertThat(itemTooLong.reason()).isEqualTo("CONSTRAINT_VIOLATION");
		assertThat(itemTooLong.details()).contains("tags[0]");

		// 嵌套字符串超单属性 2000 码点上限 → TOO_LARGE（序 3 对任意深度生效）。
		ToolCallParser.Validation nestedTooLarge = parser.validate(nestedCall(
				"{\"query\": \"报销\", \"filter\": {\"department\": \"" + "x".repeat(2001) + "\"}}"),
				nestedRegistry);
		assertThat(nestedTooLarge.reason()).isEqualTo("TOO_LARGE");
		assertThat(nestedTooLarge.details()).contains("filter.department");
		assertThat(nestedTooLarge.logSummary()).contains("2001");

		// 注册期：嵌套 object 漏写 additionalProperties=false → 启动失败。
		assertThatThrownBy(() -> ToolCallParser.newContract("kb_filter_search", """
				{"type":"object","additionalProperties":false,
				 "required":["query"],
				 "properties":{
				   "query":{"type":"string"},
				   "filter":{"type":"object","properties":{"department":{"type":"string"}}}}}
				""", "CUSTOMER", "KNOWLEDGE_BASE_GRANT", false, true, 3000,
				ToolCallParser.FailureMode.RETRYABLE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("additionalProperties");
	}

	/** AGT-TOOL-006 断言 3：integer 接受 5 与 5.0，拒绝 5.5 与任何字符串，不做隐式转换。 */
	@Test
	void acceptsIntegralValuedNumbersForIntegerAndRejectsFractionsAndStrings() {
		assertThat(parser.validate(kbSearchCallWithTopK("5"), registry).valid()).isTrue();
		assertThat(parser.validate(kbSearchCallWithTopK("5.0"), registry).valid()).isTrue();

		ToolCallParser.Validation fraction = parser.validate(kbSearchCallWithTopK("5.5"), registry);
		assertThat(fraction.valid()).isFalse();
		assertThat(fraction.reason()).isEqualTo("TYPE_MISMATCH");

		ToolCallParser.Validation string = parser.validate(kbSearchCallWithTopK("\"5\""), registry);
		assertThat(string.valid()).isFalse();
		assertThat(string.reason()).isEqualTo("TYPE_MISMATCH");
	}

	/**
	 * 5.4 节："校验器必须收集全部违规项放进 details 一并回喂模型，但 reason 只取
	 * 表中顺序的第一条。"同时缺必填、带未知属性、类型不符时，reason 必须是
	 * MISSING_REQUIRED（序 4 先于序 5、6），details 三个属性名都在。
	 */
	@Test
	void collectsAllViolationsButReportsFirstReasonByFixedValidationOrder() {
		ToolCallParser.Validation validation = parser.validate(
				kbSearchCall("{\"query\": \"报销\", \"filter\": \"x\", \"topK\": \"5\"}"), registry);

		assertThat(validation.valid()).isFalse();
		assertThat(validation.reason()).isEqualTo("MISSING_REQUIRED");
		assertThat(validation.details()).contains("knowledgeBaseId", "filter", "topK");
	}

	/**
	 * AGT-STEP-002 断言 3 的协议层切片（判别力所在）：identical-repeat 序列三次调用的
	 * arguments 原始字符串两两不同（属性顺序不同、第三次 topK 写成 5.0），但按 7.4 节
	 * 规范化后完全相等。直接对原始字符串比较的实现会漏判死循环。
	 */
	@Test
	void normalizationJudgesIdenticalRepeatSequenceAsTheSameCall() {
		JsonNode sequence = AgentFixtures
				.json(AgentFixtures.root().resolve("model-response/sequences/identical-repeat.json"));
		JsonNode expectation = AgentFixtures.expectationFor("sequences.json", "identical-repeat.json");

		List<ToolCall> calls = new ArrayList<>();
		for (int i = 0; i < sequence.path("responses").size(); i++) {
			calls.addAll(parser.parse(sequence.path("responses").get(i)).toolCalls());
		}
		assertThat(calls).hasSize(3);

		Set<String> rawForms = new HashSet<>();
		Set<String> normalizedForms = new HashSet<>();
		for (ToolCall call : calls) {
			ToolCallParser.Validation validation = parser.validate(call, registry);
			assertThat(validation.valid()).isTrue();
			rawForms.add(call.rawArguments());
			// 顺序敏感的直接序列化：规范化不做（属性顺序不同、5.0 未归一）这里就不会收敛到 1。
			normalizedForms.add(AgentFixtures.MAPPER.writeValueAsString(validation.normalizedArguments()));
		}
		assertThat(rawForms).hasSize(3); // 原始字符串两两不同
		assertThat(normalizedForms).hasSize(1); // 规范化后完全相等 → 死循环检测应在第 3 步命中
		assertThat(normalizedForms.iterator().next())
				.isEqualTo(AgentFixtures.MAPPER.writeValueAsString(expectation.get("normalizedArguments")));
	}

	/**
	 * AGT-STEP-001 断言 5 的协议层切片：step-limit-loop 序列 6 次调用的
	 * (工具名, 规范化参数) 两两不同，死循环检测不得先于步数上限触发。
	 */
	@Test
	void normalizationKeepsDistinctCallsDistinctAcrossStepLimitLoop() {
		JsonNode sequence = AgentFixtures
				.json(AgentFixtures.root().resolve("model-response/sequences/step-limit-loop.json"));

		Set<String> deathLoopKeys = new HashSet<>();
		int totalCalls = 0;
		for (int i = 0; i < sequence.path("responses").size(); i++) {
			for (ToolCall call : parser.parse(sequence.path("responses").get(i)).toolCalls()) {
				ToolCallParser.Validation validation = parser.validate(call, registry);
				assertThat(validation.valid()).isTrue();
				deathLoopKeys.add(call.name() + "|"
						+ AgentFixtures.MAPPER.writeValueAsString(validation.normalizedArguments()));
				totalCalls++;
			}
		}
		assertThat(totalCalls).isEqualTo(6);
		assertThat(deathLoopKeys).hasSize(6);
	}

	/**
	 * AGT-INJ-902 断言 2 的协议层切片（硬门禁）：同形字工具名 kb_ѕearch（U+0455）不得被
	 * 归一化后匹配到真实的 kb_search——工具名精确匹配，不做 NFKC 归一、不做模糊匹配、
	 * 不做大小写折叠。任何为"容错"加的归一化都会给攻击者开一条把变体映射到真实工具的通道。
	 */
	@Test
	void matchesToolNamesExactlyWithoutUnicodeNormalization() {
		String homoglyphName = "kb_ѕearch"; // 西里尔字母 ѕ（U+0455），肉眼与拉丁 s 无法区分
		assertThat(homoglyphName).isNotEqualTo("kb_search");

		ToolCallParser.Validation validation = parser.validate(
				new ToolCall("call_1", homoglyphName,
						"{\"query\": \"报销\", \"knowledgeBaseId\": \"1\"}",
						AgentFixtures.MAPPER.readTree("{\"query\": \"报销\", \"knowledgeBaseId\": \"1\"}"),
						false),
				registry);

		assertThat(validation.valid()).isFalse();
		assertThat(validation.code()).isEqualTo("TOOL_NOT_FOUND");
	}

	/**
	 * AGT-AUTH-005 断言 2 的协议层切片（硬门禁）：工具参数中夹带 userId / principal / role
	 * 等身份字段，必须命中 UNKNOWN_PROPERTY 被拒绝（additionalProperties=false）。
	 * 权限主体只能来自认证上下文，永不来自模型输出（INV-T1）。
	 */
	@Test
	void rejectsIdentityFieldsSmuggledIntoToolArguments() {
		ToolCallParser.Validation validation = parser.validate(kbSearchCall(
				"{\"query\": \"报销\", \"knowledgeBaseId\": \"1\", \"userId\": \"7\", \"role\": \"EDITOR\"}"),
				registry);

		assertThat(validation.valid()).isFalse();
		assertThat(validation.code()).isEqualTo("TOOL_ARGUMENT_INVALID");
		assertThat(validation.reason()).isEqualTo("UNKNOWN_PROPERTY");
		assertThat(validation.details()).contains("userId", "role");
	}

	/**
	 * 注册期校验（5.1~5.3 节）：配置错误必须在构造注册表时炸，而不是等某个用户恰好
	 * 触发那个工具才炸。含 AGT-STEP-006 断言 4 的协议层切片
	 * （sideEffect=true 且 failureMode=RETRYABLE → 启动失败）。
	 */
	@Test
	void refusesToRegisterContractsThatViolateTheProtocol() {
		String validSchema = """
				{"type":"object","additionalProperties":false,"required":["query"],
				 "properties":{"query":{"type":"string"}}}
				""";

		// 5.2 节命名规范：连字符、camelCase 都不合法。
		assertThatThrownBy(() -> ToolCallParser.newContract("kb-search", validSchema,
				"CUSTOMER", "NONE", false, true, 3000, ToolCallParser.FailureMode.RETRYABLE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("kb-search").hasMessageContaining("命名规范");
		assertThatThrownBy(() -> ToolCallParser.newContract("getDocument", validSchema,
				"CUSTOMER", "NONE", false, true, 3000, ToolCallParser.FailureMode.RETRYABLE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("getDocument");

		// AGT-STEP-006 断言 4：自动重试有副作用的工具会造成重复执行。
		assertThatThrownBy(() -> ToolCallParser.newContract("notify_send", validSchema,
				"CUSTOMER", "NONE", true, true, 3000, ToolCallParser.FailureMode.RETRYABLE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("sideEffect").hasMessageContaining("RETRYABLE");

		// 5.3 节：additionalProperties 必须显式写 false——默认值会让"忘记写"和"故意允许"无法区分。
		assertThatThrownBy(() -> ToolCallParser.newContract("kb_search", """
				{"type":"object","required":["query"],"properties":{"query":{"type":"string"}}}
				""", "CUSTOMER", "NONE", false, true, 3000, ToolCallParser.FailureMode.RETRYABLE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("additionalProperties");

		// 5.3 节：子集之外的关键字（pattern）→ 启动失败，错误信息指出工具名与关键字名。
		assertThatThrownBy(() -> ToolCallParser.newContract("kb_search", """
				{"type":"object","additionalProperties":false,"required":["query"],
				 "properties":{"query":{"type":"string","pattern":"^.+$"}}}
				""", "CUSTOMER", "NONE", false, true, 3000, ToolCallParser.FailureMode.RETRYABLE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("kb_search").hasMessageContaining("pattern");
	}

	/** 5.5 节：内置注册表的三个工具及其属性逐项对照协议表格。 */
	@Test
	void builtinRegistryMatchesProtocolSectionFiveFive() {
		assertThat(registry.keySet())
				.containsExactlyInAnyOrder("kb_search", "doc_fetch", "conversation_title_set");

		ToolCallParser.ToolContract kbSearch = registry.get("kb_search");
		assertThat(kbSearch.resourceScope()).isEqualTo("KNOWLEDGE_BASE_GRANT");
		assertThat(kbSearch.sideEffect()).isFalse();
		assertThat(kbSearch.failureMode()).isEqualTo(ToolCallParser.FailureMode.RETRYABLE);

		ToolCallParser.ToolContract titleSet = registry.get("conversation_title_set");
		assertThat(titleSet.sideEffect()).isTrue();
		assertThat(titleSet.failureMode()).isEqualTo(ToolCallParser.FailureMode.NON_RETRYABLE);
		assertThat(titleSet.resourceScope()).isEqualTo("CURRENT_CONVERSATION");
		// 5.5 节的刻意设计：conversation_title_set 没有 conversationId 参数，会话 ID 由
		// 服务端从请求上下文注入，模型无法指定别人的会话（injection/005 的反向验证）。
		assertThat(titleSet.parameters().path("properties").has("conversationId")).isFalse();
	}

	private static ToolCall kbSearchCall(String argumentsJson) {
		return new ToolCall("call_t", "kb_search", argumentsJson,
				AgentFixtures.MAPPER.readTree(argumentsJson), false);
	}

	private static ToolCall nestedCall(String argumentsJson) {
		return new ToolCall("call_n", "kb_filter_search", argumentsJson,
				AgentFixtures.MAPPER.readTree(argumentsJson), false);
	}

	private static ToolCall kbSearchCallWithTopK(String topKLiteral) {
		return kbSearchCall("{\"query\": \"报销\", \"knowledgeBaseId\": \"1\", \"topK\": " + topKLiteral + "}");
	}

	private static String enumName(Enum<?> value) {
		return value == null ? null : value.name();
	}

	private static String textOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}
}
