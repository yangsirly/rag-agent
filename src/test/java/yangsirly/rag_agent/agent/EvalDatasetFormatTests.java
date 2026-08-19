package yangsirly.rag_agent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import yangsirly.rag_agent.agent.support.AgentFixtures;

/**
 * docs/eval/ 评测集的格式校验（agent-test-plan.md 第 6 节的验收条款："可以在批次 4 里
 * 加一个 EvalDatasetFormatTests 校验，顺便让评测集格式也进 CI"）。
 *
 * <p>只验格式不验内容：每行合法 JSON、字段名与计划 6.1 / 6.2 节完全一致（不多不少）、
 * relevantChunkIds 在分块策略定型（待定项 T-6）前保持空数组、至少一道应拒答题、
 * mustInclude 写的是事实点而不是整句参考答案。评测本身不进 mvn test。
 *
 * <p>评测文件按 Maven 模块根目录相对路径定位（surefire 的工作目录即模块根）。
 */
class EvalDatasetFormatTests {

	private static final Set<String> RETRIEVAL_FIELDS = Set.of(
			"id", "query", "relevantDocIds", "relevantChunkIds", "note", "tags");

	private static final Set<String> ANSWER_FIELDS = Set.of(
			"id", "question", "mustInclude", "mustNotInclude", "mustCiteDocIds",
			"shouldRefuse", "referenceAnswer", "tags");

	/** 协议 4.4 节：评测集在真实语料导入前使用可读占位形式 kb-1/doc-3。 */
	private static final String DOC_ID_PLACEHOLDER = "kb-\\d+/doc-\\d+";

	@Test
	void retrievalGoldenLinesAreValidAndCarryExactlyThePlannedFields() {
		List<JsonNode> rows = readJsonl(Path.of("docs", "eval", "retrieval-golden.jsonl"));
		assertThat(rows).hasSizeBetween(3, 5);

		Set<String> ids = new HashSet<>();
		for (JsonNode row : rows) {
			assertThat(fieldNames(row)).isEqualTo(RETRIEVAL_FIELDS);
			String id = row.path("id").asText("");
			assertThat(id).matches("RET-\\d{3}");
			assertThat(ids.add(id)).as("题目 ID %s 不得重复", id).isTrue();
			assertThat(row.path("query").asText("")).isNotEmpty();
			// 数组字段先断言类型：劣化为字符串时 size() 也是 0，元素循环会静默跳过。
			assertThat(row.path("relevantDocIds").isArray()).isTrue();
			for (int i = 0; i < row.path("relevantDocIds").size(); i++) {
				assertThat(row.path("relevantDocIds").get(i).asText()).matches(DOC_ID_PLACEHOLDER);
			}
			// 待定项 T-6：分块策略定型前 relevantChunkIds 一律留空数组——这是刻意的。
			assertThat(row.path("relevantChunkIds").isArray()).isTrue();
			assertThat(row.path("relevantChunkIds").size()).isZero();
			assertThat(row.path("tags").isArray()).isTrue();
			assertThat(row.path("tags").size()).isGreaterThan(0);
		}
	}

	@Test
	void answerGoldenLinesAreValidAndCarryExactlyThePlannedFields() {
		List<JsonNode> rows = readJsonl(Path.of("docs", "eval", "answer-golden.jsonl"));
		assertThat(rows).hasSizeBetween(3, 5);

		Set<String> ids = new HashSet<>();
		int shouldRefuseCount = 0;
		for (JsonNode row : rows) {
			assertThat(fieldNames(row)).isEqualTo(ANSWER_FIELDS);
			String id = row.path("id").asText("");
			assertThat(id).matches("ANS-\\d{3}");
			assertThat(ids.add(id)).as("题目 ID %s 不得重复", id).isTrue();
			assertThat(row.path("question").asText("")).isNotEmpty();
			String referenceAnswer = row.path("referenceAnswer").asText("");
			assertThat(referenceAnswer).isNotEmpty();
			// 数组字段先断言类型：劣化为字符串时 size() 也是 0，元素循环会静默跳过。
			assertThat(row.path("mustInclude").isArray()).isTrue();
			assertThat(row.path("mustNotInclude").isArray()).isTrue();
			assertThat(row.path("mustCiteDocIds").isArray()).isTrue();
			assertThat(row.path("tags").isArray()).isTrue();
			assertThat(row.path("tags").size()).isGreaterThan(0);
			for (int i = 0; i < row.path("mustCiteDocIds").size(); i++) {
				assertThat(row.path("mustCiteDocIds").get(i).asText()).matches(DOC_ID_PLACEHOLDER);
			}
			for (int i = 0; i < row.path("mustNotInclude").size(); i++) {
				String forbidden = row.path("mustNotInclude").get(i).asText();
				assertThat(forbidden).isNotEmpty();
				// 出题约束（metrics.md 第 0 节）：mustNotInclude 不得是正确答案自然措辞的
				// 子串，否则正确答案会被规则层判死（如 "可以报销" ⊂ "不可以报销"）。
				// referenceAnswer 是正确措辞的样本，先用它机器校验。
				assertThat(referenceAnswer)
						.as("%s 的 mustNotInclude[%s] 是参考答案的子串，会把正确答案判死", id, forbidden)
						.doesNotContain(forbidden);
			}
			if (row.path("shouldRefuse").asBoolean(false)) {
				shouldRefuseCount++;
				// 应拒答的题不该有必含事实点和必引文档——那正是"不得编造"的形状。
				assertThat(row.path("mustInclude").size()).isZero();
				assertThat(row.path("mustCiteDocIds").size()).isZero();
			} else {
				// mustInclude 是事实点不是整句（计划 6.2 的设计要点）：非拒答题至少一个
				// 事实点，每个事实点不超过 20 码点——把整句参考答案（截掉一个字）塞进来
				// 也必须拦住，"严格短于全句"那种上界拦不住任何东西。
				assertThat(row.path("mustInclude").size()).isGreaterThan(0);
				for (int i = 0; i < row.path("mustInclude").size(); i++) {
					String factPoint = row.path("mustInclude").get(i).asText();
					assertThat(factPoint).isNotEmpty();
					assertThat(factPoint.codePointCount(0, factPoint.length()))
							.as("mustInclude 应是事实点（如 \"30 天\"），不是整句参考答案")
							.isLessThanOrEqualTo(20);
				}
			}
		}
		// 知识库里没有的问题，模型编答案是最危险的失败模式——应拒答题必须占一定比例。
		assertThat(shouldRefuseCount).isGreaterThan(0);
	}

	private static Set<String> fieldNames(JsonNode row) {
		Set<String> names = new HashSet<>();
		row.properties().forEach(property -> names.add(property.getKey()));
		return names;
	}

	private static List<JsonNode> readJsonl(Path file) {
		assertThat(file).as("评测集文件必须存在（批次 5 产出）").exists();
		try {
			return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
					.filter(line -> !line.isBlank())
					.map(AgentFixtures.MAPPER::readTree)
					.toList();
		} catch (IOException readFailure) {
			throw new UncheckedIOException("无法读取评测集 " + file, readFailure);
		}
	}
}
