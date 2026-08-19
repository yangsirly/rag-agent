package yangsirly.rag_agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;

import yangsirly.rag_agent.agent.protocol.SseEvent;
import yangsirly.rag_agent.agent.support.AgentFixtures;

/**
 * fixtures/agent/ 数据集的验收测试（agent-test-plan.md 3 节末尾的验收条款：
 * "所有 fixture 文件必须能被 Jackson 正常读取，批次 4 里写一个 FixtureLoaderTests
 * 遍历加载，就是验收本身"）。
 *
 * <p>除"能加载"之外还验三件事：期望文件与样本文件一一对应（缺一边测试就在断言
 * 凭空想象的行为）、注入语料的元数据完整且不把 L5 当拦截层、数据卫生
 * （外部域名一律 .invalid，确保任何环境下不可解析）。
 *
 * <p>本类不加载 Spring 上下文。
 */
class FixtureLoaderTests {

	private static final Set<String> ATTACK_TYPES = Set.of(
			"DIRECT_INSTRUCTION_OVERRIDE", "ROLE_IMPERSONATION", "TOOL_LURE", "DATA_EXFILTRATION",
			"ENCODING_BYPASS", "MULTILINGUAL_BYPASS", "CITATION_FORGERY");

	/** L5 提示词层不作为门禁（协议 5.6 节），任何用例都不允许把它声明为期望拦截层。 */
	private static final Set<String> GATE_LAYERS = Set.of("L1", "L2", "L3", "L4");

	private static final Pattern URL_HOST = Pattern.compile("https?://([^/\\s\"'\\\\]+)");

	private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

	static List<Path> modelResponseFixtures() {
		return AgentFixtures.filesIn("model-response", ".json");
	}

	static List<Path> sequenceFixtures() {
		return AgentFixtures.filesIn("model-response/sequences", ".json");
	}

	static List<Path> sseStreamFixtures() {
		return AgentFixtures.filesIn("sse-stream", ".txt");
	}

	static List<Path> injectionFixtures() {
		return AgentFixtures.filesIn("injection", ".json");
	}

	@ParameterizedTest
	@MethodSource("modelResponseFixtures")
	void loadsEveryModelResponseFixtureAsSdkShapedJson(Path fixture) {
		JsonNode response = AgentFixtures.json(fixture);
		// 样本必须保持 SDK 响应形状（README 第 2 节）：choices[0].message 是解析入口。
		assertThat(response.path("choices").isArray()).isTrue();
		assertThat(response.path("choices").get(0).path("message").isObject()).isTrue();
	}

	@ParameterizedTest
	@MethodSource("sequenceFixtures")
	void loadsEverySequenceFixtureWithSdkShapedResponses(Path fixture) {
		JsonNode sequence = AgentFixtures.json(fixture);
		assertThat(sequence.path("scenario").asText("")).isNotEmpty();
		JsonNode responses = sequence.path("responses");
		assertThat(responses.isArray()).isTrue();
		assertThat(responses.size()).isGreaterThan(0);
		for (int i = 0; i < responses.size(); i++) {
			assertThat(responses.get(i).path("choices").get(0).path("message").isObject()).isTrue();
		}
	}

	@ParameterizedTest
	@MethodSource("sseStreamFixtures")
	void loadsEverySseStreamFixtureAsParsableFrames(Path fixture) {
		SseEvent.Stream stream = SseEvent.parseStream(AgentFixtures.text(fixture));
		assertThat(stream.events()).isNotEmpty();
		for (SseEvent event : stream.events()) {
			// 2.1 节：所有事件共有 seq 与 messageId 两个字段。
			assertThat(event.seq()).isPositive();
			assertThat(event.messageId()).isNotNull();
		}
	}

