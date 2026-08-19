package yangsirly.rag_agent.agent.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * fixtures/agent/ 数据集的读取工具（批次 4 的测试基建，见 agent-test-plan.md 5.2 节）。
 *
 * <p>fixture 目录通过测试类路径定位，不依赖工作目录——Maven 会把 src/test/resources
 * 复制到 target/test-classes，从任何目录启动测试都能找到。文件列表按文件名排序，
 * 保证 @ParameterizedTest 的用例顺序在任何文件系统上都稳定。
 */
public final class AgentFixtures {

	public static final ObjectMapper MAPPER = new JsonMapper();

	private AgentFixtures() {
	}

	/** fixtures/agent/ 根目录。 */
	public static Path root() {
		URL url = AgentFixtures.class.getClassLoader().getResource("fixtures/agent");
		if (url == null) {
			throw new IllegalStateException("测试类路径上找不到 fixtures/agent 目录");
		}
		try {
			return Path.of(url.toURI());
		} catch (URISyntaxException invalidUrl) {
			throw new IllegalStateException("无法解析 fixtures/agent 目录位置：" + url, invalidUrl);
		}
	}

	/** 列出子目录下指定后缀的文件（不含更深层子目录），按文件名排序。 */
	public static List<Path> filesIn(String subdir, String suffix) {
		Path dir = root().resolve(subdir);
		try (Stream<Path> entries = Files.list(dir)) {
			return entries
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(suffix))
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.toList();
		} catch (IOException listFailure) {
			throw new UncheckedIOException("无法列出 fixture 目录 " + dir, listFailure);
		}
	}

	public static JsonNode json(Path file) {
		return MAPPER.readTree(text(file));
	}

	public static String text(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException readFailure) {
			throw new UncheckedIOException("无法读取 fixture 文件 " + file, readFailure);
		}
	}

	/** 从 expectations/&lt;expectationsFile&gt; 的 fixtures[] 里按 file 字段取出一条期望记录。 */
	public static JsonNode expectationFor(String expectationsFile, String fixtureFileName) {
		JsonNode expectations = json(root().resolve("expectations").resolve(expectationsFile));
		JsonNode fixtures = expectations.path("fixtures");
		for (int i = 0; i < fixtures.size(); i++) {
			if (fixtureFileName.equals(fixtures.get(i).path("file").asText(null))) {
				return fixtures.get(i);
			}
		}
		throw new IllegalStateException(
				"expectations/" + expectationsFile + " 里没有 " + fixtureFileName + " 的期望记录——"
						+ "fixture 与期望文件必须一一对应（fixtures/agent/README.md 第 4 节）");
	}
}
