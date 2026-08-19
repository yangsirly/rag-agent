package yangsirly.rag_agent.agent.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 模型响应的解析与工具调用校验，对应协议 5.1~5.4、5.7、7.4 节。
 *
 * <p>【临时寄放】本类型位于 test 源码树，因为批次 4 的硬约束是零 main 改动
 * （见 docs/plans/agent-test-plan.md 5.3 节）。实现落地时把 protocol/ 下四个文件移到
 * src/main/java/yangsirly/rag_agent/agent/protocol/，测试只改 import 的包名。
 *
 * <p>解析与校验刻意分成两层（协议第 8 节待定项 T-1 的直接收益）：
 * <ul>
 * <li>{@link #parse}：把 SDK 响应形状（当前按 OpenAI tool_calls 书写）转成内部形态。
 * 换 SDK 只需要重写这一层；</li>
 * <li>{@link #validate}：5.4 节的 7 步校验。顺序固定是被测契约本身——同一份输入必须
 * 永远得到同一个 reason，否则 fixture 驱动的测试会随实现内部的遍历顺序变绿变红。
 * 校验对嵌套结构递归执行：5.3 节的子集明确支持 items 与嵌套 object，只查顶层会让
 * 嵌套层的未知属性绕过 L2 拦截层。</li>
 * </ul>
 *
 * <p>使用 tools.jackson（Jackson 3）而不是 com.fasterxml（Jackson 2）：前者是 Spring Boot 4
 * 的原生 JSON 库且在本项目为 compile 依赖；后者只经 jjwt-jackson 以 runtime scope 传递引入，
 * 迁移到 main 后不可在编译期使用。
 */
public final class ToolCallParser {

	/** 5.2 节：工具名 snake_case，动宾结构，域前缀在前。不符合者应用启动失败。 */
	private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,31}$");

	/** 5.4 节序 3：arguments 序列化后的总量上限。 */
	private static final int MAX_ARGUMENTS_BYTES = 8 * 1024;

	/** 5.4 节序 3：单个字符串属性的上限，按 Unicode 码点计数，任意嵌套深度都适用。 */
	private static final int MAX_STRING_PROPERTY_CODE_POINTS = 2000;

	/** 5.3 节：支持的 JSON Schema 子集。刻意很小——schema 有服务端校验器和模型两个消费者。 */
	private static final Set<String> SUPPORTED_SCHEMA_KEYWORDS = Set.of(
			"type", "required", "additionalProperties", "properties", "enum",
			"minLength", "maxLength", "minimum", "maximum", "items", "maxItems", "default");

	private static final ObjectMapper MAPPER = new JsonMapper();

	/** 2.6 节 finishReason；有工具调用时轮次未结束，finishReason 为 null。 */
	public enum FinishReason {
		STOP, LENGTH, REFUSAL
	}

	/** 单次模型响应对轮次的判定（口径同 fixtures/agent/expectations/model-response.json）。 */
	public enum TurnOutcome {
		COMPLETED, CONTINUES, FAILED
	}

	/** 5.1 节 failureMode。 */
	public enum FailureMode {
		RETRYABLE, NON_RETRYABLE
	}

	/**
	 * 一次模型响应解析后的内部形态。
	 * <ul>
	 * <li>空内容且无工具调用且无拒答 → FAILED、errorCode=MODEL_UNAVAILABLE——不得落一条
	 * content 为空的 COMPLETED 消息（INV-M4、第 6 节、待定项 T-7）；</li>
	 * <li>拒答（refusal）→ COMPLETED 而不是 FAILED——拒答是正确行为（2.6 节）；</li>
	 * <li>finish_reason=content_filter → FAILED、errorCode=CONTENT_FILTERED（第 6 节，
	 * retryable=false）——被过滤的输出不得当成功回答落库，也不得错报成 retryable 的
	 * MODEL_UNAVAILABLE（那正是第 6 节"违反时"点名的白烧 token 路径）。</li>
	 * </ul>
	 */
	public record ModelTurn(String content, FinishReason finishReason, TurnOutcome outcome,
			String errorCode, List<ToolCall> toolCalls) {
	}

	/** 5.1 节的注册条目。注册表是配置而不是运行时输入，配错了在构造时抛异常（快速失败）。 */
	public record ToolContract(String name, JsonNode parameters, String requiredRole,
			String resourceScope, boolean sideEffect, boolean idempotent, long timeoutMs,
			FailureMode failureMode) {
	}

	/**
	 * 5.4 节校验结果。
	 * <ul>
	 * <li>{@code details} 收集<b>全部</b>违规项一并回喂模型（让模型一次改对），嵌套属性
	 * 用点号路径（如 {@code filter.department}）、数组元素用下标（如 {@code tags[1]}），
	 * 但 {@code reason} 只取 5.4 节表中顺序的第一条；</li>
	 * <li>{@code message} 是回喂给模型的无害化文案（5.6 节）：TOOL_NOT_FOUND 时不透露
	 * 注册表中实际有哪些工具，任何情况下不含参数值、堆栈、SQL；</li>
	 * <li>{@code logSummary} 是 5.4 节序 3 日志约束的可执行形式——实现落地后日志只允许
	 * 打这一行摘要（工具名 + 属性名 + 长度），不得打参数值。超长参数极可能是注入
	 * payload，写进日志等于把它复制到一个权限更宽、留存更久的地方；</li>
	 * <li>{@code normalizedArguments} 仅校验通过时非空，<b>本身即是</b>按 7.4 节规范化后
	 * 的形态（属性名字典序、去未定义属性、数值规范化），是 argsHash 与 5.7 节死循环
	 * 检测的比较输入。</li>
	 * </ul>
	 */
	public record Validation(boolean valid, String code, String reason, List<String> details,
			JsonNode normalizedArguments, String message, String logSummary) {
	}

	/** 解析一次模型响应（OpenAI chat.completion 形状，见协议第 8 节待定项 T-1）。 */
	public ModelTurn parse(JsonNode chatCompletion) {
		JsonNode choices = chatCompletion.path("choices");
		if (!choices.isArray() || choices.size() == 0) {
			throw new IllegalArgumentException("模型响应缺少 choices");
		}
		JsonNode choice = choices.get(0);
		JsonNode message = choice.path("message");
		String finishReasonWire = textOrNull(choice.get("finish_reason"));
		List<ToolCall> toolCalls = new ArrayList<>();
		JsonNode toolCallsNode = message.path("tool_calls");
		for (int i = 0; i < toolCallsNode.size(); i++) {
			JsonNode entry = toolCallsNode.get(i);
			JsonNode function = entry.path("function");
			String rawArguments = textOrNull(function.get("arguments"));
			JsonNode arguments = parseArgumentsObject(rawArguments);
			toolCalls.add(new ToolCall(textOrNull(entry.get("id")), textOrNull(function.get("name")),
					rawArguments, arguments, arguments == null));
		}
		String content = textOrNull(message.get("content"));
		String refusal = textOrNull(message.get("refusal"));
		if ("content_filter".equals(finishReasonWire)) {
			// 第 6 节 CONTENT_FILTERED。已产出的部分正文按 INV-M7 保留，工具调用不执行。
			return new ModelTurn(content, null, TurnOutcome.FAILED, "CONTENT_FILTERED",
					List.copyOf(toolCalls));
		}
		if (!toolCalls.isEmpty()) {
			// 工具结果需回喂模型，轮次继续；同时携带的 content 是要推流的部分正文。
			return new ModelTurn(content, null, TurnOutcome.CONTINUES, null, List.copyOf(toolCalls));
		}
		if (refusal != null && !refusal.isEmpty()) {
			return new ModelTurn(refusal, FinishReason.REFUSAL, TurnOutcome.COMPLETED, null, List.of());
		}
		if (content != null && !content.isEmpty()) {
			FinishReason finishReason = "length".equals(finishReasonWire) ? FinishReason.LENGTH : FinishReason.STOP;
			return new ModelTurn(content, finishReason, TurnOutcome.COMPLETED, null, List.of());
		}
		return new ModelTurn(null, null, TurnOutcome.FAILED, "MODEL_UNAVAILABLE", List.of());
	}

	/** 5.4 节的 7 步校验。第一条命中的违规决定 reason，但 details 收集全部违规项。 */
	public Validation validate(ToolCall call, Map<String, ToolContract> registry) {
		// 序 1：工具名精确匹配注册表——不做 NFKC 归一、不做模糊匹配、不做大小写折叠。
		// 任何为"容错"加的归一化都会给同形字攻击开一条把变体映射到真实工具的通道
		// （5.2 节、fixtures/agent/injection/011）。
		ToolContract contract = registry.get(call.name());
		if (contract == null) {
			return new Validation(false, "TOOL_NOT_FOUND", null, List.of(), null,
					"工具 " + call.name() + " 不存在，请只使用工具清单中列出的工具。",
					"tool=" + call.name() + " code=TOOL_NOT_FOUND");
		}
		// 序 2：arguments 必须是合法 JSON 对象。解析失败可恢复，不抛异常（5.4 序 2）。
		if (call.argumentsInvalid() || call.arguments() == null || !call.arguments().isObject()) {
			return argumentInvalid(call, "MALFORMED_JSON", List.of(), Map.of());
		}

		Buckets buckets = new Buckets();
		// 序 3 的总量子条件：按 rawArguments 的 UTF-8 字节数。
		if (call.rawArguments() != null
				&& call.rawArguments().getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENTS_BYTES) {
			buckets.tooLarge.add("arguments");
		}
		collectViolations("", contract.parameters(), call.arguments(), buckets);

		String reason = buckets.firstReason();
		if (reason != null) {
			return argumentInvalid(call, reason, buckets.orderedDetails(), buckets.stringCodePoints);
		}
		return new Validation(true, null, null, List.of(),
				normalizeArguments(call.arguments(), contract), null,
				"tool=" + call.name() + " result=OK");
	}

	/**
	 * 7.4 节的参数规范化：属性名按字典序排序、去掉 schema 未定义的属性（各嵌套层都做，
	 * 不只顶层——否则模型在嵌套对象里塞变化的垃圾属性就能让每次调用的 argsHash 互不相同，
	 * 绕过 INV-I5 幂等与 5.7 节死循环检测）、数值规范化（5.0 与 5 视为同一值）。
	 */
	public JsonNode normalizeArguments(JsonNode arguments, ToolContract contract) {
		return normalizeWithSchema(contract.parameters(), arguments);
	}

	/** 递归的确定性形态：对象键字典序、数值规范化。不做属性删除（那一步需要 schema）。 */
	public JsonNode canonicalize(JsonNode node) {
		if (node.isObject()) {
			TreeMap<String, JsonNode> sorted = new TreeMap<>();
			for (Map.Entry<String, JsonNode> property : node.properties()) {
				sorted.put(property.getKey(), property.getValue());
			}
			ObjectNode out = MAPPER.createObjectNode();
			sorted.forEach((name, value) -> out.set(name, canonicalize(value)));
			return out;
		}
		if (node.isArray()) {
			ArrayNode out = MAPPER.createArrayNode();
			for (int i = 0; i < node.size(); i++) {
				out.add(canonicalize(node.get(i)));
			}
			return out;
		}
		if (node.isNumber()) {
			try {
				return MAPPER.readTree(node.decimalValue().stripTrailingZeros().toPlainString());
			} catch (RuntimeException nonFinite) {
				return node; // NaN / Infinity 等无法用 BigDecimal 表达的值原样保留
			}
		}
		return node;
	}

	/** 7.4 节 argsHash 的输入：确定性序列化，可直接比较或哈希。 */
	public String argsHashInput(JsonNode node) {
		return MAPPER.writeValueAsString(canonicalize(node));
	}

	/** 5.5 节的三个内置工具。kb_search 的 schema 逐字来自 5.1 节的注册条目示例。 */
	public static Map<String, ToolContract> builtinRegistry() {
		Map<String, ToolContract> registry = new LinkedHashMap<>();
		List<ToolContract> contracts = List.of(
				newContract("kb_search", """
						{"type":"object","additionalProperties":false,
						 "required":["query","knowledgeBaseId"],
						 "properties":{
						   "query":{"type":"string","minLength":1,"maxLength":500},
						   "knowledgeBaseId":{"type":"string","minLength":1,"maxLength":20},
						   "topK":{"type":"integer","minimum":1,"maximum":20,"default":5}}}
						""", "CUSTOMER", "KNOWLEDGE_BASE_GRANT", false, true, 3000, FailureMode.RETRYABLE),
				newContract("doc_fetch", """
						{"type":"object","additionalProperties":false,
						 "required":["docId"],
						 "properties":{"docId":{"type":"string"}}}
						""", "CUSTOMER", "KNOWLEDGE_BASE_GRANT", false, true, 3000, FailureMode.RETRYABLE),
				// conversation_title_set 刻意没有 conversationId 参数：会话 ID 由服务端从
				// 请求上下文注入，模型无法指定（5.5 节，injection/005 的反向验证）。
				newContract("conversation_title_set", """
						{"type":"object","additionalProperties":false,
						 "required":["title"],
						 "properties":{"title":{"type":"string","minLength":1,"maxLength":100}}}
						""", "CUSTOMER", "CURRENT_CONVERSATION", true, true, 3000, FailureMode.NON_RETRYABLE));
		for (ToolContract contract : contracts) {
			registry.put(contract.name(), contract);
		}
		return Map.copyOf(registry);
	}

	/** 构造并校验一条注册条目。校验失败抛异常——配置错误应在部署时炸（5.1 节）。 */
	public static ToolContract newContract(String name, String parametersJson, String requiredRole,
			String resourceScope, boolean sideEffect, boolean idempotent, long timeoutMs,
			FailureMode failureMode) {
		return validateContract(new ToolContract(name, MAPPER.readTree(parametersJson), requiredRole,
				resourceScope, sideEffect, idempotent, timeoutMs, failureMode));
	}

	/** 5.1~5.3 节的注册期校验，任一违反即抛 IllegalArgumentException（应用启动失败）。 */
	public static ToolContract validateContract(ToolContract contract) {
		if (contract.name() == null || !NAME_PATTERN.matcher(contract.name()).matches()) {
			throw new IllegalArgumentException(
					"工具 " + contract.name() + " 不符合命名规范 ^[a-z][a-z0-9_]{2,31}$（协议 5.2 节）");
		}
		if (!Set.of("CUSTOMER", "EDITOR").contains(contract.requiredRole())) {
			throw new IllegalArgumentException(
					"工具 " + contract.name() + " 的 requiredRole 非法：" + contract.requiredRole());
		}
		if (!Set.of("NONE", "CURRENT_CONVERSATION", "KNOWLEDGE_BASE_GRANT").contains(contract.resourceScope())) {
			throw new IllegalArgumentException(
					"工具 " + contract.name() + " 的 resourceScope 非法：" + contract.resourceScope());
		}
		if (contract.sideEffect() && contract.failureMode() == FailureMode.RETRYABLE) {
			throw new IllegalArgumentException("工具 " + contract.name()
					+ " 同时声明 sideEffect=true 与 failureMode=RETRYABLE："
					+ "自动重试有副作用的工具会造成重复执行（协议 5.1 节）");
		}
		if (!"object".equals(contract.parameters().path("type").asText(null))) {
			throw new IllegalArgumentException(
					"工具 " + contract.name() + " 的 parameters 根类型必须是 object（协议 5.3 节）");
		}
		validateSchemaNode(contract.name(), contract.parameters());
		return contract;
	}

	/**
	 * 递归校验一个 schema 节点：只允许 5.3 节子集内的关键字；每个 object 型节点
	 * （不只根）必须显式声明 additionalProperties=false——默认值会让"忘记写"和
	 * "故意允许"无法区分，而嵌套层漏写会让未知属性从嵌套对象绕进来。
	 */
	private static void validateSchemaNode(String toolName, JsonNode schema) {
		for (Map.Entry<String, JsonNode> keyword : schema.properties()) {
			if (!SUPPORTED_SCHEMA_KEYWORDS.contains(keyword.getKey())) {
				// 错误信息必须指出工具名与关键字名（5.3 节"违反时"）。
				throw new IllegalArgumentException("工具 " + toolName
						+ " 的 parameters 使用了不支持的关键字 " + keyword.getKey() + "（协议 5.3 节）");
			}
		}
		if ("object".equals(schema.path("type").asText(null))) {
			JsonNode additional = schema.get("additionalProperties");
			if (additional == null || !additional.isBoolean() || additional.asBoolean(true)) {
				throw new IllegalArgumentException("工具 " + toolName
						+ " 的每个 object 型 schema 节点都必须显式声明 additionalProperties=false（协议 5.3 节）");
			}
			for (Map.Entry<String, JsonNode> property : schema.path("properties").properties()) {
				validateSchemaNode(toolName, property.getValue());
			}
		}
		if (schema.has("items")) {
			validateSchemaNode(toolName, schema.get("items"));
		}
	}

	/** 5.4 节序 3~7 的违规收集桶。reason 取第一个非空桶（表中顺序），details 按桶序合并。 */
	private static final class Buckets {
		final List<String> tooLarge = new ArrayList<>();
		final List<String> missing = new ArrayList<>();
		final List<String> unknown = new ArrayList<>();
		final List<String> typeMismatch = new ArrayList<>();
		final List<String> constraint = new ArrayList<>();
		final Map<String, Integer> stringCodePoints = new LinkedHashMap<>();

		String firstReason() {
			if (!tooLarge.isEmpty()) {
				return "TOO_LARGE";
			}
			if (!missing.isEmpty()) {
				return "MISSING_REQUIRED";
			}
			if (!unknown.isEmpty()) {
				return "UNKNOWN_PROPERTY";
			}
			if (!typeMismatch.isEmpty()) {
				return "TYPE_MISMATCH";
			}
			if (!constraint.isEmpty()) {
				return "CONSTRAINT_VIOLATION";
			}
			return null;
		}

		List<String> orderedDetails() {
			LinkedHashSet<String> details = new LinkedHashSet<>();
			details.addAll(tooLarge);
			details.addAll(missing);
			details.addAll(unknown);
			details.addAll(typeMismatch);
			details.addAll(constraint);
			return List.copyOf(details);
		}
	}

	/**
	 * 对一个 schema 节点与对应值递归收集序 3~7 的违规。顶层属性的 detail 就是属性名
	 * （与 expectations sidecar 的口径一致），嵌套属性用点号路径、数组元素用下标。
	 */
	private void collectViolations(String path, JsonNode schema, JsonNode value, Buckets buckets) {
		// 序 3：任意深度的字符串属性都受 2000 码点上限约束。按 Unicode 码点计数而不是
		// UTF-16 长度——按 UTF-16 算一个 emoji 占 2，长度限制会莫名收紧。
		if (value.isTextual()) {
			String text = value.asText();
			int codePoints = text.codePointCount(0, text.length());
			if (codePoints > MAX_STRING_PROPERTY_CODE_POINTS) {
				buckets.tooLarge.add(pathOrRoot(path));
				buckets.stringCodePoints.put(pathOrRoot(path), codePoints);
			}
		}
		// 序 6：类型匹配。不做隐式转换："5" 传给 integer 是 TYPE_MISMATCH——隐式转换会把
		// 模型的系统性错误掩盖成偶发问题，ID 字段上尤其危险（5.4 节）。类型不对时不再
		// 做取值约束与下钻。
		String type = schema.path("type").asText(null);
		if (type != null && !matchesType(type, value)) {
			buckets.typeMismatch.add(pathOrRoot(path));
			return;
		}
		// 序 7：长度 / 范围 / enum / maxItems。
		if (violatesConstraints(schema, value)) {
			buckets.constraint.add(pathOrRoot(path));
		}
		if (value.isObject()) {
			JsonNode properties = schema.path("properties");
			// 序 4：required 全部出现且不为 null。
			JsonNode required = schema.path("required");
			for (int i = 0; i < required.size(); i++) {
				String name = required.get(i).asText();
				JsonNode child = value.get(name);
				if (child == null || child.isNull()) {
					buckets.missing.add(childPath(path, name));
				}
			}
			for (Map.Entry<String, JsonNode> property : value.properties()) {
				String propertyPath = childPath(path, property.getKey());
				JsonNode childSchema = properties.get(property.getKey());
				// 序 5：无 schema 未定义的属性。拒绝而不是忽略——忽略会让"多塞一个越权
				// 参数"的攻击悄悄通过校验层（5.3 节 additionalProperties 必须显式 false）。
				if (childSchema == null) {
					buckets.unknown.add(propertyPath);
					continue;
				}
				JsonNode child = property.getValue();
				if (child.isNull()) {
					// required 为 null 已在序 4 报过；可选属性显式传 null 不匹配任何声明类型。
					if (!buckets.missing.contains(propertyPath)) {
						buckets.typeMismatch.add(propertyPath);
					}
					continue;
				}
				collectViolations(propertyPath, childSchema, child, buckets);
			}
		}
		if (value.isArray() && schema.has("items")) {
			for (int i = 0; i < value.size(); i++) {
				collectViolations(path + "[" + i + "]", schema.get("items"), value.get(i), buckets);
			}
		}
	}

	private JsonNode normalizeWithSchema(JsonNode schema, JsonNode value) {
		if (value.isObject()) {
			JsonNode properties = schema.path("properties");
			TreeMap<String, JsonNode> sorted = new TreeMap<>();
			for (Map.Entry<String, JsonNode> property : value.properties()) {
				if (properties.has(property.getKey())) {
					sorted.put(property.getKey(), property.getValue());
				}
			}
			ObjectNode out = MAPPER.createObjectNode();
			sorted.forEach((name, child) -> out.set(name, normalizeWithSchema(properties.get(name), child)));
			return out;
		}
		if (value.isArray() && schema.has("items")) {
			ArrayNode out = MAPPER.createArrayNode();
			for (int i = 0; i < value.size(); i++) {
				out.add(normalizeWithSchema(schema.get("items"), value.get(i)));
			}
			return out;
		}
		return canonicalize(value);
	}

	private static Validation argumentInvalid(ToolCall call, String reason, List<String> details,
			Map<String, Integer> stringCodePoints) {
		String message = details.isEmpty()
				? "工具参数不是合法的 JSON 对象，请给出符合参数 schema 的完整 JSON。"
				: "工具参数未通过校验（" + reason + "）：" + String.join("、", details) + "。请修正后重试。";
		StringBuilder logSummary = new StringBuilder("tool=").append(call.name())
				.append(" code=TOOL_ARGUMENT_INVALID reason=").append(reason)
				.append(" details=").append(details);
		if (!stringCodePoints.isEmpty()) {
			logSummary.append(" stringCodePoints=").append(stringCodePoints);
		}
		return new Validation(false, "TOOL_ARGUMENT_INVALID", reason, details, null, message,
				logSummary.toString());
	}

	private static boolean matchesType(String type, JsonNode value) {
		return switch (type) {
			case "string" -> value.isTextual();
			// integer 接受无小数部分的数值（5、5.0），拒绝 5.5 与任何字符串（5.3 节）。
			case "integer" -> value.isNumber() && isIntegralValue(value);
			case "number" -> value.isNumber();
			case "boolean" -> value.isBoolean();
			case "array" -> value.isArray();
			case "object" -> value.isObject();
			default -> false;
		};
	}

	private static boolean isIntegralValue(JsonNode value) {
		if (value.isIntegralNumber()) {
			return true;
		}
		try {
			return value.decimalValue().stripTrailingZeros().scale() <= 0;
		} catch (RuntimeException nonFinite) {
			return false; // NaN / Infinity
		}
	}

	private boolean violatesConstraints(JsonNode schema, JsonNode value) {
		if (value.isTextual()) {
			String text = value.asText();
			int codePoints = text.codePointCount(0, text.length());
			if (schema.has("minLength") && codePoints < schema.get("minLength").asInt(0)) {
				return true;
			}
			if (schema.has("maxLength") && codePoints > schema.get("maxLength").asInt(Integer.MAX_VALUE)) {
				return true;
			}
		}
		if (value.isNumber()) {
			// minimum / maximum 是闭区间（5.3 节）。
			if (schema.has("minimum")
					&& value.decimalValue().compareTo(schema.get("minimum").decimalValue()) < 0) {
				return true;
			}
			if (schema.has("maximum")
					&& value.decimalValue().compareTo(schema.get("maximum").decimalValue()) > 0) {
				return true;
			}
		}
		if (value.isArray() && schema.has("maxItems")
				&& value.size() > schema.get("maxItems").asInt(Integer.MAX_VALUE)) {
			return true;
		}
		if (schema.has("enum")) {
			JsonNode allowed = schema.get("enum");
			boolean matched = false;
			for (int i = 0; i < allowed.size(); i++) {
				if (argsHashInput(allowed.get(i)).equals(argsHashInput(value))) {
					matched = true;
					break;
				}
			}
			if (!matched) {
				return true;
			}
		}
		return false;
	}

	private static String pathOrRoot(String path) {
		return path.isEmpty() ? "arguments" : path;
	}

	private static String childPath(String path, String name) {
		return path.isEmpty() ? name : path + "." + name;
	}

	private static JsonNode parseArgumentsObject(String rawArguments) {
		if (rawArguments == null) {
			return null;
		}
		try {
			JsonNode parsed = MAPPER.readTree(rawArguments);
			return parsed.isObject() ? parsed : null;
		} catch (JacksonException malformed) {
			return null;
		}
	}

	private static String textOrNull(JsonNode node) {
		return node == null || node.isNull() ? null : node.asText();
	}
}