	@ParameterizedTest
	@MethodSource("injectionFixtures")
	void loadsEveryInjectionFixtureWithCompleteMetadata(Path fixture) {
		JsonNode payload = AgentFixtures.json(fixture);
		String fileName = fixture.getFileName().toString();

		String id = payload.path("id").asText("");
		assertThat(id).matches("INJ-\\d{3}");
		// 文件名前缀与 id 序号一致，供 agent-test-cases.md 按编号索引。
		assertThat(fileName).startsWith(id.substring(4));

		assertThat(payload.path("attackType").asText("")).isIn(ATTACK_TYPES);
		assertThat(payload.path("carrier").asText("")).isEqualTo("KNOWLEDGE_BASE_DOCUMENT");
		assertThat(payload.path("documentTitle").asText("")).isNotEmpty();
		assertThat(payload.path("documentBody").asText("")).isNotEmpty();
		assertThat(payload.path("expectedOutcome").asText("")).isIn("BLOCKED", "NEUTRALIZED");
		// 期望拦截层只能是 L1~L4：注入 payload 必须假定 L5（提示词层）已经失效。
		assertThat(payload.path("expectedLayer").asText("")).isIn(GATE_LAYERS);
		assertThat(payload.path("assertion").asText("")).isNotEmpty();
		assertThat(payload.path("protocolRefs").isArray()).isTrue();
		assertThat(payload.path("protocolRefs").size()).isGreaterThan(0);
	}

	/**
	 * 期望文件与样本文件必须一一对应：样本没有期望，测试就在断言凭空想象的行为；
	 * 期望没有样本，说明有场景只写了答案没写题目（README 第 4 节）。
	 */
	@Test
	void expectationsCoverExactlyTheFixtureFilesInBothDirections() {
		assertThat(expectationFileNames("model-response.json"))
				.containsExactlyInAnyOrderElementsOf(fileNames(modelResponseFixtures()));
		assertThat(expectationFileNames("sequences.json"))
				.containsExactlyInAnyOrderElementsOf(fileNames(sequenceFixtures()));
		assertThat(expectationFileNames("sse-stream.json"))
				.containsExactlyInAnyOrderElementsOf(fileNames(sseStreamFixtures()));
	}

	/** 数据集清单对照 README 第 1 节：11 单次响应 + 3 序列 + 6 事件流 + 14 注入 + 3 期望。 */
	@Test
	void fixtureInventoryMatchesTheReadme() {
		assertThat(modelResponseFixtures()).hasSize(11);
		assertThat(sequenceFixtures()).hasSize(3);
		assertThat(sseStreamFixtures()).hasSize(6);
		assertThat(injectionFixtures()).hasSize(14);
		assertThat(AgentFixtures.filesIn("expectations", ".json")).hasSize(3);
	}

	/**
	 * 数据卫生（README 第 6 节）：fixture 中出现的 URL 主机名与邮箱域名一律使用
	 * RFC 2606 保留的 .invalid 顶级域，确保任何环境下都不可解析、不会误发真实请求。
	 */
	@Test
	void usesOnlyReservedInvalidDomainsThroughoutTheDataset() {
		try (Stream<Path> files = Files.walk(AgentFixtures.root())) {
			files.filter(Files::isRegularFile).forEach(file -> {
				String text = AgentFixtures.text(file);
				Matcher url = URL_HOST.matcher(text);
				while (url.find()) {
					assertThat(url.group(1))
							.as("fixture %s 中的 URL 主机名必须以 .invalid 结尾", file.getFileName())
							.endsWith(".invalid");
				}
				Matcher email = EMAIL.matcher(text);
				while (email.find()) {
					assertThat(email.group(1))
							.as("fixture %s 中的邮箱域名必须以 .invalid 结尾", file.getFileName())
							.endsWith(".invalid");
				}
			});
		} catch (IOException walkFailure) {
			throw new UncheckedIOException(walkFailure);
		}
	}

	private static List<String> fileNames(List<Path> files) {
		return files.stream().map(path -> path.getFileName().toString()).toList();
	}

	private static List<String> expectationFileNames(String expectationsFile) {
		JsonNode expectations = AgentFixtures
				.json(AgentFixtures.root().resolve("expectations").resolve(expectationsFile));
		JsonNode fixtures = expectations.path("fixtures");
		return Stream.iterate(0, index -> index + 1).limit(fixtures.size())
				.map(index -> fixtures.get(index).path("file").asText(""))
				.toList();
	}
}
