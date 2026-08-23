# Servidor MCP do IWrite

## Arquitetura

O MCP é uma camada fina dentro do backend Spring Boot (`com.iwrite.mcp`). As tools reutilizam os services de domínio existentes, portanto autorização por livro/cena, isolamento por tenant, auditoria e tratamento de IA continuam centralizados.

```text
Cliente MCP
  |-- SSE: GET /sse
  |-- POST /mcp/message
  v
Backend IWrite -> com.iwrite.mcp -> services existentes -> PostgreSQL
                                      |-> AuditLogService
                                      |-> logs estruturados
                                      `-> LlmExecutionGateway
```

## Exposição suportada hoje

- MCP permanece **desabilitado por padrão** (`IWRITE_MCP_ENABLED=false`);
- o transporte atual não possui autenticação própria por cliente MCP;
- por isso, quando habilitado, a configuração suportada usa a identidade fixa de desenvolvimento e exige `server.address` em loopback;
- `McpLoopbackGuard` recusa startup em combinação insegura;
- `/sse` e `/mcp/message` não devem ser publicados por reverse proxy enquanto não houver autenticação própria do transporte.

A autenticação real da API HTTP não transforma automaticamente o transporte MCP em um protocolo autenticado. Evoluir isso é trabalho futuro e deve preservar a mesma autorização de domínio usada pelas tools.

## Tools

| Tool | Parâmetros | Retorno |
|---|---|---|
| `listar_livros_acessiveis` | — | metadados dos livros acessíveis |
| `obter_outline_livro` | `bookId` | estrutura do livro sem conteúdo integral |
| `analisar_cena` | `sceneId`, `focus` opcional | análise de cena via fluxo LLM existente |

`analisar_cena` reutiliza autorização, limite de entrada, gateway de auditoria LLM e tratamento de indisponibilidade. Nenhuma tool altera o manuscrito.

## Resource

`iwrite://books/{bookId}/outline` retorna metadados autorizados da estrutura do livro sem conteúdo integral de cenas.

## Autorização e isolamento

- `tenantId` e `userId` nunca são parâmetros das tools;
- IDs enviados pelo cliente são apenas referências de recurso;
- acesso passa pelos services existentes;
- recurso inexistente, de outro tenant ou revogado usa semântica não enumerável;
- argumentos livres e respostas não entram em logs estruturados.

## Limite de análise MCP

`McpSceneAnalysisLimiter` protege custo e concorrência por identidade:

- uma análise concorrente por identidade;
- janela configurável por `IWRITE_MCP_SCENE_ANALYSIS_MAX_PER_WINDOW` e `IWRITE_MCP_SCENE_ANALYSIS_WINDOW`;
- excesso resulta em categoria sanitizada `rate_limited`;
- falhas também contam para a janela.

O limiter é local à instância atual; eventual escala horizontal deve considerar store compartilhado junto da evolução geral de rate limiting.

## Erros

As respostas de erro usam categorias enumeradas e sanitizadas:

- `not_found`;
- `invalid_request`;
- `unavailable`;
- `rate_limited`;
- `internal`.

Stack traces, nomes de classes internas, conteúdo de manuscrito, prompts e credentials não são enviados ao cliente.

## Auditoria e telemetria

Cada invocação gera auditoria de domínio e log estruturado com metadados controlados. `analisar_cena` também passa pela auditoria especializada do `LlmExecutionGateway`.

Com OpenTelemetry habilitado, logs e traces seguem a configuração documentada em `docs/opentelemetry-implementation.md` e `docs/otel-correlated-logs.md`.

## Como validar localmente

```bash
docker compose up -d db
IWRITE_MCP_ENABLED=true \
IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=true \
SERVER_ADDRESS=127.0.0.1 \
./mvnw spring-boot:run
```

Depois:

```bash
npx @modelcontextprotocol/inspector
```

Conecte via SSE em `http://localhost:8085/sse`.

A validação deve confirmar:

- descoberta das três tools;
- descoberta do resource template;
- execução autorizada de `listar_livros_acessiveis`;
- leitura autorizada do outline;
- comportamento sanitizado de análise indisponível quando IA está desabilitada;
- inexistência de exposição remota do transporte.

## Testes automatizados relevantes

O repositório cobre:

- isolamento multi-tenant das tools/resources;
- descoberta e execução pelo transporte SSE;
- guard de loopback/startup;
- política de segurança dos endpoints MCP;
- rate limiting de `analisar_cena`;
- auditoria e erros sanitizados.

## Limitações atuais

- sem operações destrutivas/escrita via MCP;
- sem autenticação individual por cliente MCP;
- execução suportada somente em loopback com identidade de desenvolvimento;
- catálogo propositalmente pequeno: 3 tools + 1 resource.

Essas limitações são deliberadas para manter a superfície segura enquanto o transporte não possui autenticação própria.

## Histórico acadêmico

Em 08/08/2026 o MCP foi validado manualmente com o MCP Inspector, em loopback, junto das evidências de Umami da disciplina. Foram confirmadas descoberta, execução das tools, leitura do resource e erro sanitizado da análise com IA desabilitada.

A antiga checklist também previa uma validação posterior do **Umami** no deploy remoto acadêmico `eq22.dsc.rodrigor.com`. Essa etapa deixou de ser aplicável quando a disciplina e aquele ambiente foram encerrados; **não é uma pendência do MCP nem do produto atual**.

Os registros originais permanecem em `docs/evidencias-validacao-humana-2026-08-08.md` e `docs/entrega/` como evidência histórica.
