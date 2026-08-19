package yangsirly.rag_agent.agent.protocol;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 一个已解析的 SSE 事件，对应协议 2.1~2.7 节的六种事件类型。
 *
 * <p>【临时寄放】本类型位于 test 源码树，因为批次 4 的硬约束是零 main 改动
 * （见 docs/plans/agent-test-plan.md 5.3 节）。实现落地时把 protocol/ 下四个文件移到
 * src/main/java/yangsirly/rag_agent/agent/protocol/，测试只改 import 的包名。
 *
 * <p>解析约定（agent-protocol.md 2.1、2.9 节）：
 * <ul>
 * <li>行终止符同时接受 LF / CRLF / CR——SSE 规范允许三种，仓库里的 fixture 是 LF，但
 * Windows 上 core.autocrlf 可能在检出时改写换行，解析器不得依赖字节形态
 * （见 fixtures/agent/README.md 第 3 节）；</li>
 * <li>{@code :} 开头的行是注释（如心跳 {@code :hb}），不是事件、不占用 seq；</li>
 * <li>未知的 event 类型解析为 {@link EventType#UNKNOWN} 保留，由消费方忽略并记录，
 * 不得中断——为将来加事件类型留出前向兼容；</li>
 * <li>末尾未以空行终结的残帧按 SSE 规范丢弃——这正是"截断"（INV-S11）的形态。</li>
 * </ul>
 *
 * <p>2.1 节还规定 {@code id:} 行必须等于 payload 中的 seq、data 必须是单行 JSON，且把
 * 违反时的处理明确落在<b>消费方</b>（PROTOCOL_VIOLATION）。本类只负责把事实暴露出来
 * （{@code sseId} 与 {@code multiLineData}），判定在 {@link SseEventSequenceValidator}。
 */
public record SseEvent(EventType type, String rawType, long sseId, JsonNode data, boolean multiLineData) {

	private static final ObjectMapper MAPPER = new JsonMapper();

	public enum EventType {
		DELTA, TOOL_CALL, TOOL_RESULT, CITATION, DONE, ERROR, UNKNOWN;

		static EventType fromWire(String wireType) {
			return switch (wireType) {
				case "delta" -> DELTA;
				case "tool_call" -> TOOL_CALL;
				case "tool_result" -> TOOL_RESULT;
				case "citation" -> CITATION;
				case "done" -> DONE;
				case "error" -> ERROR;
				default -> UNKNOWN;
			};
		}
	}

	/** 全事件公共字段（2.1 节），缺失时返回 -1 以便校验器把它报成 INV-S1 而不是抛异常。 */
	public long seq() {
		return data.path("seq").asLong(-1);
	}

	/** 全事件公共字段（2.1 节）。 */
	public String messageId() {
		return data.path("messageId").asText(null);
	}

	/** delta 事件的正文增量（2.2 节）；字段缺失或为 null 时返回 null。 */
	public String deltaText() {
		JsonNode text = data.get("text");
		return text == null || text.isNull() ? null : text.asText();
	}

	/** tool_call / tool_result 事件的调用标识（2.3、2.4 节）。 */
	public String callId() {
		return data.path("callId").asText(null);
	}

	/** tool_call 事件的所属步号（2.3 节，定义见 5.7 节）。 */
	public long step() {
		return data.path("step").asLong(-1);
	}

	/** citation 事件的内联标记编号（2.5、4.1 节）。 */
	public long marker() {
		return data.path("marker").asLong(-1);
	}

	/** done 事件携带的最终落库正文（2.6 节）。 */
	public String content() {
		return data.path("content").asText(null);
	}

	/** error 事件（2.7 节）与 tool_result 事件（2.4 节）的错误码。 */
	public String errorCode() {
		return data.path("code").asText(null);
	}

	/** 解析结果：事件列表 + 注释行数（注释不是事件，单独计数供测试断言心跳约定）。 */
	public record Stream(List<SseEvent> events, int commentLineCount) {
	}

	/** 测试内联构造用。data 必须是单行 JSON 对象（2.1 节）。 */
	public static SseEvent of(String wireType, String dataJson) {
		JsonNode data = MAPPER.readTree(dataJson);
		return new SseEvent(EventType.fromWire(wireType), wireType, data.path("seq").asLong(-1), data, false);
	}

	/** 解析原始 SSE 帧文本。 */
	public static Stream parseStream(String raw) {
		List<SseEvent> events = new ArrayList<>();
		int commentLineCount = 0;
		String eventType = null;
		String id = null;
		List<String> dataLines = new ArrayList<>();
		String[] lines = raw.split("\r\n|\r|\n", -1);
		// 输入以行终止符收尾时，split(-1) 会多出一个人为的尾部空串。不剔除它，
		// "…data: {...}\n" 这种恰在帧内被切断的输入会被当成空行而误分发残帧——
		// 规范一致的消费端（如浏览器 EventSource）在 EOF 处丢弃未完结的帧。
		int lineCount = lines.length;
		if (lineCount > 0 && (raw.endsWith("\n") || raw.endsWith("\r"))) {
			lineCount--;
		}
		for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
			String line = lines[lineIndex];
			if (line.isEmpty()) {
				// 空行分发当前帧（SSE 规范）；只有 data 的帧才构成事件。
				if (!dataLines.isEmpty()) {
					JsonNode data = MAPPER.readTree(String.join("\n", dataLines));
					String wireType = eventType == null ? "" : eventType;
					events.add(new SseEvent(EventType.fromWire(wireType), wireType, parseSseId(id), data,
							dataLines.size() > 1));
				}
				eventType = null;
				id = null;
				dataLines = new ArrayList<>();
				continue;
			}
			if (line.startsWith(":")) {
				commentLineCount++;
				continue;
			}
			int colon = line.indexOf(':');
			String field = colon < 0 ? line : line.substring(0, colon);
			String value = colon < 0 ? "" : line.substring(colon + 1);
			if (value.startsWith(" ")) {
				value = value.substring(1);
			}
			switch (field) {
				case "event" -> eventType = value;
				case "id" -> id = value;
				case "data" -> dataLines.add(value);
				default -> {
					// 未知字段按 SSE 规范忽略。
				}
			}
		}
		return new Stream(List.copyOf(events), commentLineCount);
	}

	private static long parseSseId(String id) {
		if (id == null) {
			return -1;
		}
		try {
			return Long.parseLong(id.trim());
		} catch (NumberFormatException invalidId) {
			return -1;
		}
	}
}
