# IWrite

O IWrite é uma aplicação web para planejamento, organização, escrita e revisão de livros. A documentação desta pasta acompanha o **estado do produto no repositório principal**; material acadêmico permanece preservado em `docs/entrega/` como histórico separado.

## Estado documentado

Este snapshot foi sincronizado em **22 de agosto de 2026**, após as PRs de autenticação, cadastro público, observabilidade, analytics, MCP, healthcheck e consolidação acadêmica até a **PR #159**.

O sistema já possui:

- livros, seções, capítulos e cenas;
- editor TipTap com autosave e revisão otimista;
- outline, storyboard e kanban;
- personagens, locais, itens e planejamento de cenas;
- notebook;
- histórico e restauração segura de cenas;
- metas, streaks e dashboards;
- exportação TXT, Markdown e DOCX;
- multi-tenancy e isolamento por tenant/livro;
- ownership explícito e colaboração base;
- fundação segura de convites;
- autenticação por sessão e cadastro público;
- workspace pessoal e fundação de personas declarativas;
- auditoria de domínio e de execuções LLM;
- análise opcional de cenas com OpenAI/Anthropic;
- OpenTelemetry, Grafana, Tempo, Loki e Prometheus/Mimir;
- Umami opcional;
- MCP mínimo;
- k6 autenticado;
- CI/E2E;
- healthcheck database-aware.

## Estado de produto

A base técnica está consolidada, mas algumas capacidades de produto ainda são incompletas. A frente principal aberta é identidade/colaboração (#142):

- #144 — completar perfil/personas;
- #145 — RBAC/capabilities por livro;
- #146 — múltiplos workspaces;
- #147 — aceite completo de convites e biblioteca compartilhada;
- #148 — UX específica para editor, revisor e leitor beta.

Também permanecem abertas frentes de revisão editorial, busca, publicação PDF/EPUB, importação, object storage, resiliência offline, RAG, séries/universos, LGPD e operação SaaS.

## Stack principal

### Backend

- Java 21;
- Spring Boot 3.4.1;
- Spring Security;
- Spring Data JPA / Hibernate;
- PostgreSQL 16;
- Flyway;
- Spring AI.

### Frontend

- Next.js 15;
- React 19;
- TypeScript;
- Tailwind CSS;
- TanStack Query;
- TipTap;
- Vitest / Testing Library / Playwright.

### Plataforma e diagnóstico

- Docker Compose;
- OpenTelemetry Java Agent;
- Grafana / Tempo / Loki / Prometheus-Mimir;
- Umami;
- MCP;
- k6.

## Princípios atuais

- O backend é a fonte de verdade para identidade, tenant, autorização, word count e progresso.
- Recursos inacessíveis não devem ser enumeráveis por UUID.
- Persona global não concede autorização; permissões efetivas são contextuais.
- Migrations publicadas são forward-only e mudanças críticas são testadas em PostgreSQL real.
- Conteúdo de manuscrito, prompts, tokens e secrets não devem aparecer em telemetria operacional.
- Integrações externas devem ser opcionais/configuráveis e não impedir desenvolvimento local quando desabilitadas.
- O monólito modular é o baseline atual; microserviços não são objetivo por si só.
- Nenhuma arquitetura futura depende de contas ou servidores da antiga disciplina.

## Navegação

- [Roadmap e estado atual](Roadmap.md)
- [Diário de desenvolvimento](Development-Log.md)
- [Arquitetura](Architecture.md)
- [Decisões arquiteturais](Architectural-Decisions.md)
- [Migrations e evolução do banco](Database-Migrations.md)
- [Qualidade, testes e processo de revisão](Quality-and-Review.md)

## Histórico acadêmico

O projeto foi desenvolvido e avaliado na disciplina DSC/UFPB. Evidências, rubricas, vídeos e configurações institucionais continuam versionados para fins históricos, principalmente em `README-ENTREGA-DSC.md` e `docs/entrega/`.

Esses documentos não representam o ambiente operacional atual do produto.