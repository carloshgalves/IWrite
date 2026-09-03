package com.iwrite.mcp;

import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditResult;
import com.iwrite.audit.repository.AuditLogRepository;
import com.iwrite.book.dto.BookRequest;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.service.BookService;
import com.iwrite.chapter.dto.ChapterRequest;
import com.iwrite.chapter.dto.ChapterResponse;
import com.iwrite.chapter.service.ChapterService;
import com.iwrite.scene.dto.SceneRequest;
import com.iwrite.scene.dto.SceneResponse;
import com.iwrite.scene.entity.SceneStatus;
import com.iwrite.scene.service.SceneService;
import com.iwrite.section.dto.BookSectionRequest;
import com.iwrite.section.dto.BookSectionResponse;
import com.iwrite.section.entity.SectionType;
import com.iwrite.section.service.BookSectionService;
import com.iwrite.support.TestDatabaseInitializer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova de descoberta com a configuração local suportada: identidade fixa de
 * desenvolvimento + servidor limitado a loopback. Um cliente MCP real (SSE
 * sobre HTTP) descobre as tools e o resource template e executa fluxos
 * autorizados de ponta a ponta — provando também que o guard de loopback
 * permite esse arranjo.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.mcp.server.enabled=true",
                "server.address=127.0.0.1",
                "iwrite.current-user.development.enabled=true",
                "server.shutdown=immediate"
        }
)
class McpServerDiscoveryIntegrationTest {

    @DynamicPropertySource
    static void testDatasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabaseInitializer.prepareDatabase();
        registry.add("spring.datasource.url", TestDatabaseInitializer::testDbUrl);
        registry.add("spring.datasource.username", TestDatabaseInitializer::username);
        registry.add("spring.datasource.password", TestDatabaseInitializer::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private BookService bookService;

    @Autowired
    private BookSectionService sectionService;

    @Autowired
    private ChapterService chapterService;

    @Autowired
    private SceneService sceneService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void analiseIndisponivelRetornaErroUnavailableSanitizadoEAuditaFalha() {
        BookResponse book = bookService.create(new BookRequest("Livro MCP Análise", null, null, null, null));
        BookSectionResponse section = sectionService.create(book.id(), new BookSectionRequest("Parte 1", SectionType.PART, 0));
        ChapterResponse chapter = chapterService.create(section.id(), new ChapterRequest("Capítulo 1", null, 0));
        SceneResponse scene = sceneService.create(chapter.id(), new SceneRequest(
                "Cena 1", null, SceneStatus.DRAFT, 0, "{\"type\":\"doc\"}", "texto suficiente para analisar"));

        var transport = HttpClientSseClientTransport.builder("http://localhost:" + port).build();
        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build()) {
            client.initialize();

            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    "analisar_cena", Map.of("sceneId", scene.id().toString())));

            assertThat(result.isError()).isTrue();
            assertThat(textOf(result))
                    .contains("unavailable")
                    .doesNotContain("Exception")
                    .doesNotContain("com.iwrite")
                    .doesNotContain("texto suficiente para analisar");
        }

        assertThat(auditLogRepository.findAll())
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo(AuditAction.MCP_SCENE_ANALYZED);
                    assertThat(log.getResult()).isEqualTo(AuditResult.FAILED);
                    assertThat(log.getResourceId()).isEqualTo(scene.id());
                });
    }

    @Test
    void clienteMcpDescobreEExecutaToolsEResource() {
        BookResponse book = bookService.create(new BookRequest(
                "Livro MCP Discovery", null, null, null, null
        ));

        var transport = HttpClientSseClientTransport.builder("http://localhost:" + port).build();
        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build()) {

            McpSchema.InitializeResult initialization = client.initialize();
            assertThat(initialization.serverInfo().name()).isEqualTo("iwrite-mcp");

            McpSchema.ListToolsResult tools = client.listTools();
            assertThat(tools.tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("listar_livros_acessiveis", "obter_outline_livro", "analisar_cena");

            assertThat(tools.tools())
                    .filteredOn(tool -> tool.name().equals("listar_livros_acessiveis"))
                    .singleElement()
                    .satisfies(tool -> assertThat(tool.description())
                            .contains("`relationship`", "`role`", "`capabilities`", "`contextualCapabilities`")
                            .doesNotContain("nível de acesso"));

            McpSchema.ListResourceTemplatesResult templates = client.listResourceTemplates();
            assertThat(templates.resourceTemplates())
                    .extracting(McpSchema.ResourceTemplate::uriTemplate)
                    .contains(IwriteMcpServerConfiguration.OUTLINE_URI_TEMPLATE);

            McpSchema.CallToolResult listResult = client.callTool(
                    new McpSchema.CallToolRequest("listar_livros_acessiveis", Map.of()));
            assertThat(listResult.isError()).isFalse();
            assertThat(textOf(listResult))
                    .contains("Livro MCP Discovery")
                    .contains("\"relationship\"", "\"role\"", "\"capabilities\"", "\"contextualCapabilities\"");

            McpSchema.ReadResourceResult outline = client.readResource(
                    new McpSchema.ReadResourceRequest("iwrite://books/" + book.id() + "/outline"));
            assertThat(outline.contents()).hasSize(1);
            assertThat(((McpSchema.TextResourceContents) outline.contents().get(0)).text())
                    .contains("Livro MCP Discovery");

            McpSchema.CallToolResult notFound = client.callTool(new McpSchema.CallToolRequest(
                    "obter_outline_livro", Map.of("bookId", UUID.randomUUID().toString())));
            assertThat(notFound.isError()).isTrue();
            assertThat(textOf(notFound))
                    .contains("not_found")
                    .doesNotContain("Exception")
                    .doesNotContain("com.iwrite");
        }
    }

    private String textOf(McpSchema.CallToolResult result) {
        List<McpSchema.Content> content = result.content();
        return content.stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(item -> ((McpSchema.TextContent) item).text())
                .reduce("", String::concat);
    }
}
