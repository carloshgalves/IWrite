# Decisões arquiteturais

Este documento registra decisões consolidadas até a PR #52.

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

O sistema separa:

- palavras produtivas;
- ajustes do manuscrito;
- total atual do manuscrito.

Deleções e restaurações não devem transformar produtividade em números enganosos.

## ADR-007 — Idempotência com chave e fingerprint

`operationId` identifica uma tentativa lógica. Um fingerprint representa o conteúdo semântico da requisição.

**Regra:** retry idêntico reaproveita o resultado; reutilização da chave com payload diferente gera conflito.

## ADR-008 — Lock pessimista no agregado do livro

Mutações que afetam a contagem total usam lock do livro e uma ordem estável de locks.

**Motivo:** impedir lost updates e reduzir risco de deadlock.

## ADR-009 — Datas de progresso são históricas

`progressDate` é derivada do timezone efetivo no momento do registro e permanece armazenada como fato histórico.

Mudanças posteriores de timezone não reinterpretam registros antigos.

## ADR-010 — Datas futuras relativas ao timezone atual são excluídas de métricas correntes

Ao mover o timezone para oeste, uma data persistida pode ficar depois do novo “hoje”. Ela continua no banco, mas não pode inflar streaks ou séries correntes.

## ADR-011 — Tenant isolation com 404 equivalente

Recursos de outro tenant e recursos inexistentes usam a mesma semântica pública.

**Motivo:** reduzir vazamento de existência por UUID.

## ADR-012 — Métricas compartilhadas e pessoais são contratos distintos

O dashboard do livro usa:

```text
shared manuscript metrics
myWriting.progress
myWriting.schedule
recorded contributions
```

**Motivo:** impedir que métricas de um usuário sejam apresentadas como estado coletivo do livro.

## ADR-013 — Atividade registrada independe do saldo líquido final

Um livro continua aparecendo nas contribuições quando houve linhas não zero, mesmo que `+100` e `-100` resultem em saldo líquido zero.

**Motivo:** manter coerência entre dias de escrita, livros escritos e detalhamento por livro.

## ADR-014 — Migrations críticas são testadas a partir da versão anterior

Não basta inspecionar o schema já migrado pelo Spring. O teste deve:

1. migrar até a versão anterior;
2. inserir dados legados representativos;
3. aplicar a nova migration;
4. validar dados, constraints e índices.

## ADR-015 — Reviews devem procurar falhas semânticas, não apenas falhas de teste

Timezone, saldo líquido, stale state, idempotência e isolamento já produziram bugs que passavam em verificações superficiais.

**Processo adotado:** uma auditoria pré-commit, correções em lote, um review geral da PR e no máximo um re-review focado quando a correção altera produção.

## Decisões futuras ainda abertas

- ownership explícito do livro;
- modelo de colaboração;
- convite por e-mail;
- criação/entrada de usuários em tenants;
- activity events gerais;
- autenticação de produção.