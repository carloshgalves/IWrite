# Diário de desenvolvimento

Este diário foi reconstruído retrospectivamente a partir de merges e marcos verificáveis. Entradas antigas são agrupadas por fase; não representam anotações feitas necessariamente no mesmo dia.

## Maio de 2026 — fundação do workspace e editor

### PRs #1–#9 — workspace, outline e escrita

- Workspace dividido em componentes reutilizáveis.
- Sidebar e outline estruturados.
- Editor de cena com feedback de salvamento e erro.
- Edição/exclusão de livros, seções, capítulos e cenas.
- TipTap e toolbar.
- Correção de troca de cenas.
- Autosave com debounce.

**Aprendizado:** estado local do editor precisa estar vinculado à cena ativa; callbacks atrasados não podem salvar conteúdo na cena errada.

### PRs #10–#17 — planejamento narrativo

- Personagens.
- Locais e itens.
- Planejamento de cenas com POV, participantes, local, itens, objetivo, conflito, resultado e notas.
- Refinamentos de layout do workspace.

### PRs #18–#25 — dashboard e experiência de escrita

- Primeiro dashboard do livro.
- Meta de palavras.
- Fundação de testes backend/frontend.
- Drag and drop no outline.
- Ordenação de livros.
- Focus mode.
- Configuração de espaçamento do TipTap.

### PRs #26–#36 — exportação, E2E, navegação e notebook

- Exportação Markdown e DOCX.
- Playwright E2E e execução periódica.
- Seleção de cena na URL.
- Evolução do dashboard.
- Notebook.
- Meta diária e backend inicial de streak.

## Junho de 2026 — planejamento, histórico e multi-tenancy

### PRs #37–#39 — visualizações de planejamento

- Planejamento semanal.
- Storyboard v1.
- Kanban de cenas.

### PR #40 — histórico e restauração de cenas

- snapshots imutáveis;
- hash para deduplicação;
- checkpoints automáticos/manuais;
- paginação;
- `contentRevision`;
- restauração segura diante de alterações locais;
- integração com ledger e progresso.

### PRs #41–#42 — estabilidade de testes e notebook

- Correções na infraestrutura de testes PostgreSQL.
- Consolidação do notebook.

### PR #43 — tenant pessoal, usuário e timezone

- `tenants`, `users`, `tenant_memberships`;
- associação de livros ao tenant;
- timezone do usuário e default do tenant;
- backfill determinístico para dados legados.

### PRs #44–#48 — isolamento por tenant

Isolamento aplicado progressivamente a livros/exports, estrutura de manuscrito, histórico, entidades narrativas e notebook. Recursos inacessíveis e inexistentes passaram a usar resposta pública equivalente.

### PR #49 — B7a: ownership pessoal do progresso

Schedules e daily progress passaram a ser pessoais por usuário/livro; eventos passaram a registrar ator.

### PR #50 — B7b: timezone efetivo

`WritingDayResolver` passou a derivar a data de escrita a partir de instante UTC + timezone efetivo do usuário. Datas históricas não são reinterpretadas.

### PR #51 — B7c: integridade do ledger

- lock pessimista do agregado;
- idempotência por `operationId`;
- fingerprint da requisição;
- retries seguros;
- rollback conjunto entre cena, versão, ledger e progresso.

### PR #52 — B7d-a: dashboards e contribuições

- `/api/dashboard/me`;
- dashboard global;
- contribuições por livro;
- separação entre manuscrito compartilhado e progresso pessoal;
- índices para analytics.

### PR #53 — bootstrap da wiki

Criou `docs/wiki/` como fonte versionada da documentação arquitetural. O snapshot inicial parou propositalmente na PR #52 e permaneceu sem atualização por várias fases — lacuna corrigida pela limpeza administrativa de agosto.

## Final de junho / início de julho — colaboração, IA e auditoria

### PR #98 — C1: ownership explícito e colaboração base

- `books.owner_user_id`;
- `book_collaborators`;
- autorização por livro;
- listagem limitada a livros acessíveis;
- ações owner-only;
- semântica não enumerável preservada;
- migrations V25/V26.

### PR #101 — prontidão de container e `/ping`

Adicionou endpoint público de health inicial e consolidou requisitos de execução/container daquele período.

### PRs #102–#103 — primeira feature de IA

- análise estruturada de cena;
- interface `WritingAssistant` provider-neutral;
- OpenAI-compatible provider;
- UI de análise no editor;
- proteção contra respostas obsoletas ao trocar de cena;
- IA somente sobre conteúdo sincronizado.

### PRs #104–#105 — auditoria LLM e integração da análise

- `LlmExecutionGateway`;
- migration V28;
- status/categorias de erro;
- tokens/custo opcional;
- auditoria sem manuscrito/prompt/resposta completos;
- análise de cena passou pelo gateway auditável.

### PR #106 — fundação segura de convites

- migration V29;
- token de 256 bits;
- apenas hash SHA-256 persistido;
- status/expiração/revogação;
- constraints e índices de concorrência;
- criação/revogação owner-only.

