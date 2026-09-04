# Migrations e evolução do banco

O IWrite usa Flyway e PostgreSQL. O migration head atual é **V36**.

## Regras

- migrations aplicadas/publicadas são tratadas como imutáveis;
- mudanças entram em nova versão forward-only;
- alterações de ownership/dados seguem estratégia segura de coluna → backfill → constraint quando aplicável;
- SQL dependente de PostgreSQL é testado em PostgreSQL real;
- migrations críticas recebem teste explícito da versão anterior relevante para a nova;
- correção de migration já publicada deve preferir nova migration, não reescrever histórico;
- backfills precisam ser determinísticos e falhar de forma explícita diante de ambiguidade.

## Linha evolutiva

### V1–V4 — núcleo do manuscrito

- V1: livros;
- V2: seções;
- V3: capítulos;
- V4: cenas.

### V5–V14 — planejamento narrativo e notebook

A faixa adicionou personagens, locais, itens, associações de cena, campos de planejamento e notebook.

Arquivos SQL continuam sendo a fonte exata de cada coluna, índice e constraint.

### V15 — daily writing progress

Introdução do agregado diário de progresso de escrita.

### V16 — writing schedules

Introdução dos schedules de escrita por livro.

### V17 — scene versions

Snapshots imutáveis de cena para histórico/restauração.

### V18 — word-count events e separação de progresso

Introdução do ledger de contagem de palavras e separação entre métricas produtivas/ajustes.

### V19 — configurações do notebook

Evolução de preferências/metadados do notebook por livro.

### V20 — tenant pessoal, usuário e timezone

Criou a fundação de:

- `tenants`;
- `users`;
- `tenant_memberships`;
- usuário/tenant determinísticos para dados legados;
- `books.tenant_id` com backfill;
- timezone do usuário e default do tenant.

### V21 — índice de livros por tenant

Índice de acesso para a nova fronteira multi-tenant.

### V22 — ownership pessoal do progresso

- schedules por `user + book`;
- daily progress por `user + book + date`;
- atribuição do ator em eventos de word count;
- backfills e constraints correspondentes.

### V23 — request fingerprint

Adicionou fingerprint aos eventos idempotentes do ledger para distinguir retry legítimo de reutilização incompatível de `operationId`.

### V24 — índices de dashboard

Índices para analytics por usuário/data/livro e livro/data/usuário.

### V25 — ownership do livro e colaboradores

`V25__add_book_ownership_and_collaborators.sql`

Introduziu:

- `books.owner_user_id`;
- `book_collaborators`;
- constraints/índices de ownership e acesso compatíveis com tenant;
- fundação C1 de autorização por livro.

### V26 — backfill de colaboradores legados

`V26__backfill_legacy_book_collaborators.sql`

Moveu o backfill legado para migration própria sem alterar a V25 já publicada.

### V27 — audit logs

`V27__create_audit_logs.sql`

Criou trilha de auditoria de domínio com metadados de ator, tenant, ação, recurso, instante e resultado.

### V28 — auditoria de execuções LLM

`V28__create_llm_execution_audits.sql`

Criou modelo especializado para execuções de IA, incluindo status, provider, família/modelo efetivo, latência, tokens/custo opcional e fallback, sem armazenar manuscrito/prompt/resposta completos.

### V29 — convites de colaboração

`V29__create_book_collaboration_invitations.sql`

Criou a fundação segura de convites:

- recipient email normalizado;
- `requested_role` inicial;
- `token_hash` único;
- status/lifecycle;
- expiração, revogação e optimistic locking;
- FK composta para impedir vínculo com livro de outro tenant;
- índice único parcial para convite pendente equivalente.

O token bruto não é persistido.

### V30 — credenciais de usuário

`V30__create_user_credentials.sql`

Adicionou armazenamento separado de credenciais/hashes de senha para autenticação real.

### V31 — personas de usuário

`V31__create_user_personas.sql`

Criou `user_personas` com:

- persona declarativa;
- `is_primary`;
- unicidade por usuário/persona;
- índice único parcial para persona principal;
- backfill inicial do usuário legado quando localizado.

Persona não representa autorização.

### V32 — normalização de emails legados

`V32__normalize_user_emails.sql`

Normaliza emails existentes e impede regressão de representação incompatível, com guard explícito para colisões antes de alterar dados.

### V33 — canonicalização de emails

`V33__canonicalize_user_emails.sql`

Aperfeiçoa a canonicalização para coincidir com a política da aplicação, incluindo regras ASCII/collation-independent e constraints correspondentes.

### V34 — backfill de persona após canonicalização

`V34__backfill_legacy_user_persona_after_email_normalization.sql`

Repete com segurança o backfill do usuário legado depois da canonicalização de email, somente quando ele ainda não possui persona, evitando duplicação.

### V35 — papel explícito do Book Collaborator

