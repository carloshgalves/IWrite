# IWrite

IWrite é uma aplicação web para escrita e organização narrativa. O modelo principal é `Livro -> Seção -> Capítulo -> Cena`; a cena concentra texto TipTap, autosave, planejamento, histórico de versões e análise opcional com LLM.

Este repositório também é a implementação da equipe **EQ22** na disciplina **Desenvolvimento de Sistemas Corporativos (DSC/UFPB)**.

> **Avaliação humana ou automatizada:** comece em [`README-ENTREGA-DSC.md`](README-ENTREGA-DSC.md) e depois use [`docs/entrega/README.md`](docs/entrega/README.md). Cada requisito importante possui documentação própria com arquitetura, implementação, testes, evidências, reprodução e limitações.

## Vídeo da Avaliação 2

[▶ Assistir à demonstração completa do IWrite](https://youtu.be/aGw0S_mtT60)

---

## Avaliação 2 — requisitos atualizados em 30/07

| Sigla | Requisito | Estado no IWrite | Evidência verificável |
|---|---|---|---|
| **Aud** | Log de Auditoria | ✅ **Atende** | `src/main/java/com/iwrite/audit/`, `V27__create_audit_logs.sql`, `AuditLogIntegrationTest` |
| **Int** | Integração com Serviço Externo | ✅ **Atende** | Spring AI OpenAI/Anthropic + Umami institucional |
| **Cob** | Cobertura automatizada ≥ 85% | ✅ **Atende na revisão atual** | frontend **87,16% de linhas** na CI #253 com threshold `lines: 85`; backend **92,01% de linhas** na validação pós-HC; [`docs/entrega/13-cobertura/README.md`](docs/entrega/13-cobertura/README.md) |
| **IA** | Usa LLM | ✅ **Extra atendido** | análise de cenas com OpenAI/Anthropic + auditoria LLM + modo `none` seguro |
| **HC** | Healthcheck consulta o banco, lido do código | ✅ **Extra atendido** | `DatabaseHealthService -> JdbcTemplate -> SELECT 1`; 200/up e 503/down; [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md) |
| **Tel** | Telemetria | ✅ **Extra atendido** | OpenTelemetry Java Agent, spans/métricas manuais, Grafana, Tempo, Loki e Prometheus/Mimir |
| **Uma** | Umami | ✅ **Extra atendido**; 🟡 repetição pós-deploy remoto pendente | coleta HTTP 200, pageviews, rota sanitizada e eventos no painel institucional |

### Resultado resumido

```text
Aud ✅
Int ✅
Cob ✅
IA  ✅
HC  ✅
Tel ✅
Uma ✅
```

---

## Cobertura atual — não depende mais apenas do snapshot antigo

A primeira versão deste README usava o snapshot versionado de 01/07/2026 como principal prova de cobertura. O Codex apontou corretamente que isso não demonstrava a cobertura do frontend atual, pois houve código novo depois daquele snapshot.

A PR #159 corrigiu a lacuna de forma verificável:

```text
web/package.json
  -> npm test = vitest run --coverage

web/vitest.config.mjs
  -> include src/**/*.{ts,tsx}
  -> threshold lines = 85

.github/workflows/ci.yml
  -> executa npm test
```

Na **CI #253**, sobre a revisão atual:

```text
41 arquivos de teste passaram
375 testes passaram
Statements: 87,16%
Branches:   83,87%
Functions:  71,90%
Lines:      87,16%
```

O critério acadêmico é operacionalizado como **linhas ≥ 85%**. Como o Vitest possui `thresholds.lines = 85`, a CI só permanece verde se o frontend satisfizer o mínimo.

O backend foi revalidado após a implementação do HC com:

```text
841 testes
0 falhas
0 erros
92,01% de linhas
com.iwrite.health.*: 100% de linhas
```

O snapshot histórico continua versionado em `cobertura/`, mas a afirmação `Cob ✅` agora é sustentada por medição atual e gate automatizado.

**Relatório específico:** [`docs/entrega/13-cobertura/README.md`](docs/entrega/13-cobertura/README.md).

---

## HC — healthcheck consulta PostgreSQL de verdade

O critério do professor é explícito: **“healthcheck consulta o banco, lido do código”**.

Fluxo implementado:

```text
GET /ping
  -> PingController
  -> DatabaseHealthService
  -> HEALTH_QUERY = "SELECT 1"
  -> JdbcTemplate.queryForObject(HEALTH_QUERY, Integer.class)
  -> PostgreSQL
```

Banco disponível:

```text
HTTP 200
status = ok
database = up
```

Banco indisponível:

```text
HTTP 503
status = unavailable
database = down
```

O response não expõe URL JDBC, hostname, porta, credenciais, mensagem da exceção ou stack trace.

O probe usa datasource/pool Hikari dedicado, separado do pool principal, com limites curtos de aquisição, validação, conexão, socket e query. O `Dockerfile` principal verifica `/api/ping`, então o health do container atravessa:

```text
Docker HEALTHCHECK
 -> Next.js
 -> /api/ping
 -> Spring Boot /ping
 -> SELECT 1
 -> PostgreSQL
```

**Relatório específico:** [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md).

---

## Relatórios detalhados por requisito

| # | Área | Estado | README específico |
|---|---|---|---|
| 01 | Autenticação e multi-tenancy | ✅ | [`docs/entrega/01-auth-multitenancy/README.md`](docs/entrega/01-auth-multitenancy/README.md) |
| 02 | OpenTelemetry automático | ✅ | [`docs/entrega/02-opentelemetry-auto/README.md`](docs/entrega/02-opentelemetry-auto/README.md) |
| 03 | Telemetria manual — spans e métricas de negócio | ✅ | [`docs/entrega/03-telemetria-negocio/README.md`](docs/entrega/03-telemetria-negocio/README.md) |
| 04 | Grafana / Tempo / Loki / Prometheus-Mimir | ✅ | [`docs/entrega/04-grafana-stack/README.md`](docs/entrega/04-grafana-stack/README.md) |
| 05 | Logs estruturados + correlação log/trace | ✅ com divergência literal documentada no item 4 | [`docs/entrega/05-logs-correlacionados/README.md`](docs/entrega/05-logs-correlacionados/README.md) |
| 06 | Umami | ✅ | [`docs/entrega/06-umami/README.md`](docs/entrega/06-umami/README.md) |
| 07 | MCP Server | ✅ | [`docs/entrega/07-mcp/README.md`](docs/entrega/07-mcp/README.md) |
| 08 | k6 / performance | ✅ | [`docs/entrega/08-k6/README.md`](docs/entrega/08-k6/README.md) |
| 09 | CI / E2E | ✅ | [`docs/entrega/09-ci-e2e/README.md`](docs/entrega/09-ci-e2e/README.md) |
| 10 | Health / containerização / deploy | ✅ | [`docs/entrega/10-health-deploy/README.md`](docs/entrega/10-health-deploy/README.md) |
| 11 | IA / providers / auditoria | ✅ | [`docs/entrega/11-ia-auditoria/README.md`](docs/entrega/11-ia-auditoria/README.md) |
| 12 | **HC — healthcheck database-aware** | ✅ | [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md) |
| 13 | **Cob — cobertura ≥85%** | ✅ | [`docs/entrega/13-cobertura/README.md`](docs/entrega/13-cobertura/README.md) |

---

## Arquitetura

```text
Navegador
   |
   | mesma origem (/api/*)
   v
Next.js 15 / React 19
   |
   | rewrite server-side
   v
Spring Boot 3.4.1 / Java 21
   |
   +----> PostgreSQL 16
   |
   +----> OpenTelemetry Java Agent --OTLP--> Grafana / Tempo / Loki / Mimir
   |
   +----> Spring AI -----------------------> OpenAI ou Anthropic (opcional)
   |
   +----> MCP -----------------------------> loopback na configuração suportada

Next.js ----> Umami institucional (opcional)
```

A identidade e o tenant são resolvidos no backend. O cliente não escolhe `tenantId`, e recursos cross-tenant recebem semântica equivalente a recurso inexistente para reduzir enumeração.

---

## Tecnologias

- **Backend:** Java 21, Spring Boot 3.4.1, Spring Security, Spring Data JPA, Flyway, PostgreSQL 16.
- **Frontend:** Next.js 15, React 19, TypeScript, Tailwind CSS, TanStack Query, TipTap.
- **Observabilidade:** OpenTelemetry Java Agent, OTLP, Grafana, Tempo, Loki, Prometheus/Mimir.
- **Analytics:** Umami.
- **IA:** Spring AI, OpenAI e Anthropic opcionais.
- **MCP:** Spring AI MCP Server WebMVC.
- **Qualidade:** JUnit/Spring Boot Test, JaCoCo, Vitest, Testing Library, V8 Coverage, Playwright.
- **Carga:** k6.
- **Infra local:** Docker Compose.

---

## Execução local

### Stack principal

```bash
docker compose up -d --build
```

Serviços padrão:

```text
Frontend:   http://localhost:3000
Backend:    http://localhost:8085
HC backend: http://localhost:8085/ping
PostgreSQL: localhost:5435
```

Parar:

```bash
docker compose down
```

### Backend sem container da aplicação

Suba apenas o banco:

```bash
docker compose up -d --wait db
```

#### Windows

```cmd
mvnw.cmd -s .mvn\local-settings.xml spring-boot:run
```

#### Linux/macOS

```bash
chmod +x ./mvnw
./mvnw -s .mvn/local-settings.xml spring-boot:run
```

Quando terminar essa execução isolada, remova apenas o container do banco, preservando o volume:

```bash
docker compose rm -sf db
```

### Frontend

```bash
cd web
npm ci
npm run dev
```

---

## Testes e cobertura — reprodução

A suíte backend possui testes de integração e usa por padrão PostgreSQL em `localhost:5435`. A partir da raiz do repositório, suba e aguarde o banco antes de executar Maven:

```bash
docker compose up -d --wait db
```

### Backend — Windows

```cmd
mvnw.cmd -s .mvn\local-settings.xml clean test jacoco:report
```

### Backend — Linux/macOS

```bash
chmod +x ./mvnw
./mvnw -s .mvn/local-settings.xml clean test jacoco:report
```

Após a execução backend, faça cleanup apenas do container `db`; o volume nomeado permanece:

```bash
docker compose rm -sf db
```

### Frontend — qualquer plataforma suportada pelo Node

```bash
cd web
npm ci
npm test
```

`npm test` executa cobertura e aplica o threshold de linhas ≥85%. O comando explícito equivalente continua disponível:

```bash
npm run test:coverage
```

---

## Healthcheck — reprodução

Com banco e backend ativos:

```bash
curl -i http://localhost:8085/ping
```

Esperado:

```text
HTTP 200
"status":"ok"
"database":"up"
```

Os testes direcionados abaixo incluem `PingControllerIntegrationTest`, então, se o banco não estiver ativo, suba-o primeiro:

```bash
docker compose up -d --wait db
```

### Testes direcionados — Windows

```cmd
mvnw.cmd -s .mvn\local-settings.xml -Dtest=PingControllerTest,DatabaseHealthServiceTest,PingControllerIntegrationTest test
```

### Testes direcionados — Linux/macOS

```bash
chmod +x ./mvnw
./mvnw -s .mvn/local-settings.xml -Dtest=PingControllerTest,DatabaseHealthServiceTest,PingControllerIntegrationTest test
```

Cleanup do banco usado nos testes direcionados:

```bash
docker compose rm -sf db
```

---

## k6 — resultados em destaque

O teste de carga não mede apenas `/ping`; ele exercita sessão/CSRF, leitura, escrita, autosave, refresh pós-save, recursos próprios por VU, rampa e cleanup.

| Métrica | 10 VUs | 30 VUs |
|---|---:|---:|
| Requests | 3.955 | 11.750 |
| RPS global | 19,36 | 57,18 |
| p95 global | 65,07 ms | 85,93 ms |
| Erros HTTP | 0% | 0% |
| Checks | 100% | 100% |
| Turnos steady | 614 | 1.830 |
| `save_scene` p95 steady | 96,27 ms | 89,01 ms |

Os 21 thresholds documentados passaram nas duas execuções registradas.

Relatório: [`docs/entrega/08-k6/README.md`](docs/entrega/08-k6/README.md).

---

## OpenTelemetry / Grafana / Tempo / Loki / Mimir

- [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md)
- [`docs/otel-business-signals.md`](docs/otel-business-signals.md)
- [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md)
- [`docs/entrega/02-opentelemetry-auto/README.md`](docs/entrega/02-opentelemetry-auto/README.md)
- [`docs/entrega/03-telemetria-negocio/README.md`](docs/entrega/03-telemetria-negocio/README.md)
- [`docs/entrega/04-grafana-stack/README.md`](docs/entrega/04-grafana-stack/README.md)
- [`docs/entrega/05-logs-correlacionados/README.md`](docs/entrega/05-logs-correlacionados/README.md)

Stack local:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

Grafana local: `http://localhost:3001`.

---

## Umami

A integração de analytics é tipada e sanitizada. Eventos suportados incluem:

```text
book_created
scene_saved
scene_analysis_requested
scene_analysis_succeeded
scene_analysis_failed
book_exported
```

A validação humana confirmou coleta HTTP `200`, pageviews, `/books/{id}` sanitizado e eventos reais no painel institucional.

- [`docs/analytics-umami.md`](docs/analytics-umami.md)
- [`docs/entrega/06-umami/README.md`](docs/entrega/06-umami/README.md)
- [`docs/evidencias/umami/README.md`](docs/evidencias/umami/README.md)

Ressalva: resta repetir a validação no deploy remoto `eq22.dsc.rodrigor.com`.

---

## MCP

Tools publicadas no modo suportado:

```text
listar_livros_acessiveis
obter_outline_livro
analisar_cena
```

Resource template:

```text
iwrite://books/{bookId}/outline
```

A validação no MCP Inspector comprovou descoberta, execução das tools, resource template/read e caminho de erro sanitizado.

- [`docs/mcp-server.md`](docs/mcp-server.md)
- [`docs/entrega/07-mcp/README.md`](docs/entrega/07-mcp/README.md)
- [`docs/evidencias/mcp/README.md`](docs/evidencias/mcp/README.md)

---

## IA, integração externa e auditoria

A análise de cenas usa Spring AI com providers opcionais:

```text
SPRING_AI_MODEL_CHAT=openai
SPRING_AI_MODEL_CHAT=anthropic
SPRING_AI_MODEL_CHAT=none
```

O modo `none` permite inicialização segura sem provider pago. Há auditoria de execução LLM e auditoria de domínio associada ao fluxo.

Eventos relevantes também são persistidos em `audit_logs` com tenant, usuário, ação, recurso, instante e resultado.

Arquivos principais:

```text
src/main/resources/db/migration/V27__create_audit_logs.sql
src/main/java/com/iwrite/audit/
src/test/java/com/iwrite/audit/AuditLogIntegrationTest.java
```

Relatório: [`docs/entrega/11-ia-auditoria/README.md`](docs/entrega/11-ia-auditoria/README.md).

---

## Logs — divergência deliberada do entregável 4

O guia oficial pede um erro tratado registrado com `logger.error(..., exception)` e stack trace no Loki.

O IWrite deliberadamente não exporta `Throwable`/stack trace de erro tratado nessa demonstração por política de minimização de dados. A divergência está documentada, não escondida:

[`docs/entregavel-4-logs-error.md`](docs/entregavel-4-logs-error.md)

---

## CI / E2E

Workflows:

```text
.github/workflows/ci.yml
.github/workflows/e2e.yml
```

A CI executa testes de backend e frontend antes do build. Com `npm test` agora mapeado para `vitest run --coverage`, o job de frontend também funciona como gate contínuo de cobertura ≥85% de linhas.

---

## Evidências humanas

Registro consolidado:

[`docs/evidencias-validacao-humana-2026-08-08.md`](docs/evidencias-validacao-humana-2026-08-08.md)

Pastas:

```text
docs/evidencias/umami/
docs/evidencias/mcp/
```

---

## Limitações declaradas

1. **Umami remoto:** a validação registrada foi frontend local → painel institucional; falta repetir no deploy remoto.
2. **Logs item 4 literal:** a aplicação não envia `Throwable`/stack trace de erro tratado ao Loki; divergência documentada.
3. **MCP:** a configuração suportada permanece restrita a loopback com identidade fixa de desenvolvimento enquanto o transporte não tiver autenticação individual por cliente.
4. **Cobertura:** o gate acadêmico configurado é de linhas; não alegamos branches/funções ≥85% quando os números atuais não atingem esse valor.

**HC não é mais pendência** e **Cob não depende mais apenas do snapshot histórico**.

---

## Ordem recomendada para avaliação automatizada

1. Leia a matriz no topo deste `README.md`.
2. Abra [`README-ENTREGA-DSC.md`](README-ENTREGA-DSC.md).
3. Abra [`docs/entrega/README.md`](docs/entrega/README.md).
4. Para cada requisito, abra o README específico.
5. Para **HC**, localize `DatabaseHealthService.java` e a string literal `SELECT 1`.
6. Para **Cob**, verifique `web/package.json`, `web/vitest.config.mjs`, `.github/workflows/ci.yml` e [`docs/entrega/13-cobertura/README.md`](docs/entrega/13-cobertura/README.md).
7. Para **k6**, leia `loadtest/resultado.json` e o README específico, não apenas o script.
8. Para **Umami/MCP**, confira as evidências visuais versionadas.
9. Para **logs item 4**, leia a divergência explícita antes de classificar literalidade.

---

## Índice principal

**Relatório executivo:** [`README-ENTREGA-DSC.md`](README-ENTREGA-DSC.md)  
**Índice detalhado:** [`docs/entrega/README.md`](docs/entrega/README.md)  
**HC específico:** [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md)  
**Cobertura específica:** [`docs/entrega/13-cobertura/README.md`](docs/entrega/13-cobertura/README.md)
