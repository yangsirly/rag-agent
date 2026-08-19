package yangsirly.rag_agent.agent.protocol;

import tools.jackson.databind.JsonNode;

/**
 * 一次模型请求的工具调用，对应协议 2.3 节 tool_call 事件的服务端内部形态。
 *
 * <p>【临时寄放】本类型位于 test 源码树，因为批次 4 的硬约束是零 main 改动
 * （见 docs/plans/agent-test-plan.md 5.3 节）。Agent 实现落地时的迁移动作是确定且机械的：
 * 把 protocol/ 下四个文件移到 src/main/java/yangsirly/rag_agent/agent/protocol/，
 * 测试文件只改 import 的包名。
 *
 * <p>字段语义（agent-protocol.md 2.3 节）：
 * <ul>
 * <li>{@code callId} 来自模型响应；服务端发现重复或缺失时以 srv_&lt;seq&gt; 替代——替代逻辑
 * 属于事件发射层，不在本类型；</li>
 * <li>{@code rawArguments} 是模型给出的参数原文（OpenAI 形状下是一个 JSON 字符串，见协议
 * 第 8 节待定项 T-1）。5.4 节序 3 的 8 KiB 总量检查以它的 UTF-8 字节长度为准；</li>
 * <li>{@code arguments} 是解析后的参数对象。原文不是合法 JSON 对象时为 {@code null}，
 * 且 {@code argumentsInvalid} 为 {@code true}（协议 5.4 序 2、2.3 节 argumentsInvalid 字段）——
 * 解析失败是可恢复的，tool_call 事件仍要发出。</li>
 * </ul>
 */
public record ToolCall(String callId, String name, String rawArguments, JsonNode arguments,
		boolean argumentsInvalid) {
}