Aceite transacional e UX ficaram para etapa posterior e hoje são consolidados na #147.

## Julho / início de agosto — operação observável e autenticação real

### PR #132 — Umami + MCP

- analytics opcional com URLs/propriedades sanitizadas;
- eventos de produto allowlisted;
- servidor MCP mínimo com 3 tools + 1 resource;
- reutilização dos services/autorização existentes;
- guard de loopback;
- rate limit da análise via MCP;
- testes adversariais de isolamento.

### PR #134 — OpenTelemetry automático

- Java Agent versionado e checksum validado;
- OTLP opcional;
- stack LGTM local;
- configuração segura por ambiente;
- traces HTTP/JDBC e métricas JVM;
- CI do entrypoint.

### PR #138 — spans e métricas de negócio

Instrumentou salvamento de cena e análise assistida, incluindo ciclo de vida transacional correto, vocabulários fechados e proteção contra dados sensíveis.

### PR #140 — logs estruturados e correlação

- eventos estruturados;
- trace/log correlation;
- Loki;
- severidade baseada no resultado real;
- não exportação de stack trace em erros tratados;
- sanitização de provider/model family.

### PR #139 — autenticação real e sessão multi-tenant

Concluiu #135, #136, #133 e #137:

- `user_credentials` (V30);
- Spring Security e sessão de servidor;
- CSRF;
- login/me/logout;
- tenant derivado de membership;
- rate limiting por origem/conta;
- login frontend;
- reconciliação de sessão/cache entre abas;
- seed/demo multi-tenant reproduzível.

### PR #141 — k6 autenticado e baseline

Substituiu carga superficial de `/ping` por cenário real de escrita:

- login/CSRF;
- recursos próprios por VU;
- leitura de biblioteca/outline/cena;
- autosave;
- update de conteúdo;
- refresh pós-save;
- cleanup e guard contra alvo externo;
- evidências para 10 e 30 VUs.

## 5–10 de agosto — cadastro público e fechamento acadêmico

### PR #149 — cadastro público e workspace pessoal

Concluiu #143:

- `POST /api/auth/register`;
- criação transacional de User, UserCredential, Tenant, TenantMembership OWNER e UserPersona;
- estabelecimento da sessão pelo mesmo mecanismo do login;
- migrations V31–V34;
- normalização/canonicalização segura de email;
- política de senha alinhada ao limite efetivo do bcrypt;
- robustez contra concorrência e session races.

A PR também semeou parcialmente a #144 ao criar `user_personas`, mas não implementou API/UI de perfil nem múltiplas personas editáveis.

### PR #154 — coexistência MCP + chat model

Removeu ciclo de dependência entre o catálogo MCP e o resolver de tools do modelo. MCP continua exposto ao servidor sem permitir que a própria LLM invoque recursivamente as tools MCP.

### PR #155 — Anthropic/Claude

Adicionou Anthropic como provider real de análise de cenas, preservando OpenAI e `none`.

### PR #156 — validação humana Umami/MCP

Versionou evidências reais do painel Umami e MCP Inspector sem secrets/Website ID real.

### PR #157 — relatório acadêmico consolidado

Criou relatório explícito requisito → implementação → teste → evidência e registrou a divergência deliberada da rubrica de logs com stack trace.

### PR #158 — healthcheck database-aware

- `SELECT 1` real ao PostgreSQL;
- 200/up e 503/down;
- pool dedicado e deadlines curtos;
- Docker healthcheck atravessando frontend/proxy → backend → banco.

### PR #159 — documentação final e cobertura

- consolidou documentação de HC/cobertura;
- transformou cobertura frontend em gate contínuo de linhas ≥85%;
- registrou 87,16% de linhas no frontend e 92,01% no backend na validação daquele marco.

## 22 de agosto de 2026 — transição administrativa pós-disciplina

Foi feita uma limpeza administrativa para alinhar GitHub e `master`:

- issues de autenticação concluídas foram fechadas;
- epic #123 e subissues de Umami/MCP/validação/evidências foram fechadas;
- #56 e #58 foram absorvidas pela #147;
- #64 foi substituída pela #145;
- #57 deixou de fixar Resend e passou a ser provider-neutral;
- #142 e #144 foram atualizadas para reconhecer o que a PR #149 já entregou;
- issues de CI, observabilidade e health foram reescritas para distinguir fundação já implementada de operação futura;
- referências a servidor/bucket/contas da disciplina foram removidas do backlog atual quando eram dependências indevidas;
- `docs/wiki/` deixou de ser snapshot da PR #52 e passou a representar o produto atual;
- README principal passou a ser product-first, mantendo entregáveis acadêmicos como arquivo histórico.

## Próxima atualização

O próximo marco de desenvolvimento deve atualizar este diário quando uma das frentes canônicas atuais for mergeada, em especial #144–#148 ou outra entrega de produto/infra com impacto arquitetural.