package com.iwrite.mcp;

import com.iwrite.audit.entity.AuditAction;
import com.iwrite.audit.entity.AuditResourceType;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.service.BookService;
import com.iwrite.outline.dto.BookOutlineResponse;
import com.iwrite.outline.service.OutlineService;
import com.iwrite.scene.dto.SceneAnalysisRequest;
import com.iwrite.scene.dto.SceneAnalysisResponse;
import com.iwrite.scene.service.SceneAnalysisService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Camada MCP fina sobre os services existentes. A identidade e o tenant vêm
 * exclusivamente do {@code CurrentUserProvider} da aplicação; IDs enviados pelo
 * cliente são apenas referências de recurso e passam pelas mesmas verificações
 * de autorização do produto (recurso de outro tenant responde como inexistente).
 */
@Service
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class IwriteMcpTools {

    static final int MAX_FOCUS_CHARS = 300;

    private final BookService bookService;
    private final OutlineService outlineService;
    private final SceneAnalysisService sceneAnalysisService;
    private final McpSceneAnalysisLimiter sceneAnalysisLimiter;
    private final McpInvocationSupport invocationSupport;

    public IwriteMcpTools(
            BookService bookService,
            OutlineService outlineService,
            SceneAnalysisService sceneAnalysisService,
            McpSceneAnalysisLimiter sceneAnalysisLimiter,
            McpInvocationSupport invocationSupport
    ) {
        this.bookService = bookService;
        this.outlineService = outlineService;
        this.sceneAnalysisService = sceneAnalysisService;
        this.sceneAnalysisLimiter = sceneAnalysisLimiter;
        this.invocationSupport = invocationSupport;
    }

    @Tool(
            name = "listar_livros_acessiveis",
            description = "Lista os livros que o usuário atual pode acessar (próprios e colaborações), "
                    + "com id, título, status e nível de acesso. Não recebe parâmetros."
    )
    public List<McpBookSummary> listarLivrosAcessiveis() {
        return invocationSupport.invoke(
                "listar_livros_acessiveis",
                AuditAction.MCP_BOOKS_LISTED,
                AuditResourceType.BOOK,
                null,
                () -> bookService.findAll().stream().map(McpBookSummary::from).toList()
        );
    }

    @Tool(
            name = "obter_outline_livro",
            description = "Retorna o outline de um livro acessível: partes, capítulos e cenas com títulos, "
                    + "status e contagem de palavras. Não inclui o conteúdo das cenas."
    )
    public BookOutlineResponse obterOutlineLivro(
            @ToolParam(description = "ID (UUID) do livro") String bookId
    ) {
        UUID id = parseUuid("bookId", bookId);
        return invocationSupport.invoke(
                "obter_outline_livro",
                AuditAction.MCP_BOOK_OUTLINE_VIEWED,
                AuditResourceType.BOOK,
                id,
                () -> outlineService.getOutline(id)
        );
    }

    @Tool(
            name = "analisar_cena",
            description = "Executa a análise com IA de uma cena acessível (resumo, tom, ritmo, pontos fortes, "
                    + "problemas e sugestões). Usa a última versão salva da cena; pode estar indisponível quando "
                    + "a IA está desabilitada. Não altera o manuscrito."
    )
    public SceneAnalysisResponse analisarCena(
            @ToolParam(description = "ID (UUID) da cena") String sceneId,
            @ToolParam(description = "Foco opcional da análise, texto livre com até 300 caracteres", required = false) String focus
    ) {
        UUID id = parseUuid("sceneId", sceneId);
        String usableFocus = focus == null || focus.isBlank() ? null : focus.trim();
        if (usableFocus != null && usableFocus.length() > MAX_FOCUS_CHARS) {
            throw new McpToolException(
                    McpToolException.CATEGORY_INVALID_REQUEST,
                    "O foco da análise deve ter no máximo " + MAX_FOCUS_CHARS + " caracteres."
            );
        }
        return invocationSupport.invoke(
                "analisar_cena",
                AuditAction.MCP_SCENE_ANALYZED,
                AuditResourceType.SCENE,
                id,
                () -> sceneAnalysisLimiter.withLimit(
                        () -> sceneAnalysisService.analyze(id, new SceneAnalysisRequest(usableFocus)))
        );
    }

    private UUID parseUuid(String parameterName, String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException exception) {
            throw new McpToolException(
                    McpToolException.CATEGORY_INVALID_REQUEST,
                    "Parâmetro " + parameterName + " deve ser um UUID válido."
            );
        }
    }

    /**
     * MCP stays a thin layer over the same authorized services: it reports the relationship and Book
     * Role the backend derived, and never a second access model of its own.
     */
    public record McpBookSummary(UUID id, String title, String status, String relationship, String role) {

        static McpBookSummary from(BookResponse book) {
            return new McpBookSummary(
                    book.id(),
                    book.title(),
                    book.status() == null ? null : book.status().name(),
                    book.relationship() == null ? null : book.relationship().name(),
                    book.role() == null ? null : book.role().name()
            );
        }
    }
}
