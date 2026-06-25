# Roadmap e estado atual

## Estado consolidado até a PR #52

### Concluído

- **MVP estrutural:** livros, seções, capítulos e cenas.
- **Experiência de escrita:** workspace, outline, editor TipTap, autosave, focus mode e seleção de cena por URL.
- **Planejamento narrativo:** personagens, locais, itens, planejamento de cenas, storyboard e kanban.
- **Organização:** notebook por livro e categorias/notas.
- **Exportação:** Markdown e DOCX.
- **Histórico:** snapshots de cena, restauração, revisão de conteúdo e proteção contra sobrescrita concorrente.
- **Metas e progresso:** metas diárias e semanais, streak, dashboard do livro e dashboard global do usuário.
- **Qualidade:** fundação de testes backend/frontend, E2E e CI periódico.
- **Multi-tenancy:** tenant pessoal, usuário, timezone e isolamento progressivo de todos os principais domínios.
- **B7a:** ownership de progresso e schedules por usuário.
- **B7b:** data efetiva de escrita derivada do timezone do usuário.
- **B7c:** idempotência, fingerprint, locking e integridade do ledger.
- **B7d-a:** dashboard pessoal, contribuições registradas e separação entre métricas pessoais e compartilhadas.

### Em andamento fora deste snapshot

- **C1 — ownership explícito do livro, colaboradores e autorização por livro.**

### Próximas fases prioritárias

- **C2 — convites e Resend:** token seguro, hash persistido, expiração, uso único, revogação, reenvio e aceite transacional.

### Adiado

- **B7d-b — atividade geral de projeto e activity streaks.**

Foi adiada porque não bloqueia C1/C2 e exigiria instrumentação transversal em muitos módulos.

## Critério de prioridade

1. segurança e isolamento;
2. entregas obrigatórias da disciplina;
3. integridade de dados;
4. experiência de uso;
5. refinamentos e métricas secundárias.

## Definição de concluído por fase

Uma fase só é considerada concluída quando:

- migrations aplicam em PostgreSQL real;
- testes focados passam;
- suíte backend completa passa;
- suíte frontend e build passam quando houver alteração de contrato/UI;
- `git diff --check` passa;
- findings de auditoria são classificados e corrigidos em lote;
- review da PR não deixa blocker ou high finding sem resolução;
- documentação é atualizada após o merge.