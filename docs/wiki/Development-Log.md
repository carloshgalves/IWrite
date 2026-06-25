# Diário de desenvolvimento

Este diário foi reconstruído retrospectivamente a partir de merges e marcos verificáveis. As entradas antigas são agrupadas por fase; não representam anotações feitas no mesmo dia.

## Maio de 2026 — fundação do workspace e editor

### PRs #1–#9 — workspace, outline e escrita

- Workspace dividido em componentes reutilizáveis.
- Sidebar e outline visualmente estruturados.
- Editor de cena com feedback de salvamento e erro.
- Edição e exclusão de livros, seções, capítulos e cenas.
- Refatoração do outline em componentes menores.
- TipTap e toolbar incorporados.
- Correção do bug de troca de cenas no editor.
- Autosave com debounce.

**Aprendizado principal:** estado local do editor precisa estar vinculado à cena ativa; callbacks atrasados não podem salvar conteúdo na cena errada.

### PRs #10–#17 — planejamento narrativo

- CRUD e interface master-detail de personagens.
- Melhorias no layout do workspace.
- Locais e itens.
- Planejamento de cenas com POV, participantes, local, itens, objetivo, conflito, resultado e notas.
- Melhorias na visualização de itens e no espaço útil do editor.

### PRs #18–#25 — dashboard e experiência de escrita

- Primeiro dashboard do livro.
- Meta de palavras do livro.
- Fundação de testes backend e frontend nas PRs #20 e #21.
- Drag and drop no outline.
- Ordenação de livros na home.
- Focus mode.
- Configuração de espaçamento de parágrafos no TipTap.

### PRs #26–#33 — exportação, E2E e navegação

- Exportação Markdown.
- Exportação DOCX.
- Testes Playwright E2E.
- Execução periódica dos testes E2E.
- Seleção de cena refletida na URL.
- Evolução do dashboard.
- Validação do outline a partir do estado da URL.

### PRs #34–#36 — notebook, metas e streak

- Primeira versão do notebook.
- Meta diária.
- Backend inicial de streak.

## Junho de 2026 — planejamento, histórico e consolidação

### PRs #37–#39 — planejamento semanal e visualizações

- Planejamento semanal.
- Storyboard v1.
- Kanban de cenas.

### PR #40 — histórico e restauração de cenas

O histórico de versões consolidou uma mudança importante de arquitetura:

- snapshots imutáveis de conteúdo;
- hash SHA-256 para deduplicação;
- checkpoints automáticos e manuais;
- paginação do histórico;
- `contentRevision` para optimistic concurrency;
- proteção contra autosave atrasado após restauração;
- restauração com escolha explícita para alterações locais não salvas;
- integração entre restauração, ledger e daily progress;
- deleção sem transformar redução de manuscrito em produtividade negativa.

### PRs #41–#42 — estabilidade de testes e notebook

- Correções da infraestrutura de testes com PostgreSQL.
- Consolidação da implementação de notebook.

## 22–25 de junho de 2026 — fundação multi-tenant e progresso pessoal

### PR #43 — tenant pessoal, usuário e timezone

- Criação de `tenants`, `users` e `tenant_memberships`.
- Tenant pessoal determinístico para dados legados.
- Associação de livros ao tenant.
- Timezone de usuário e timezone padrão do tenant.

### PRs #44–#48 — isolamento por tenant

A proteção foi aplicada progressivamente para evitar uma mudança monolítica:

- **#44:** livros e exports;
- **#45:** seções, capítulos e cenas;
- **#46:** histórico e restauração de cenas;
- **#47:** personagens, locais, itens e associações de planejamento;
- **#48:** categorias, notas e exportação do notebook.

Recursos inacessíveis e inexistentes passaram a usar semântica equivalente de 404.

### PR #49 — B7a: ownership pessoal do progresso

- Schedules passaram a pertencer a usuário + livro.
- Daily progress passou a pertencer a usuário + livro + data.
- Eventos do ledger receberam atribuição do ator.
- Usuários diferentes podem manter schedules e progresso independentes no mesmo livro.

### PR #50 — B7b: timezone efetivo

- O relógio de progresso passou a operar em UTC.
- `WritingDayResolver` tornou-se a abstração central da data de escrita.
- “Hoje” é derivado do instante UTC e do timezone efetivo do usuário.
- Datas históricas persistidas não são reinterpretadas após mudança de timezone.

### PR #51 — B7c: integridade do ledger

- Lock pessimista do livro para mutações de agregado.
- Ordem de locks explícita para evitar deadlocks.
- Criação e salvamento de cenas com idempotência.
- Fingerprint da requisição para detectar reutilização incompatível de `operationId`.
- Retries não duplicam eventos nem alteram revisões.
- Rollback transacional preserva consistência entre cena, ledger e daily progress.

### PR #52 — B7d-a: dashboards e contribuições

- Endpoint `/api/dashboard/me`.
- Dashboard global do usuário por período.
- Métricas de palavras produtivas, ajustes, dias de escrita, livros e streaks.
- Contribuições por livro.
- Endpoint de contribuições registradas por livro e usuário.
- Separação entre progresso compartilhado do manuscrito e `myWriting` pessoal.
- Índices para analytics por usuário/data/livro e livro/data/usuário.

#### Findings importantes encontrados em review

- filtro de contribuidor antigo não podia sobreviver à troca de livro;
- datas persistidas que se tornassem futuras após mudança de timezone não podiam inflar o melhor streak;
- livros com deltas líquidos zerados precisavam permanecer na lista quando houve atividade real.

#### Validação final registrada

- 321 testes backend;
- 136 testes frontend;
- build frontend aprovado;
- `git diff --check` aprovado.

## Próxima entrada

A próxima atualização deve ocorrer após o merge da fase C1. Ela deverá registrar ownership explícito de livros, colaboradores, nova autorização e os findings encontrados na auditoria/review.