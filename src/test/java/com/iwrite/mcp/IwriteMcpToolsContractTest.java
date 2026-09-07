package com.iwrite.mcp;

import com.iwrite.book.authorization.BookCapability;
import com.iwrite.book.authorization.BookRelationship;
import com.iwrite.book.dto.BookResponse;
import com.iwrite.book.entity.BookRole;
import com.iwrite.book.entity.BookStatus;
import com.iwrite.mcp.IwriteMcpTools.McpBookSummary;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the {@code listar_livros_acessiveis} tool contract aligned with {@link McpBookSummary}.
 * MCP clients and models consume the {@link Tool} description as the contract, so a field the record
 * exposes but the description does not name would be an invisible divergence; this pins both to the
 * derived Book access model ({@code relationship}, {@code role}, {@code capabilities},
 * {@code contextualCapabilities}) and away from the removed "nível de acesso" concept.
 */
class IwriteMcpToolsContractTest {

    private static final String TOOL_DESCRIPTION = listarLivrosAcessiveisTool().description();

    @Test
    void toolDescriptionNamesEveryFieldTheSummaryExposes() {
        for (RecordComponent component : McpBookSummary.class.getRecordComponents()) {
            assertThat(TOOL_DESCRIPTION)
                    .as("tool description must document the '%s' field", component.getName())
                    .contains("`" + component.getName() + "`");
        }
    }

    @Test
    void toolDescriptionDropsTheRemovedAccessLevelConcept() {
        assertThat(TOOL_DESCRIPTION).doesNotContain("nível de acesso");
    }

    @Test
    void summaryCarriesTheDerivedCapabilitySetsAndNullRoleForOwner() {
        BookResponse owner = bookResponse(
                BookRelationship.OWNER,
                null,
                List.of(BookCapability.READ_MANUSCRIPT, BookCapability.DELETE_BOOK),
                List.of(BookCapability.EDIT_AUTHORED_CONTRIBUTION)
        );

        McpBookSummary summary = McpBookSummary.from(owner);

        assertThat(summary.relationship()).isEqualTo("OWNER");
        assertThat(summary.role()).isNull();
        assertThat(summary.capabilities()).containsExactly("READ_MANUSCRIPT", "DELETE_BOOK");
        assertThat(summary.contextualCapabilities()).containsExactly("EDIT_AUTHORED_CONTRIBUTION");
    }

    @Test
    void summaryReportsTheCollaboratorRoleAndEmptyCapabilitySetsSafely() {
        BookResponse collaborator = bookResponse(
                BookRelationship.COLLABORATOR,
                BookRole.LEGACY_COLLABORATOR,
                List.of(),
                List.of()
        );

        McpBookSummary summary = McpBookSummary.from(collaborator);

        assertThat(summary.relationship()).isEqualTo("COLLABORATOR");
        assertThat(summary.role()).isEqualTo("LEGACY_COLLABORATOR");
        assertThat(summary.capabilities()).isEmpty();
        assertThat(summary.contextualCapabilities()).isEmpty();
    }

    private static BookResponse bookResponse(
            BookRelationship relationship,
            BookRole role,
            List<BookCapability> capabilities,
            List<BookCapability> contextualCapabilities
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        return new BookResponse(
                UUID.randomUUID(),
                "Livro",
                null,
                null,
                BookStatus.WRITING,
                null,
                relationship,
                role,
                capabilities,
                contextualCapabilities,
                now,
                now
        );
    }

    private static Tool listarLivrosAcessiveisTool() {
        try {
            return IwriteMcpTools.class
                    .getMethod("listarLivrosAcessiveis")
                    .getAnnotation(Tool.class);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
