# IWrite

IWrite é uma aplicação web para **planejamento, organização, escrita e revisão de livros**. O modelo principal é `Livro → Seção → Capítulo → Cena`, com editor TipTap, autosave, planejamento narrativo, histórico de versões, métricas de escrita, colaboração e análise opcional com LLM.

> **Fonte de verdade:** o estado atual do produto é o código da branch `master` deste repositório. A documentação acadêmica da disciplina foi preservada como histórico e evidência, mas não define mais a arquitetura, o deploy ou o roadmap operacional do IWrite.

## Estado atual

O `master` já possui:

- livros, seções, capítulos e cenas;
- editor TipTap com autosave, revisão otimista e focus mode;
- outline, storyboard e kanban;
- personagens, locais, itens e planejamento de cenas;
- notebook por livro;
- histórico e restauração segura de cenas;
- metas diárias/semanais, streaks e dashboards;
- exportação TXT, Markdown e DOCX;
- multi-tenancy e isolamento por tenant/livro;
- ownership explícito e colaboração base por livro;
- modelo seguro de convites com token de uso público não persistido em claro;
- autenticação real por email/senha e sessão de servidor;
- cadastro público com workspace pessoal;
- fundação de personas declarativas de usuário;
- auditoria de domínio e auditoria de execuções LLM;
- análise opcional de cenas com OpenAI ou Anthropic via Spring AI;
- OpenTelemetry com traces, métricas e logs estruturados correlacionados;
- stack local Grafana, Tempo, Loki e Prometheus/Mimir;
- analytics opcional com Umami;
- servidor MCP mínimo e seguro na configuração suportada;
- cenário k6 autenticado e reproduzível;
- CI, E2E e gate de cobertura frontend;
- healthcheck database-aware com consulta real ao PostgreSQL.

## O que está em desenvolvimento

