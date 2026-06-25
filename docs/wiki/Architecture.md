# Arquitetura

## Visão geral

O IWrite usa uma arquitetura web separada em backend e frontend.

```text
Next.js / TypeScript
        ↓ REST
Spring Boot / Java 21
        ↓ JPA
PostgreSQL / Flyway
```

## Backend

### Organização por domínio

Os pacotes são separados por áreas funcionais, entre elas:

- `book`;
- `section`;
- `chapter`;
- `scene`;
- `sceneversion`;
- `character`;
- `location`;
- `item`;
- `notebook`;
- `outline`;
- `dashboard`;
- `writingprogress`;
- `tenant`;
- `user`;
- `export`.

Cada domínio normalmente possui:

- controller;
- DTOs;
- entity;
- repository;
- service;
- testes de integração quando o comportamento exige PostgreSQL real.

### Hierarquia principal do manuscrito

```text
Tenant
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
    └── BookWordCountEvent
```

Até a PR #52, o acesso era isolado por tenant. Ownership explícito do livro e colaboradores pertencem à fase C1 posterior.

## Editor e conteúdo

A cena possui conteúdo estruturado e texto normalizado para cálculo de palavras.

O frontend usa TipTap, mas o backend continua responsável por:

- validar revisões;
- calcular word count;
- manter ledger;
- atualizar daily progress;
- preservar histórico;
- impedir retries incompatíveis.

### Concorrência

`contentRevision` protege contra sobrescrita de conteúdo mais novo.

Operações sensíveis usam:

- optimistic concurrency para revisão de conteúdo;
- pessimistic locking para o agregado de palavras do livro;
- `operationId` para idempotência;
- fingerprint para garantir que a mesma chave não seja reutilizada com outra requisição.

## Ledger e progresso

O sistema diferencia três conceitos:

1. **Estado atual do manuscrito** — total de palavras existentes.
2. **Palavras produtivas** — crescimento considerado escrita produtiva.
3. **Ajustes do manuscrito** — alterações líquidas que não devem inflar produtividade.

O ledger registra mutações imutáveis. O daily progress agrega por:

```text
user + book + progress_date
```

Datas de escrita são derivadas de um instante UTC e do timezone efetivo do usuário.

## Dashboards

### Dashboard do livro

Separa:

- métricas compartilhadas do manuscrito;
- `myWriting.progress` pessoal;
- `myWriting.schedule` pessoal;
- planejamento e distribuição das cenas;
- contribuições registradas.

### Dashboard global

`GET /api/dashboard/me` agrega atividade pessoal entre livros do tenant atual, respeitando:

- palavras produtivas;
- ajustes;
- dias positivos distintos;
- timezone efetivo;
- datas históricas persistidas;
- exclusão de datas futuras relativas ao novo timezone.

## Frontend

O frontend utiliza:

- App Router do Next.js;
- TypeScript;
- TanStack Query para dados remotos;
- TipTap no editor;
- componentes específicos para workspace, outline, dashboards, planejamento e notebook.

Estados de salvamento incluem:

- salvo;
- alterações não salvas;
- salvando;
- erro ao salvar.

A troca de cena cancela ou invalida callbacks antigos para impedir gravação cruzada.

## Banco e migrations

Flyway é a única forma aceita para evolução de schema.

Regras:

- migrations antigas não são alteradas;
- mudanças incompatíveis seguem o padrão nullable → backfill → constraint;
- migrations críticas recebem teste explícito de versão anterior para versão nova;
- PostgreSQL real é usado para validar locks, constraints, índices e SQL específico.

## Isolamento

Até a PR #52:

- todo livro pertence a um tenant;
- recursos aninhados são resolvidos pela cadeia até o livro;
- UUID conhecido não concede acesso;
- recurso estrangeiro e recurso inexistente usam resposta pública equivalente;
- progresso pessoal é também limitado pelo usuário atual.

## Fora do snapshot

Não fazem parte desta versão documentada:

- ownership explícito do livro;
- `BookCollaborator`;
- convites;
- Resend;
- autenticação de produção;
- activity event feed.