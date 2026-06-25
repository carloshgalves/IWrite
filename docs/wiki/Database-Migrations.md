# Migrations e evolução do banco

O IWrite usa Flyway e PostgreSQL. Este documento representa o schema até V24.

## Regras

- migrations aplicadas não são editadas;
- cada mudança recebe uma nova versão;
- alterações de ownership seguem nullable → backfill → `NOT NULL` → constraints;
- SQL dependente de PostgreSQL é testado em PostgreSQL real;
- migrations de alto risco recebem teste explícito da versão anterior para a nova.

## Linha evolutiva

### V1–V4 — núcleo do manuscrito

- V1: livros;
- V2: seções;
- V3: capítulos;
- V4: cenas;
- índices básicos de parent/ordenação acompanharam a hierarquia.

### V5–V14 — expansão funcional

Migrations intermediárias acompanharam a evolução de:

- metadados e status;
- personagens;
- locais;
- itens;
- planejamento de cenas;
- metas e dashboard;
- estruturas auxiliares do editor e exportação.

A documentação histórica dessas versões é tratada como evolução incremental; os arquivos SQL continuam sendo a fonte exata de cada coluna e constraint.

### V15 — daily writing progress

Introdução do agregado diário de progresso de escrita.

### V16 — writing schedules

Introdução dos schedules de escrita por livro.

### V17–V19 — ledger, histórico e separação de métricas

Essa faixa consolidou:

- eventos de contagem de palavras;
- integração com histórico/restauração;
- separação entre alteração produtiva e ajuste do manuscrito;
- evolução das constraints e agregados diários.

A V18 criou os eventos de word count e dividiu as métricas de daily progress.

### V20 — tenant pessoal e timezone

Criou:

- `tenants`;
- `users`;
- `tenant_memberships`;
- usuário e tenant determinísticos para dados legados;
- `books.tenant_id` com backfill;
- timezone do usuário e timezone padrão do tenant.

### V21 — evolução posterior da fundação tenant

Ajustes complementares de schema prepararam ownership pessoal de progresso e atribuição de ator.

### V22 — ownership de progresso

- schedules por `user + book`;
- daily progress por `user + book + date`;
- atribuição de usuário nos eventos necessários;
- backfill de dados anteriores.

### V23 — request fingerprint

Adicionou fingerprint aos eventos idempotentes do ledger, permitindo distinguir:

- retry da mesma operação;
- reutilização incompatível da mesma chave.

### V24 — índices de dashboard

```sql
create index idx_daily_progress_user_date_book
    on book_daily_writing_progress (user_id, progress_date, book_id);

create index idx_daily_progress_book_date_user
    on book_daily_writing_progress (book_id, progress_date, user_id);
```

Objetivos:

- dashboard global por usuário e período;
- analytics por livro, data e contribuidor.

A constraint única de progresso por usuário, livro e data foi preservada.

## Estado no fim da PR #52

- migration head: V24;
- dados históricos de progresso preservados;
- tenant isolation aplicada aos domínios principais;
- Book ownership explícito e `book_collaborators` ainda não existiam.

## Checklist para nova migration

- [ ] usar a próxima versão disponível;
- [ ] não modificar SQL antigo;
- [ ] testar dados legados;
- [ ] validar rollback transacional possível durante aplicação;
- [ ] nomear constraints e índices explicitamente;
- [ ] verificar `ON DELETE`;
- [ ] verificar concorrência e unicidade;
- [ ] testar a passagem da versão anterior para a nova;
- [ ] executar suíte completa.