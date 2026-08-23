# Decisões arquiteturais

Este documento registra decisões consolidadas no `master`. As ADRs iniciais foram preservadas e novas decisões foram adicionadas após as fases de colaboração, autenticação, IA e observabilidade.

## ADR-001 — Backend como fonte de verdade para word count

O frontend envia conteúdo, mas a contagem oficial é calculada no backend.

**Motivo:** impedir divergência entre clientes e manter ledger, dashboard e exportações coerentes.

## ADR-002 — TipTap isolado do fluxo de persistência

O editor rico permanece encapsulado. O estado salvo é associado à cena ativa e callbacks antigos são invalidados na troca de cena.

**Motivo:** evitar que autosaves atrasados gravem conteúdo na cena errada.

## ADR-003 — Histórico imutável de cenas

Scene versions são snapshots imutáveis, com hash para deduplicação e source explícito.

**Motivo:** restauração auditável e proteção contra perda de conteúdo.

## ADR-004 — `contentRevision` para concorrência otimista

Atualizações de conteúdo e restaurações devem informar a revisão esperada.

**Motivo:** impedir sobrescrita silenciosa de uma versão mais recente.

## ADR-005 — Ledger separado do daily progress

O ledger registra eventos; daily progress é um agregado de escrita por usuário, livro e data.

**Motivo:** manter auditabilidade sem recalcular toda a história para cada dashboard.

## ADR-006 — Produtividade não é igual à variação líquida do manuscrito

O sistema separa palavras produtivas, ajustes do manuscrito e total atual do manuscrito.

**Motivo:** deleções e restaurações não devem transformar produtividade em números enganosos.

## ADR-007 — Idempotência com chave e fingerprint

`operationId` identifica uma tentativa lógica. Um fingerprint representa o conteúdo semântico da requisição.

**Regra:** retry idêntico reaproveita o resultado; reutilização da chave com payload diferente gera conflito.

## ADR-008 — Lock pessimista no agregado do livro

Mutações que afetam a contagem total usam lock do livro e uma ordem estável de locks.

**Motivo:** impedir lost updates e reduzir risco de deadlock.

## ADR-009 — Datas de progresso são históricas

`progressDate` é derivada do timezone efetivo no momento do registro e permanece armazenada como fato histórico. Mudanças posteriores de timezone não reinterpretam registros antigos.

## ADR-010 — Datas futuras relativas ao timezone atual são excluídas de métricas correntes

Ao mover o timezone para oeste, uma data persistida pode ficar depois do novo “hoje”. Ela continua no banco, mas não pode inflar streaks ou séries correntes.

## ADR-011 — Tenant isolation com 404 equivalente

Recursos de outro tenant e recursos inexistentes usam a mesma semântica pública.

**Motivo:** reduzir vazamento de existência por UUID.

## ADR-012 — Métricas compartilhadas e pessoais são contratos distintos

O dashboard separa estado compartilhado do manuscrito, `myWriting` pessoal e contribuições registradas.

**Motivo:** impedir que métricas de um usuário sejam apresentadas como estado coletivo do livro.

## ADR-013 — Atividade registrada independe do saldo líquido final

Um livro continua aparecendo nas contribuições quando houve atividade real, mesmo que deltas positivos e negativos resultem em saldo líquido zero.

## ADR-014 — Migrations críticas são testadas a partir da versão anterior

O teste deve migrar até uma versão anterior relevante, inserir dados legados representativos, aplicar a nova migration e validar dados, constraints e índices.

## ADR-015 — Reviews devem procurar falhas semânticas, não apenas falhas de teste

Timezone, stale state, idempotência, sessão, concorrência e isolamento já produziram bugs que passavam em verificações superficiais. Reviews adversariais devem procurar invariantes quebradas e falsos verdes.

## ADR-016 — Livro possui owner explícito e colaboração é contextual

`Book` possui proprietário explícito e `BookCollaborator` representa acesso adicional ao livro.

**Motivo:** tenant membership por si só não deve significar acesso a todos os livros de um workspace.

O modelo atual ainda será refinado pela #145 para papéis/capabilities granulares.

## ADR-017 — Sessão de servidor e tenant resolvido por membership persistida

A autenticação usa Spring Security e sessão de servidor. O tenant efetivo é derivado de membership válida no backend.

**Regras:**

- o navegador não escolhe `tenantId` arbitrariamente;
- cookie/token não carrega autorização autossuficiente para o domínio;
- membership revogada deve retirar acesso;
- sessão e caches do frontend precisam ser reconciliados em troca de identidade.