As frentes abertas são rastreadas pelas Issues. A principal sequência atual de identidade e colaboração está em [#142](https://github.com/carloshgalves/IWrite/issues/142):

1. [#144](https://github.com/carloshgalves/IWrite/issues/144) — completar perfil e personas;
2. [#145](https://github.com/carloshgalves/IWrite/issues/145) — papéis e capabilities granulares por livro;
3. [#146](https://github.com/carloshgalves/IWrite/issues/146) — múltiplos workspaces;
4. [#147](https://github.com/carloshgalves/IWrite/issues/147) — aceite de convites e biblioteca compartilhada;
5. [#148](https://github.com/carloshgalves/IWrite/issues/148) — experiências de editor, revisor e leitor beta.

Outras frentes relevantes incluem busca global (#66), PDF/EPUB (#67), object storage (#68), revisão editorial (#65), RAG/consistência (#71), importação (#110), resiliência offline (#111), séries/universos (#113), LGPD (#114) e fundação SaaS (#118–#122).

## Arquitetura

```text
Navegador
   |
   | mesma origem (/api/*)
   v
Next.js 15 / React 19
   |
   | proxy/rewrite server-side
   v
Spring Boot 3.4.1 / Java 21
   |
   +----> PostgreSQL 16 / Flyway
   |
   +----> OpenTelemetry Java Agent --OTLP--> Grafana / Tempo / Loki / Mimir
   |
   +----> Spring AI -----------------------> OpenAI ou Anthropic (opcional)
   |
   +----> MCP -----------------------------> transporte local na configuração suportada

Next.js ----> Umami (opcional)
```

O backend é a fonte de verdade para identidade, tenant, autorização, word count, progresso e operações persistentes. O navegador não escolhe arbitrariamente `userId`, `tenantId` ou papel efetivo.

O projeto permanece um **monólito modular**. Não há motivo atual para dividir domínio em microserviços.

## Principais capacidades

### Manuscrito e escrita

- hierarquia Livro → Seção → Capítulo → Cena;
- TipTap e conteúdo estruturado;
- autosave com debounce;
- `contentRevision` para evitar sobrescrita silenciosa;
- `operationId` + fingerprint para idempotência;
- versões imutáveis e restauração;
- exportação TXT/MD/DOCX.

### Planejamento narrativo

- personagens;
- locais;
- itens;
- POV e participantes de cena;
- objetivo, conflito, resultado e notas;
- storyboard e kanban;
- notebook por livro.

### Progresso e métricas

- metas de escrita;
- streaks;
- progresso diário por usuário;
- dashboard do livro;
- dashboard global;
- ledger de alterações de contagem de palavras;
- distinção entre palavras produtivas, ajustes e total atual do manuscrito.

### Identidade e colaboração

- Spring Security;
- sessão de servidor e CSRF;
- login, logout e restauração de sessão;
- cadastro público;
- workspace pessoal;
- ownership do livro;
- colaboradores;
- fundação segura de convites;
- isolamento multi-tenant não enumerável.

Papéis granulares, múltiplos workspaces e aceite completo de convites ainda estão nas Issues #145–#147.

### IA

A análise de cenas usa Spring AI com providers opcionais:

```text
SPRING_AI_MODEL_CHAT=openai
SPRING_AI_MODEL_CHAT=anthropic
SPRING_AI_MODEL_CHAT=none
```

O modo `none` mantém a aplicação funcional sem provider pago. As execuções passam pelo gateway de auditoria LLM, que registra metadados operacionais controlados sem persistir manuscrito, prompt completo, resposta completa ou API key.

### Observabilidade e analytics

O repositório mantém como capacidades atuais:

- OpenTelemetry Java Agent;
- traces HTTP/JDBC;
- spans e métricas de negócio;
- logs estruturados correlacionados por trace;
- Grafana + Tempo + Loki + Prometheus/Mimir em stack local;
- Umami opcional com URLs e propriedades sanitizadas.

Essas integrações não dependem mais de contas da antiga disciplina. Cada ambiente futuro deverá fornecer sua própria configuração e seus próprios secrets.

### MCP

O servidor MCP mínimo expõe, na configuração suportada:

```text
listar_livros_acessiveis
obter_outline_livro
analisar_cena
```

Resource template:

```text
iwrite://books/{bookId}/outline
```

A camada MCP reutiliza services e autorização existentes, sem duplicar regras de domínio.

## Banco e migrations

- PostgreSQL 16;
- Flyway como única fonte de evolução do schema;
- migration head atual: **V34**;
- migrations críticas possuem testes com PostgreSQL real;
- mudanças de alto risco cobrem constraints, concorrência, backfills e isolamento.

Veja [docs/wiki/Database-Migrations.md](docs/wiki/Database-Migrations.md).

## Execução local

### Stack principal

```bash
docker compose up -d --build
```

Serviços padrão:

```text
Frontend:   http://localhost:3000
Backend:    http://localhost:8085
Health:     http://localhost:8085/ping
PostgreSQL: localhost:5435
```

Parar:

```bash
docker compose down
```

### Backend fora do container

Suba apenas o banco:

```bash
docker compose up -d --wait db
```

Windows:

```cmd
mvnw.cmd -s .mvn\local-settings.xml spring-boot:run
```

Linux/macOS:

```bash
chmod +x ./mvnw
./mvnw -s .mvn/local-settings.xml spring-boot:run
```

### Frontend

```bash
cd web
npm ci
npm run dev
```

## Testes e qualidade

Backend:

```bash
docker compose up -d --wait db
./mvnw -s .mvn/local-settings.xml clean test jacoco:report
```

Frontend:

```bash
cd web
npm ci
npm test
npm run build
```

`npm test` executa Vitest com cobertura e o repositório possui threshold de **linhas ≥ 85%**. Na consolidação da PR #159, o frontend registrou **87,16% de linhas** e o backend **92,01% de linhas**.

Workflows principais:

```text
.github/workflows/ci.yml
.github/workflows/e2e.yml
```

O E2E usa Playwright, credenciais efêmeras de demonstração e stack isolada.

## Healthcheck

`GET /ping` consulta o PostgreSQL de verdade:

```text
GET /ping
  -> PingController
  -> DatabaseHealthService
  -> SELECT 1
  -> PostgreSQL
```

- banco disponível → HTTP 200 / `database=up`;
- banco indisponível → HTTP 503 / `database=down`.

O `Dockerfile` também usa `/api/ping`, de modo que o healthcheck do container verifica frontend/proxy, backend e banco.

## Documentação

### Estado atual e arquitetura

- [Wiki versionada](docs/wiki/README.md)
- [Arquitetura](docs/wiki/Architecture.md)
- [Decisões arquiteturais](docs/wiki/Architectural-Decisions.md)
- [Migrations](docs/wiki/Database-Migrations.md)
- [Qualidade e review](docs/wiki/Quality-and-Review.md)
- [Autenticação e multi-tenancy](docs/authentication-multitenancy.md)
- [Servidor MCP](docs/mcp-server.md)
- [OpenTelemetry](docs/opentelemetry-implementation.md)
- [Sinais de negócio OTel](docs/otel-business-signals.md)
- [Logs correlacionados](docs/otel-correlated-logs.md)
- [Umami](docs/analytics-umami.md)
- [Auditoria LLM](docs/llm-execution-audit.md)
- [Load test](loadtest/README.md)

### Histórico acadêmico

O IWrite foi desenvolvido e avaliado originalmente na disciplina **Desenvolvimento de Sistemas Corporativos (DSC/UFPB)**. O material de entrega permanece versionado para preservar histórico, evidências e decisões técnicas daquele período:

- [README da entrega acadêmica](README-ENTREGA-DSC.md)
- [Índice dos entregáveis](docs/entrega/README.md)
- [Evidências humanas](docs/evidencias-validacao-humana-2026-08-08.md)
- [Vídeo da Avaliação 2](https://youtu.be/aGw0S_mtT60)

Esses documentos são **arquivo histórico**. Referências a `eq22`, servidor institucional, contas da disciplina ou rubricas de avaliação não devem ser interpretadas como dependências atuais do produto.
