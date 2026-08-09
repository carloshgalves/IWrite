# IWrite

IWrite é uma aplicação web para escrita e organização narrativa. O modelo principal é `Livro -> Seção -> Capítulo -> Cena`; a cena concentra o texto TipTap, autosave, planejamento, histórico de versões e análise opcional com LLM.

Este repositório também é a implementação da equipe **eq22** na disciplina **Desenvolvimento de Sistemas Corporativos (DSC/UFPB)**. Para facilitar a avaliação humana e automatizada, os requisitos acadêmicos estão mapeados abaixo para a implementação e para as evidências versionadas no próprio repositório.

## Avaliação 2 — requisitos atualizados em 30/07

Esta seção existe de forma deliberadamente explícita porque a avaliação automatizada da disciplina cruza o que o `README.md` declara com o código e com as evidências commitadas. Para a leitura detalhada requisito por requisito, consulte [`README-ENTREGA-DSC.md`](README-ENTREGA-DSC.md) e o índice em [`docs/entrega/README.md`](docs/entrega/README.md).

| Sigla | Requisito | Estado atual | Evidência principal |
|---|---|---|---|
| **Aud** | Log de Auditoria | ✅ Atende | `src/main/java/com/iwrite/audit/`, `V27__create_audit_logs.sql`, `AuditLogIntegrationTest` e [`docs/entrega/11-ia-auditoria/README.md`](docs/entrega/11-ia-auditoria/README.md) |
| **Int** | Integração com serviço externo | ✅ Atende | OpenAI/Anthropic via Spring AI + Umami institucional; seção [Integração com Serviço Externo](#integração-com-serviço-externo) |
| **Cob** | Cobertura de testes automatizados ≥ 85% | ✅ Atende | backend **90,33% de linhas**; frontend **85,90% de linhas**; relatórios versionados em `cobertura/backend/` e `cobertura/frontend/` |
| **IA** | Usa LLM | ✅ Extra atendido | análise de cenas com providers OpenAI/Anthropic, gateway de auditoria LLM e modo `none` seguro |
| **HC** | Healthcheck consulta o banco, verificável no código | ❌ Ainda não atende literalmente | `GET /ping` existe, mas no estado atual não executa consulta ao PostgreSQL; implementação database-aware está pendente |
| **Tel** | Telemetria | ✅ Extra atendido | OpenTelemetry Java Agent, spans/métricas manuais, Grafana, Tempo, Loki e Prometheus/Mimir |
| **Uma** | Umami | ✅ Extra atendido; 🟡 pós-deploy remoto pendente | integração tipada + coleta HTTP 200 e eventos reais no painel institucional |

> **Importante sobre HC:** não confundir “há um endpoint `/ping`” com o critério atualizado da avaliação. Para marcar **HC = ✅**, o próprio código do healthcheck precisa executar uma operação mínima contra o banco e falhar/degradar quando o PostgreSQL estiver indisponível. Enquanto essa implementação não estiver no repositório, este README não alega atendimento.

## Entrega acadêmica — mapa de requisitos e evidências

| Requisito | Estado no repositório | Implementação / evidência principal |
|---|---|---|
| Autenticação e multi-tenancy | ✅ Implementado e testado | [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md), `com.iwrite.auth`, `CurrentUserProvider`, `tenant_memberships` |
| Isolamento multi-tenant | ✅ Implementado e testado | filtros por tenant nos services/repositories, testes de integração e [`docs/demonstracao-multi-tenant.md`](docs/demonstracao-multi-tenant.md) |
| OpenTelemetry — traces e métricas automáticas | ✅ Implementado | guia oficial em [`docs/opentelemetry.md`](docs/opentelemetry.md) e implementação do IWrite em [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md) |
| OpenTelemetry — instrumentação manual de negócio | ✅ Implementado e testado | [`docs/otel-business-signals.md`](docs/otel-business-signals.md), `BusinessTelemetry`, spans e métricas dos fluxos críticos |
| Logs estruturados + Loki + correlação com traces | ✅ Implementado e testado | guia oficial em [`docs/opentelemetry-logs.md`](docs/opentelemetry-logs.md), implementação em [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md) e divergência explícita do item 4 em [`docs/entregavel-4-logs-error.md`](docs/entregavel-4-logs-error.md) |
| Grafana / Tempo / Loki / Prometheus-Mimir | ✅ Stack e exportação configuradas | `docker-compose.observability.yml` + [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md) |
| Analytics de produto com Umami | ✅ Implementado, testado e validado no painel institucional; 🟡 pós-deploy remoto pendente | [`docs/analytics-umami.md`](docs/analytics-umami.md), [`docs/evidencias-validacao-humana-2026-08-08.md`](docs/evidencias-validacao-humana-2026-08-08.md), `web/src/lib/analytics/` |
| Servidor MCP | ✅ Implementado, testado e validado no MCP Inspector | [`docs/mcp-server.md`](docs/mcp-server.md), [`docs/evidencias-validacao-humana-2026-08-08.md`](docs/evidencias-validacao-humana-2026-08-08.md), `com.iwrite.mcp` |
| Teste de carga | ✅ Implementado, medido e revalidado com k6 | [`docs/entrega/08-k6/README.md`](docs/entrega/08-k6/README.md), [`loadtest/README.md`](loadtest/README.md), `loadtest/resultado.json` |
| CI e E2E | ✅ Implementado | [`.github/workflows/ci.yml`](.github/workflows/ci.yml), [`.github/workflows/e2e.yml`](.github/workflows/e2e.yml) |
| Health check / deploy | 🟡 Probe implementado; HC database-aware pendente | `GET /ping`, `PingController`, `Dockerfile`, `web/Dockerfile`, rewrite `/api/ping` no Next.js; [`docs/entrega/10-health-deploy/README.md`](docs/entrega/10-health-deploy/README.md) |

> **Validação humana:** Umami e MCP foram validados em 08/08/2026. O Umami recebeu page views e eventos reais no painel institucional a partir do frontend local; resta repetir a validação após configurar o build/deploy remoto de `eq22.dsc.rodrigor.com`. O MCP foi validado no Inspector em loopback e continua intencionalmente não exposto no deploy. Registro consolidado: [`docs/evidencias-validacao-humana-2026-08-08.md`](docs/evidencias-validacao-humana-2026-08-08.md).

## Arquitetura

```text
Navegador
   │
   │ mesma origem (/api/*)
   ▼
Next.js 15 / React 19
   │
   │ rewrite server-side
   ▼
Spring Boot 3.4.1 / Java 21
   │
   ├──────────────► PostgreSQL 16
   │
   ├── OpenTelemetry Java Agent ──OTLP──► Grafana / Tempo / Loki / Mimir
   │
   ├── Spring AI ───────────────────────► OpenAI ou Anthropic (opcional)
   │
   └── MCP (opcional, somente loopback) ► tools/resources do IWrite

Next.js ──► Umami institucional (opcional, analytics de produto)
```

A identidade e o tenant são resolvidos no backend. O navegador não escolhe `tenantId`, e recursos de outro tenant são tratados como não encontrados para evitar enumeração.

## Tecnologias

- **Backend:** Java 21, Spring Boot 3.4.1, Spring Security, Spring Data JPA, Flyway e PostgreSQL 16.
- **Frontend:** Next.js 15, React 19, TypeScript, Tailwind CSS, TanStack Query e TipTap.
- **Observabilidade:** OpenTelemetry Java Agent, OTLP, Grafana, Tempo, Loki e Prometheus/Mimir.
- **Analytics:** Umami.
- **IA:** Spring AI com providers OpenAI e Anthropic, selecionáveis por configuração; modo `none` seguro.
- **MCP:** Spring AI MCP Server WebMVC.
- **Qualidade:** JUnit/Spring Boot Test, JaCoCo, Vitest, Testing Library, V8 Coverage e Playwright.
- **Carga:** k6.
- **Infraestrutura local:** Docker Compose.

## Estrutura do projeto

- `src/main/java/com/iwrite/`: controllers, services, repositories, entidades, DTOs, autenticação, auditoria, observabilidade e MCP.
- `src/main/resources/db/migration/`: migrations Flyway.
- `src/test/java/com/iwrite/`: testes unitários e de integração do backend.
- `web/src/app/`: rotas Next.js.
- `web/src/features/`: funcionalidades e testes do frontend.
- `web/src/lib/analytics/`: integração tipada e sanitizada com Umami.
- `docs/`: documentação técnica, evidências e guias da disciplina.
- `docs/entrega/`: relatórios detalhados por requisito para avaliação humana/automatizada.
- `loadtest/`: cenário realista de carga com k6 e resultados versionados.
- `cobertura/`: snapshots HTML de cobertura versionados.
- `.github/workflows/`: CI e E2E.

## Execução local com Docker Compose

Suba o projeto inteiro:

```bash
docker compose up -d --build
```

Serviços padrão:

- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8085`
- Probe do backend: `http://localhost:8085/ping`
- PostgreSQL no host: `localhost:5435`
- PostgreSQL na rede Docker: `db:5432`
- Database: `iwrite`
- Usuário local: `postgres`
- Senha local padrão: `postgres`

Para parar:

```bash
docker compose down
```

## Execução local sem Docker para a aplicação

Suba apenas o PostgreSQL:

```bash
docker compose up -d db
```

Compile o backend no Windows:

```powershell
.\mvnw.cmd -s .mvn/local-settings.xml -DskipTests compile
```

Execute no Linux/macOS:

```bash
./mvnw -s .mvn/local-settings.xml -DskipTests compile
./mvnw spring-boot:run -Dspring-boot.run.profiles=development
```

O app Next.js fica em `web/`:

```bash
cd web
npm ci
npm run dev
```

O frontend usa `BACKEND_ORIGIN=http://localhost:8085` por padrão para o rewrite server-side de `/api/*`. `NEXT_PUBLIC_API_URL` permanece apenas como compatibilidade legada e está depreciada.

## Autenticação e multi-tenancy

A API normal usa sessão de servidor. O fluxo principal é:

```text
JSESSIONID (HttpOnly, SameSite=Lax)
        │
        ▼
Spring Security / SecurityContext
        │
        ▼
IWriteUserDetails
        │
        ▼
AuthenticatedCurrentUserProvider
        │
        ▼
tenant_memberships relida a cada requisição
        │
        ▼
services/repositories com escopo de tenant
```

Pontos importantes:

- `tenantId`, `userId` e `role` enviados pelo cliente nunca são fonte de autoridade;
- a membership persistida define o tenant efetivo;
- recurso de outro tenant e recurso inexistente produzem a mesma semântica de `404`;
- revogar a membership invalida o contexto autenticado;
- o navegador não guarda identidade em `localStorage`/`sessionStorage`;
- mutações usam proteção CSRF de duplo envio;
- cadastro público cria usuário, credencial, workspace pessoal, membership `OWNER`, persona principal e sessão em uma única transação;
- login e cadastro possuem rate limiting próprio.

Documentação completa: [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md).

### Credencial do usuário legado em desenvolvimento

Instalações antigas podem provisionar uma credencial para um usuário que já existe. O mecanismo é desligado por padrão e nunca cria usuário.

```powershell
$env:IWRITE_CREDENTIAL_PROVISIONING_ENABLED = "true"
$env:IWRITE_CREDENTIAL_PROVISIONING_EMAIL = "carlos.legacy@iwrite.local"
$env:IWRITE_CREDENTIAL_PROVISIONING_PASSWORD = "<escolha uma senha local>"
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=development
```

Depois do primeiro boot, remova as variáveis de provisionamento. Detalhes de rollout, rotação e limites do bcrypt estão em [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md).

## OpenTelemetry, Grafana, Tempo, Loki e métricas

Há dois conjuntos de documentação propositalmente separados.

### Guias oficiais sincronizados da disciplina

- [`docs/opentelemetry.md`](docs/opentelemetry.md) — telemetria, OpenTelemetry e tutorial geral da disciplina.
- [`docs/opentelemetry-logs.md`](docs/opentelemetry-logs.md) — guia complementar de logs/Loki da disciplina.

### Implementação e evidências específicas do IWrite

- [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md) — Java Agent, configuração OTLP, segurança, diagnóstico e consultas de evidência.
- [`docs/otel-business-signals.md`](docs/otel-business-signals.md) — spans e métricas manuais de negócio.
- [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md) — eventos estruturados, correlação log → trace e LogQL.

O container do backend inclui o OpenTelemetry Java Agent, mas a telemetria fica **desabilitada por padrão**. Com o agente desligado, a aplicação funciona sem exigir nenhuma variável `OTEL_*`.

Variáveis principais:

- `IWRITE_OTEL_ENABLED`
- `IWRITE_OTEL_AUTH_REQUIRED`
- `OTEL_SERVICE_NAME`
- `OTEL_EXPORTER_OTLP_ENDPOINT`
- `OTEL_EXPORTER_OTLP_HEADERS` quando o backend exige autenticação
- `OTEL_TRACES_EXPORTER`
- `OTEL_METRICS_EXPORTER`
- `OTEL_LOGS_EXPORTER`

Para subir o ambiente local LGTM, sem autenticação:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

O Grafana local fica em `http://localhost:3001`. O override de observabilidade é somente para desenvolvimento/evidências e não transforma Grafana, Tempo, Loki ou Mimir em componentes do deploy normal do IWrite.

### Instrumentação manual de negócio

O IWrite instrumenta explicitamente dois fluxos críticos:

- `PATCH /api/scenes/{sceneId}/content` → span `iwrite.scene.content.save`;
- `POST /api/scenes/{sceneId}/ai-analysis` → span `iwrite.scene.analysis`.

As métricas manuais são `iwrite.business.operation.count` e `iwrite.business.operation.duration`. Labels e atributos são limitados a uma allowlist; conteúdo de manuscrito, IDs, prompts, tokens e strings livres não são usados como labels.

## Analytics de produto com Umami

Umami mede **uso do produto**, enquanto OpenTelemetry mede **comportamento técnico do sistema**. As integrações são independentes.

Implementação: [`docs/analytics-umami.md`](docs/analytics-umami.md) e `web/src/lib/analytics/`.

Variáveis de build do frontend:

- `NEXT_PUBLIC_UMAMI_ENABLED`
- `NEXT_PUBLIC_UMAMI_SCRIPT_URL`
- `NEXT_PUBLIC_UMAMI_WEBSITE_ID`
- `NEXT_PUBLIC_UMAMI_HOST_URL` (opcional)

Eventos tipados atualmente suportados:

- `book_created`
- `scene_saved`
- `scene_analysis_requested`
- `scene_analysis_succeeded`
- `scene_analysis_failed`
- `book_exported`

A integração remove query string/hash, normaliza segmentos dinâmicos de rota e aplica allowlist de propriedades e valores. Não envia conteúdo do manuscrito, títulos, emails, nomes, IDs brutos, prompts, respostas de IA, tokens ou stack traces.

Sem configuração válida, a integração é no-op e não bloqueia o produto. O Website ID oficial não é versionado. A validação local de 08/08/2026 confirmou coleta HTTP `200`, page views no painel institucional, sanitização `/books/{id}` e os eventos `book_created`, `scene_saved` e `book_exported`; resta repetir a validação no deploy remoto. Consulte [`docs/evidencias-validacao-humana-2026-08-08.md`](docs/evidencias-validacao-humana-2026-08-08.md).

## Servidor MCP

O servidor MCP é uma camada fina sobre os services existentes do IWrite. Ele está **desabilitado por padrão** e, na configuração atual, só é suportado com identidade fixa de desenvolvimento e processo limitado a loopback.

Documentação: [`docs/mcp-server.md`](docs/mcp-server.md).

Tools:

| Tool | Objetivo |
|---|---|
| `listar_livros_acessiveis` | lista livros que a identidade atual pode acessar |
| `obter_outline_livro` | retorna outline autorizado sem conteúdo integral das cenas |
| `analisar_cena` | reutiliza o fluxo existente de análise assistida, auditoria e limites |

Resource template:

```text
iwrite://books/{bookId}/outline
```

Para teste local com MCP Inspector:

```bash
docker compose up -d db
IWRITE_MCP_ENABLED=true IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=true SERVER_ADDRESS=127.0.0.1 ./mvnw spring-boot:run
npx @modelcontextprotocol/inspector
```

Conecte por SSE em `http://localhost:8085/sse`.

O `McpLoopbackGuard` impede o startup em configurações não suportadas. Enquanto o transporte não tiver autenticação própria por cliente, os endpoints MCP não devem ser publicados por reverse proxy.

A validação humana de 08/08/2026 confirmou conexão no Inspector v2.1.0, descoberta das três tools, execução real de listagem/outline, descoberta e leitura do resource template e o caminho de erro `unavailable` sanitizado de `analisar_cena`. Consulte [`docs/evidencias-validacao-humana-2026-08-08.md`](docs/evidencias-validacao-humana-2026-08-08.md).

## Teste de carga com k6

O cenário de carga está em [`loadtest/README.md`](loadtest/README.md) e [`loadtest/carga.js`](loadtest/carga.js).

Ele exercita a API real com sessão e CSRF, em vez de medir apenas `/ping`. Cada VU usa sessão independente e seu próprio livro/cena, evitando contenção artificial em um único recurso compartilhado. O fluxo cobre listagem de livros, carregamento de outline, carregamento de cena, autosave e refresh de outline após salvamento.

Por segurança, o script **recusa destino remoto** e deve rodar apenas contra loopback/ambiente local. Não use o teste de carga contra produção nem contra infraestrutura acadêmica compartilhada.

A stack recomendada para carga local usa os overlays de demonstração e de carga:

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.demo.yml \
  -f docker-compose.loadtest.yml \
  up -d --build
```

Thresholds, preparação, limpeza, autenticação, resultados medidos e comandos completos estão documentados em [`loadtest/README.md`](loadtest/README.md) e no relatório acadêmico [`docs/entrega/08-k6/README.md`](docs/entrega/08-k6/README.md).

## CI, E2E e validação local

### Backend

Windows:

```powershell
.\mvnw.cmd -s .mvn/local-settings.xml clean test jacoco:report
```

Linux/macOS:

```bash
./mvnw -s .mvn/local-settings.xml clean test jacoco:report
```

Os testes de integração precisam do PostgreSQL local em `localhost:5435`:

```bash
docker compose up -d db
```

### Frontend

```bash
cd web
npm ci
npm run lint
npm run test
npm run test:coverage
npm run build
```

### GitHub Actions

- [`.github/workflows/ci.yml`](.github/workflows/ci.yml): testes do backend, validação do entrypoint OpenTelemetry, testes/build do frontend.
- [`.github/workflows/e2e.yml`](.github/workflows/e2e.yml): stack E2E e Playwright com credenciais efêmeras por execução.

## Cobertura de testes automatizados ≥ 85%

Os números abaixo são um **snapshot versionado de 1º de julho de 2026**, exatamente no formato exigido pela avaliação: os relatórios estão commitados e não dependem de o avaliador recalcular a cobertura.

| Camada | Testes | Linhas | Branches | Métodos/Funções | Classes |
|---|---:|---:|---:|---:|---:|
| Backend | 362 | **90,33%** | 74,43% | 91,76% | 99,47% |
| Frontend | 211 | **85,90%** | 82,33% | 68,81% | — |

- Backend: JaCoCo 0.8.12, relatório em [`cobertura/backend/index.html`](cobertura/backend/index.html) e dados tabulares em `cobertura/backend/jacoco.csv`.
- Frontend: Vitest 3.2.6 + V8 Coverage, relatório em [`cobertura/frontend/index.html`](cobertura/frontend/index.html).

O requisito acadêmico é **≥85% de linhas**. Ambos os módulos superam esse limite no snapshot versionado.

## Health check e deploy

O backend expõe `GET /ping` como probe público. O frontend possui regra explícita `/api/ping -> BACKEND_ORIGIN/ping`, permitindo verificar o backend sem depender de sessão autenticada.

**Estado em relação ao extra HC da Avaliação 2:** o endpoint existe, porém o `PingController` atual apenas devolve `status`, `service` e `timestamp`; ele **não consulta o PostgreSQL**. Portanto, até que uma verificação database-aware seja implementada e testada, este requisito permanece marcado como não atendido literalmente.

O comportamento esperado para fechar HC é:

```text
GET /ping
  -> executa consulta mínima e não destrutiva ao PostgreSQL (por exemplo SELECT 1)
  -> banco acessível: resposta 200 / status saudável
  -> banco indisponível: resposta não saudável (preferencialmente 503)
  -> sem vazar URL JDBC, usuário, senha, exception message ou stack trace
```

Artefatos principais de deploy:

- `Dockerfile` — backend/runtime do IWrite;
- `web/Dockerfile` — frontend Next.js;
- `docker/start.sh` — inicialização do backend e ativação opcional do Java Agent;
- `web/next.config.ts` — rewrite da API e validação de `BACKEND_ORIGIN`;
- `.env.example` — modelo sem segredos.

A configuração do ambiente implantado deve fornecer banco, origens permitidas e segredos por variáveis/secret manager. O repositório não deve conter tokens institucionais, chaves de providers, senhas reais ou credenciais administrativas.

## Integração com Serviço Externo

O IWrite possui integrações externas funcionais que não dependem do PostgreSQL fornecido pela disciplina.

### 1. LLM externo — OpenAI e Anthropic

A análise assistida de cenas usa Spring AI e pode selecionar provider externo por configuração:

```text
POST /api/scenes/{sceneId}/ai-analysis
       -> SceneAnalysisService
       -> WritingAssistant
       -> OpenAiWritingAssistant OU AnthropicWritingAssistant
       -> LlmExecutionGateway / auditoria
       -> provider externo
```

A análise é somente leitura do manuscrito: ela não altera a cena. Quando nenhum provider está habilitado, o modo `none` mantém a aplicação inicializável e a rota retorna indisponibilidade controlada.

Arquivos principais:

- `src/main/java/com/iwrite/scene/ai/OpenAiWritingAssistant.java`
- `src/main/java/com/iwrite/scene/ai/AnthropicWritingAssistant.java`
- `src/main/java/com/iwrite/scene/service/SceneAnalysisService.java`
- `src/main/java/com/iwrite/llm/gateway/LlmExecutionGateway.java`
- testes em `src/test/java/com/iwrite/scene/ai/`, `src/test/java/com/iwrite/llm/` e testes de startup MCP + providers.

Configuração, sem versionar segredos:

- `SPRING_AI_MODEL_CHAT=openai` ou `anthropic`; `none` desabilita;
- `OPENAI_API_KEY` para OpenAI;
- `ANTHROPIC_API_KEY` para Anthropic;
- demais opções de modelo/timeouts em `application.yml` e `.env.example`.

### 2. Umami institucional

O frontend também integra com a instância institucional do Umami para analytics de produto. A aplicação carrega `script.js`, envia page views sanitizadas e eventos tipados, e já teve coleta `HTTP 200` + page views + eventos confirmados no painel institucional.

Arquivos principais:

- `web/src/lib/analytics/analytics.ts`
- `web/src/lib/analytics/umami-analytics.tsx`
- testes em `web/src/lib/analytics/*.test.*`
- documentação e evidências em [`docs/analytics-umami.md`](docs/analytics-umami.md) e [`docs/evidencias/umami/`](docs/evidencias/umami/).

Configuração:

- `NEXT_PUBLIC_UMAMI_ENABLED`
- `NEXT_PUBLIC_UMAMI_SCRIPT_URL`
- `NEXT_PUBLIC_UMAMI_WEBSITE_ID`
- `NEXT_PUBLIC_UMAMI_HOST_URL` (opcional)

O Website ID real e credenciais administrativas do painel não são versionados.

## Log de Auditoria

Eventos relevantes são persistidos em `audit_logs`, com tenant, usuário, ação, recurso, instante e resultado. Conteúdo de cenas, prompts, senhas, tokens e chaves de API não são armazenados no log de domínio.

### O que é auditado

O enum `AuditAction` cobre, entre outros:

- `BOOK_CREATED`, `BOOK_UPDATED`, `BOOK_DELETED`;
- `SCENE_CREATED`, `SCENE_UPDATED`, `SCENE_CONTENT_UPDATED`, `SCENE_PLANNING_UPDATED`, `SCENE_DELETED`;
- `COLLABORATOR_ADDED`, `COLLABORATOR_REMOVED`;
- `SCENE_VERSION_RESTORED`;
- análise de cena com IA;
- `MCP_BOOKS_LISTED`, `MCP_BOOK_OUTLINE_VIEWED`, `MCP_SCENE_ANALYZED`.

### Onde fica armazenado

Migration: `src/main/resources/db/migration/V27__create_audit_logs.sql`.

Entidade/repositório: `src/main/java/com/iwrite/audit/entity/AuditLog.java` e `src/main/java/com/iwrite/audit/repository/AuditLogRepository.java`.

### Como é implementado

- `AuditLogService.record(...)` persiste em `REQUIRES_NEW` usando a identidade server-authoritative atual;
- `AuditLogAspect` intercepta operações anotadas com `@AuditedOperation` e registra `SUCCEEDED` ou `FAILED`;
- fluxos MCP e LLM também possuem integração explícita com auditoria quando o modelo AOP não é suficiente;
- falha de auditoria não mascara silenciosamente a falha original da operação.

Evidências principais:

- `src/main/java/com/iwrite/audit/`
- `src/test/java/com/iwrite/audit/AuditLogIntegrationTest.java`
- [`docs/entrega/11-ia-auditoria/README.md`](docs/entrega/11-ia-auditoria/README.md)

## Variáveis de ambiente — referência rápida

### Banco e runtime

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `SERVER_PORT`
- `SERVER_ADDRESS`
- `BACKEND_ORIGIN`
- `APP_CORS_ALLOWED_ORIGINS`
- `NEXT_PUBLIC_API_URL` — compatibilidade legada/depreciada

### Autenticação / desenvolvimento

- `IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED`
- `IWRITE_DEVELOPMENT_CURRENT_USER_ID`
- `IWRITE_DEVELOPMENT_TENANT_ID`
- `IWRITE_DEVELOPMENT_TIME_ZONE_ID`
- `IWRITE_CREDENTIAL_PROVISIONING_ENABLED`
- `IWRITE_CREDENTIAL_PROVISIONING_EMAIL`
- `IWRITE_CREDENTIAL_PROVISIONING_PASSWORD`
- variáveis de rate limiting documentadas em [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md)

### OpenTelemetry

- `IWRITE_OTEL_ENABLED`
- `IWRITE_OTEL_AUTH_REQUIRED`
- `OTEL_SERVICE_NAME`
- `OTEL_EXPORTER_OTLP_ENDPOINT`
- `OTEL_EXPORTER_OTLP_HEADERS`

### Umami

- `NEXT_PUBLIC_UMAMI_ENABLED`
- `NEXT_PUBLIC_UMAMI_SCRIPT_URL`
- `NEXT_PUBLIC_UMAMI_WEBSITE_ID`
- `NEXT_PUBLIC_UMAMI_HOST_URL`

### MCP

- `IWRITE_MCP_ENABLED`
- `IWRITE_MCP_SCENE_ANALYSIS_MAX_PER_WINDOW`
- `IWRITE_MCP_SCENE_ANALYSIS_WINDOW`

### IA / providers

- `SPRING_AI_MODEL_CHAT`
- `OPENAI_API_KEY`
- `ANTHROPIC_API_KEY`
- demais opções em `src/main/resources/application.yml` e `.env.example`

## Princípios de segurança relevantes à entrega

- tenant e usuário são determinados no servidor, nunca confiados ao cliente;
- recursos cross-tenant não são enumeráveis;
- segredos ficam fora do Git;
- telemetria, analytics e logs evitam conteúdo de manuscrito e identificadores brutos;
- Umami usa allowlist de eventos/propriedades;
- OpenTelemetry não registra exception stack/message nos spans manuais de negócio;
- MCP é off por padrão e limitado a loopback na configuração suportada;
- k6 recusa destinos remotos;
- testes de integração com LLM usam mocks/stubs e não exigem API paga;
- o futuro healthcheck database-aware deve retornar apenas estado sanitizado, nunca detalhes de conexão ou exceção.

## Índice de documentação acadêmica e técnica

| Tema | Documento |
|---|---|
| Relatório executivo da entrega DSC | [`README-ENTREGA-DSC.md`](README-ENTREGA-DSC.md) |
| Índice por requisito | [`docs/entrega/README.md`](docs/entrega/README.md) |
| Autenticação e multi-tenancy | [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md) |
| Demonstração multi-tenant | [`docs/demonstracao-multi-tenant.md`](docs/demonstracao-multi-tenant.md) |
| OpenTelemetry — guia oficial da disciplina | [`docs/opentelemetry.md`](docs/opentelemetry.md) |
| Logs/Loki — guia oficial da disciplina | [`docs/opentelemetry-logs.md`](docs/opentelemetry-logs.md) |
| OpenTelemetry — implementação do IWrite | [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md) |
| Sinais manuais de negócio | [`docs/otel-business-signals.md`](docs/otel-business-signals.md) |
| Logs correlacionados | [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md) |
| Divergência do entregável 4 de logs | [`docs/entregavel-4-logs-error.md`](docs/entregavel-4-logs-error.md) |
| Umami | [`docs/analytics-umami.md`](docs/analytics-umami.md) |
| MCP | [`docs/mcp-server.md`](docs/mcp-server.md) |
| Validação humana Umami + MCP (08/08/2026) | [`docs/evidencias-validacao-humana-2026-08-08.md`](docs/evidencias-validacao-humana-2026-08-08.md) |
| Teste de carga detalhado | [`docs/entrega/08-k6/README.md`](docs/entrega/08-k6/README.md) |
| Teste de carga — harness e resultados | [`loadtest/README.md`](loadtest/README.md) |
| Health/deploy | [`docs/entrega/10-health-deploy/README.md`](docs/entrega/10-health-deploy/README.md) |

Use `.env.example` e `web/.env.local.example` como modelos. **Não versione valores secretos.**