**Motivo:** manter identidade e autorização revogáveis e reduzir risco de cross-tenant access.

## ADR-018 — Persona do usuário não é autorização

`UserPersona` descreve como o usuário se apresenta (`WRITER`, `EDITOR`, `REVIEWER`, `BETA_READER` etc.), mas não concede acesso a workspace, livro ou operação.

**Motivo:** a mesma pessoa pode exercer papéis diferentes em livros diferentes.

Papéis efetivos serão contextuais e centralizados na #145.

## ADR-019 — Convites persistem somente hash do token

Convites de colaboração usam token aleatório de alta entropia. O token bruto é devolvido apenas quando necessário ao fluxo; o banco persiste somente seu SHA-256.

**Motivo:** uma leitura do banco não deve permitir reutilizar links de convite ativos.

Lifecycle, unicidade, expiração e concorrência também são protegidos por constraints/índices do PostgreSQL.

## ADR-020 — Cadastro público cria conta e workspace pessoal de forma transacional

O registro cria User, UserCredential, Tenant pessoal, TenantMembership OWNER e persona principal dentro de uma fronteira transacional, e estabelece a sessão pelo mesmo mecanismo de autenticação do login.

**Motivo:** evitar contas parcialmente provisionadas e contratos divergentes entre cadastro e login.

## ADR-021 — Execuções de IA passam por gateway auditável e provider-neutral

Features de IA executam através de `LlmExecutionGateway`; adapters específicos implementam OpenAI/Anthropic sem contaminar o domínio.

O gateway controla status, erro categorizado, latência, token usage e custo opcional.

**Privacidade:** manuscrito, prompt completo, resposta completa, credenciais e mensagem sensível de exceção não são persistidos na auditoria.

## ADR-022 — Telemetria usa OpenTelemetry e vocabulário controlado

A instrumentação automática é fornecida pelo OpenTelemetry Java Agent; spans/métricas manuais representam operações de negócio.

Atributos de negócio usam chaves e valores allowlisted/normalizados.

**Motivo:** evitar um segundo SDK em produção, reduzir cardinalidade e impedir que texto livre/credenciais sejam exportados por acidente.

## ADR-023 — Logs estruturados são correlacionados sem Throwable em erros tratados

Eventos operacionais carregam metadados estruturados, trace ID e categorias controladas. Erros esperados/tratados não exportam stack trace completo para Loki.

**Motivo:** minimização de dados e redução do risco de vazar conteúdo privado em exceções. A divergência literal da antiga rubrica acadêmica permanece documentada historicamente.

## ADR-024 — Analytics de produto é opcional e sanitizado

Umami é carregado apenas quando configurado. URLs são sanitizadas e eventos/propriedades usam allowlist.

**Motivo:** analytics não deve bloquear o produto nem receber manuscrito, título privado, email, UUID bruto ou query string sensível.

## ADR-025 — MCP permanece camada fina sobre services existentes

Tools/resources MCP reutilizam services e autorização existentes em vez de duplicar consultas/regras de negócio.

Enquanto o transporte não possuir autenticação individual adequada, a configuração suportada é explicitamente habilitada e limitada a loopback no modo de identidade fixa.

## ADR-026 — Monólito modular continua sendo o baseline

Backend Spring Boot + frontend Next.js + PostgreSQL permanecem a arquitetura principal.

**Motivo:** o domínio ainda não possui escala ou independência operacional que justifique microserviços. Separar serviços agora aumentaria custo de consistência, observabilidade e deploy sem resolver uma dor confirmada.

## ADR-027 — Infraestrutura acadêmica é histórico, não dependência arquitetural

Grafana, Umami, OpenTelemetry, MCP, k6, auditoria e healthcheck permanecem porque são capacidades implementadas. Contas, hosts, buckets e credenciais da disciplina não fazem parte do baseline futuro.

**Regra:** cada integração deve ser configurável/substituível por ambiente e secrets próprios.

## Decisões ainda abertas

- matriz final de papéis/capabilities por livro (#145);
- modelo de seleção de múltiplos workspaces (#146);
- aceite completo e entrega de convites (#147/#57);
- provider/topologia de object storage (#68);
- estratégia de jobs/outbox/retries (#115);
- topologia e SLOs de produção (#90/#93/#95);
- modelo de planos/billing (#73/#74).