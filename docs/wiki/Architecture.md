# Arquitetura

## Visão geral

O IWrite usa um **monólito modular** com frontend e backend separados por contrato HTTP.

```text
Navegador
   |
   | mesma origem /api/*
   v
Next.js 15 / React 19 / TypeScript
   |
   | proxy/rewrite server-side
   v
Spring Boot 3.4.1 / Java 21
   |
   +----> PostgreSQL 16 / Flyway
   |
   +----> Spring AI ----> OpenAI ou Anthropic (opcional)
   |
   +----> OpenTelemetry Java Agent --OTLP--> Grafana / Tempo / Loki / Mimir
   |
   +----> MCP Server (configuração suportada)

Next.js ----> Umami (opcional)
```

O backend continua sendo a autoridade para identidade, tenant, autorização, word count, progresso e mutações persistentes.

## Domínio principal

A hierarquia narrativa continua centrada em:

```text
Tenant / Workspace
└── Book
    ├── BookSection
    │   └── Chapter
    │       └── Scene
    │           └── SceneVersion
    ├── Character
    ├── Location
    ├── Item
    ├── NotebookCategory
    │   └── NotebookNote
    ├── BookWritingSchedule
    ├── DailyWritingProgress
    ├── BookWordCountEvent
    ├── BookCollaborator
    └── BookCollaborationInvitation
```

Identidade adiciona ainda:

```text
User
├── UserCredential
├── UserPersona
└── TenantMembership
```

Auditoria usa modelos próprios para ações de domínio e execuções LLM.

## Backend

O código é organizado por domínios/capacidades, incluindo livros, estrutura de manuscrito, cenas, planejamento narrativo, notebook, dashboards, progresso, autenticação, tenancy, colaboração, auditoria, IA/LLM, exportação, health e MCP.

A decisão atual é **não** dividir o produto em microserviços. Os módulos compartilham processo e banco, mas regras de negócio permanecem encapsuladas em services e adapters específicos.

## Autenticação e sessão

A autenticação real foi consolidada na PR #139 e o cadastro público na PR #149.

Fluxo simplificado:

```text
email + senha
   -> Spring Security / AuthenticationManager
   -> sessão de servidor
   -> principal autenticado
   -> TenantMembership persistida
   -> tenant/workspace efetivo
   -> services autorizados
```

Princípios:

- senha é armazenada somente como hash adaptativo;
- sessão não é guardada como JWT em `localStorage`;
- CSRF protege mutações compatíveis com sessão/cookie;
- `userId`, `tenantId` ou papel enviados pelo cliente não substituem a identidade autenticada;
- membership é validada no backend;
- recurso inacessível mantém semântica não enumerável;
- múltiplos workspaces ainda serão tratados pela #146.

## Ownership e colaboração

Cada livro possui proprietário explícito. Colaboradores ativos são persistidos e o acesso é autorizado no backend.

O modelo atual ainda possui colaboração genérica herdada da C1. A #145 vai separar papéis/capabilities contextuais por livro.

A fundação de convite (`book_collaboration_invitations`) já possui:

- token aleatório de alta entropia;
- somente hash do token persistido;
- expiração/status/revogação;
- constraints e índices para concorrência/duplicidade;
- autorização owner-only para criação/gestão base.

Aceite transacional e UX ponta a ponta permanecem na #147.

## Cadastro e personas

`POST /api/auth/register` cria de forma transacional:

- User;
- UserCredential;
- Tenant pessoal;
- TenantMembership OWNER;
- UserPersona principal;
- sessão autenticada.

`UserPersona` é **declarativa**. Ser `EDITOR` ou `REVIEWER` no perfil não concede acesso a nenhum livro. A edição de múltiplas personas/perfil permanece na #144.

## Editor, concorrência e ledger

A cena possui conteúdo estruturado TipTap e texto normalizado para contagem de palavras.

Mecanismos principais:

- `contentRevision` para optimistic concurrency;
- lock pessimista em agregados do livro quando necessário;
- `operationId` para idempotência;
- fingerprint imutável da requisição;
- SceneVersion como snapshot restaurável;
- mutação da cena, ledger, versão e progresso dentro de fronteiras transacionais coerentes.

O sistema diferencia:

1. total atual do manuscrito;
2. palavras produtivas;
3. ajustes do manuscrito.

Daily progress é pessoal e agrega por usuário + livro + data histórica de escrita.

## IA e auditoria LLM

`WritingAssistant` isola o domínio do provider. Implementações atuais suportam OpenAI e Anthropic; o modo `none` desabilita IA sem impedir startup.

Fluxos de IA passam por `LlmExecutionGateway`, responsável por:

- auditoria operacional;
- status/categoria de erro;
- latência;
- token usage quando fornecido;
- custo opcional quando configurado;
- sanitização e correlação.

Não são persistidos como auditoria: manuscrito completo, prompt completo, resposta completa, senha, token ou API key.

## Observabilidade

O container pode anexar OpenTelemetry Java Agent de forma opcional.

Há:

- auto-instrumentação HTTP/JDBC/JVM;
- spans manuais de negócio;
- métricas de negócio;
- logs estruturados correlacionados com trace;
- stack local Grafana/Tempo/Loki/Prometheus-Mimir;
- vocabulários controlados para reduzir cardinalidade e vazamento.

A #90 agora trata da **operacionalização em produção**, não da criação dessa fundação.

## Analytics

O frontend integra Umami apenas quando configurado.

A camada de analytics:

- sanitiza URLs;
- remove query/hash e identificadores dinâmicos;
- usa eventos e propriedades allowlisted;
- é fail-open;
- não envia texto de manuscrito, títulos privados, email ou IDs brutos.

## MCP

O MCP é uma camada fina sobre services existentes. O catálogo mínimo contém tools para listar livros acessíveis, obter outline e analisar cena, além do resource template do outline.

Na configuração suportada atualmente, o servidor MCP é explicitamente habilitado e protegido por guard de loopback enquanto não há autenticação individual própria do transporte.

## Healthcheck

`GET /ping` consulta PostgreSQL com `SELECT 1` por um datasource de probe com deadlines curtos.

```text
/ping -> DatabaseHealthService -> JdbcTemplate -> PostgreSQL
```

- saudável: 200 / `database=up`;
- indisponível: 503 / `database=down`.

O Docker healthcheck usa `/api/ping`, atravessando a cadeia frontend/proxy → backend → banco.

## Frontend

O frontend usa App Router, TanStack Query e TipTap. As rotas protegidas restauram a sessão por `/api/auth/me`.

A sincronização entre abas combina BroadcastChannel/fallback de storage e invalidação conservadora de cache para não renderizar dados de identidade/tenant antigo após login/logout/reconciliação.

O workspace atual possui modos de visão separados para overview, storyboard, kanban, cenas, personagens, locais, itens e notebook. Evoluções de UX podem reorganizar essa taxonomia sem exigir reescrita do domínio.

## Banco e migrations

Flyway é a única forma aceita para evolução do schema. O head atual é **V34**.

Migrations críticas são verificadas em PostgreSQL real, inclusive cenários de backfill, constraints, concorrência e migração a partir de versão anterior relevante.

## Deploy e ambientes

Docker Compose permanece como ambiente local reproduzível. A imagem combinada existente é útil para desenvolvimento/demonstração e evidências históricas, mas não obriga a topologia futura de produção.

Produção deve evoluir com:

- ambientes e secrets próprios;
- banco/storage privados;
- backup/restore;
- release/rollback;
- monitoramento e alertas;
- infraestrutura independente de provedor.

Contas e servidores da antiga disciplina não fazem parte do baseline arquitetural atual.