`V35__add_book_collaborator_role.sql`

Fase expand da fundação de Book Roles (#205):

- `book_collaborators.role` não nulo, com catálogo fechado `AUTHOR`, `EDITOR`, `READER` e `LEGACY_COLLABORATOR`;
- backfill determinístico de toda linha legada para `LEGACY_COLLABORATOR`, preservando exatamente o acesso efetivo anterior sem inferir papel por persona, atividade, ownership ou email;
- default constante `LEGACY_COLLABORATOR` como caminho de compatibilidade de rollout, para que uma instância anterior à migration continue inserindo linhas utilizáveis e sem elevação; a #213 remove o default quando os novos grants forem role-aware;
- `book_collaboration_invitations.requested_role` passa a aceitar os papéis atribuíveis, mantendo convites `COLLABORATOR` já persistidos como estado legado auditável que nunca vira grant por inferência; a criação de novos convites fica fechada nesta fase, e a #213 a reabre com os papéis atribuíveis.
- constraints de catálogo adicionadas como `NOT VALID` e validadas em statement separado: o `ACCESS EXCLUSIVE` cobre apenas a mudança de catálogo, e a varredura das linhas existentes acontece sob `SHARE UPDATE EXCLUSIVE`, sem bloquear leituras e escritas concorrentes.

### V36 — Personal Book Writing Goal por User + Book

`V36__extract_personal_book_writing_goal.sql`

Separa a meta diária pessoal dos settings compartilhados do Book (#206):

- cria `book_personal_writing_goals`, único por `user_id + book_id`, com `daily_target_word_count` opcional e positivo quando presente;
- indexa `book_id` separadamente: a chave única lidera por `user_id`, que é o caminho de leitura da meta, mas a exclusão de um Book precisa encontrar as linhas apenas por `book_id`, e sem esse índice o cascade varreria a tabela inteira a cada Book removido;
- migra a antiga meta compartilhada para o Owner e para cada colaborador já existente do Book, sem criar meta para Users sem relação com o livro;
- valores legados não positivos são tratados como ausência de meta, não como zero;
- preserva o histórico de `book_daily_writing_progress`, que já carrega o snapshot da meta vigente no respectivo dia, e não reescreve `book_writing_schedules`, que já eram User + Book scoped;
- remove `books.daily_target_word_count`, eliminando do schema o antigo estado compartilhado que o novo contrato não reconhece;
- congela explicitamente `books` e `book_collaborators` em `share row exclusive` antes de ler qualquer um dos dois. Os dois decidem o resultado do backfill — o valor migrado e o conjunto de Users que o recebe — e nenhum dos dois lock é implícito o bastante para ser deixado subentendido: o lock de `books` coincide com o que a FK já pediria, mas o de `book_collaborators` não é implicado por nada, porque um grant toma `row exclusive` em `book_collaborators` e apenas `row share` em `books`, e `row share` não conflita com `share row exclusive`. `books` é travado primeiro para coincidir com a ordem que a própria aplicação usa ao conceder colaboração. Leitores não são bloqueados: só o `drop column` final os bloqueia, e apenas pela própria duração.

A remoção da coluna é deliberadamente incompatível com versões anteriores da aplicação: uma instância pré-V36 ainda seleciona `books.daily_target_word_count` e passa a falhar em toda leitura de Book assim que a migration roda. O deploy desta versão é, portanto, parada e substituição, não rolling — o que é adequado à topologia de backend único, mas precisa ser respeitado por qualquer ambiente que execute mais de uma instância.

## Estado atual

- migration head: **V36**;
- tenant e ownership de livro persistidos;
- colaboradores persistidos com Book Role explícito e revogável;
- metas diárias pessoais persistidas por User + Book em `book_personal_writing_goals`;
- convites seguros persistidos;
- auditoria de domínio e LLM persistidas;
- credenciais reais persistidas separadamente;
- personas declarativas persistidas;
- a #213 ainda removerá o default de compatibilidade `LEGACY_COLLABORATOR` e tornará novos grants role-aware, como cutover após as #206–#212 colocarem cada superfície atrás de capabilities;
- múltiplos workspaces e aceite completo de convites ainda exigirão migrations futuras conforme #146–#147.

## Checklist para nova migration

- [ ] usar a próxima versão disponível;
- [ ] não modificar migration publicada no `master`;
- [ ] definir invariantes antes do SQL;
- [ ] testar banco limpo quando aplicável;
- [ ] testar dados legados/snapshot anterior em migrations críticas;
- [ ] validar backfills determinísticos;
- [ ] nomear constraints e índices explicitamente;
- [ ] verificar `ON DELETE` e cascades;
- [ ] verificar isolamento entre tenants;
- [ ] verificar concorrência e unicidade;
- [ ] documentar correção/recuperação operacional quando uma down migration não for adequada;
- [ ] executar suíte relevante e `git diff --check`.