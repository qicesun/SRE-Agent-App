package gitlab;

import com.qicesun.sreagent.tools.gitlab.GitLabTool;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabToolTest {

    @Test
    void listRecentCommits_returnsFormattedResultsAndSendsToken() throws Exception {
        String response = "[" +
                "{\"id\":\"deadbeef\",\"author_name\":\"Bob\",\"message\":\"Add   logs\\nplease\"}," +
                "{\"short_id\":\"abc1234\",\"author_name\":\"Alice\",\"title\":\"Fix bug\"}" +
                "]";
        String path = "/api/v4/projects/group/project/repository/commits";
        AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
        try (TestServer server = startServer(path, 200, response, recorded)) {
            GitLabTool tool = new GitLabTool(server.baseUrl(), "token-123");

            String result = tool.listRecentCommits("group/project", 2);

            assertThat(result).contains("Commit Hash | Author | Message");
            assertThat(result).contains("deadbeef | Bob | Add logs please");
            assertThat(result).contains("abc1234 | Alice | Fix bug");
            RecordedRequest request = recorded.get();
            assertThat(request).isNotNull();
            assertThat(request.path).isEqualTo(path);
            assertThat(request.rawPath).isEqualTo("/api/v4/projects/group%2Fproject/repository/commits");
            assertThat(request.query).contains("per_page=2");
            assertThat(request.token).isEqualTo("token-123");
        }
    }

    @Test
    void listRecentCommits_defaultsToFiveWhenLimitInvalid() throws Exception {
        String path = "/api/v4/projects/group/project/repository/commits";
        AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
        try (TestServer server = startServer(path, 200, "[]", recorded)) {
            GitLabTool tool = new GitLabTool(server.baseUrl(), "token-123");

            tool.listRecentCommits("group/project", 0);

            RecordedRequest request = recorded.get();
            assertThat(request).isNotNull();
            assertThat(request.query).contains("per_page=5");
        }
    }

    @Test
    void listRecentCommits_returnsNoCommitsMessage() throws Exception {
        String path = "/api/v4/projects/group/project/repository/commits";
        try (TestServer server = startServer(path, 200, "[]", new AtomicReference<>())) {
            GitLabTool tool = new GitLabTool(server.baseUrl(), "token-123");

            String result = tool.listRecentCommits("group/project", 3);

            assertThat(result).isEqualTo("No commits found for project: group/project.");
        }
    }

    @Test
    void listRecentCommits_returnsErrorOnHttpFailure() throws Exception {
        String path = "/api/v4/projects/group/project/repository/commits";
        try (TestServer server = startServer(path, 500, "boom", new AtomicReference<>())) {
            GitLabTool tool = new GitLabTool(server.baseUrl(), "token-123");

            String result = tool.listRecentCommits("group/project", 1);

            assertThat(result).isEqualTo("Error: boom");
        }
    }

    @Test
    void listRecentCommits_returnsErrorOnInvalidJson() throws Exception {
        String path = "/api/v4/projects/group/project/repository/commits";
        try (TestServer server = startServer(path, 200, "not-json", new AtomicReference<>())) {
            GitLabTool tool = new GitLabTool(server.baseUrl(), "token-123");

            String result = tool.listRecentCommits("group/project", 1);

            assertThat(result).startsWith("Error:");
        }
    }

    @Test
    void listRecentCommits_requiresToken() {
        GitLabTool tool = new GitLabTool("http://localhost", " ");

        String result = tool.listRecentCommits("group/project", 1);

        assertThat(result).isEqualTo("GitLab token is not configured.");
    }

    private static TestServer startServer(
            String path, int statusCode, String responseBody, AtomicReference<RecordedRequest> recorded)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext(path, exchange -> {
            RecordedRequest request = new RecordedRequest();
            request.path = exchange.getRequestURI().getPath();
            request.rawPath = exchange.getRequestURI().getRawPath();
            request.query = exchange.getRequestURI().getQuery();
            request.token = exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN");
            recorded.set(request);
            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(responseBytes);
            }
        });
        server.start();
        return new TestServer(server, executor);
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;

        private TestServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        private String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static final class RecordedRequest {
        private String path;
        private String rawPath;
        private String query;
        private String token;
    }
}
