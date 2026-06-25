# IWrite

O IWrite é uma aplicação web para planejamento, organização e escrita de livros. O projeto combina um backend Spring Boot com um frontend Next.js e evoluiu de um editor estruturado de manuscritos para uma plataforma com planejamento narrativo, histórico de cenas, notebook, metas e métricas pessoais de escrita.

## Estado documentado

Este snapshot cobre o projeto até a PR #52, mergeada em 25 de junho de 2026.

Nesse ponto, o sistema já possuía:

- livros, seções, capítulos e cenas;
- editor TipTap com autosave e controle de troca de cena;
- personagens, locais, itens e planejamento de cenas;
- outline, storyboard, kanban e seleção de cena por URL;
- exportação Markdown e DOCX;
- histórico de versões e restauração segura;
- notebook por livro;
- metas diárias e semanais;
- dashboard do livro e dashboard global do usuário;
- streaks e progresso de escrita por usuário;
- fundação multi-tenant e isolamento por tenant;
- ledger de contagem de palavras com idempotência, fingerprint, locking e rollback;
- semântica separada entre progresso do manuscrito, progresso pessoal e contribuição registrada.

## Stack principal

### Backend

- Java 21
- Spring Boot 3
- Spring Data JPA / Hibernate
- PostgreSQL
- Flyway
- Maven Wrapper

### Frontend

- Next.js
- TypeScript
- Tailwind CSS
- TanStack Query
- TipTap
- Vitest

### Infraestrutura local

- Docker Compose
- PostgreSQL em container
- backend e frontend executados separadamente durante o desenvolvimento

## Princípios atuais

- O backend é a fonte de verdade para word count, progresso e autorização.
- Datas históricas de progresso são preservadas como registradas.
- Métricas produtivas não devem ser confundidas com alterações líquidas no manuscrito.
- Recursos de outro tenant devem ser indistinguíveis de recursos inexistentes.
- Migrations antigas não são editadas; mudanças entram em novas versões Flyway.
- Mudanças de alto risco são validadas por testes focados, suíte completa, auditoria e review de PR.

## Navegação

- [Roadmap e estado atual](Roadmap.md)
- [Diário de desenvolvimento](Development-Log.md)
- [Arquitetura](Architecture.md)
- [Decisões arquiteturais](Architectural-Decisions.md)
- [Migrations e evolução do banco](Database-Migrations.md)
- [Qualidade, testes e processo de revisão](Quality-and-Review.md)

## Limite desta versão

Book ownership explícito, colaboradores, convites e Resend ainda não estavam mergeados no estado documentado. Esses assuntos pertencem às fases C1 e C2.