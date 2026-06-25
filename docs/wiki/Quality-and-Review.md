# Qualidade, testes e processo de revisão

## Objetivo

O processo de qualidade do IWrite precisa encontrar dois tipos de problema:

1. falhas diretamente observáveis por testes;
2. falhas semânticas difíceis de perceber, como vazamento entre tenants, retries incompatíveis, datas futuras após mudança de timezone e métricas inconsistentes.

## Camadas de validação

### Testes focados

Executados durante a implementação para reduzir o ciclo de feedback.

Exemplos:

- service integration test da feature;
- migration test da versão nova;
- controller/MockMvc do contrato alterado;
- frontend test do componente afetado.

### Suíte backend completa

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -s .mvn/local-settings.xml clean test
```

JDK 21 deve estar no `PATH`; definir apenas `JAVA_HOME` não é suficiente para o wrapper atual no Windows.

### Suíte frontend

```powershell
cd web
npm.cmd run test
npm.cmd run build
cd ..
```

### Higiene do diff

```powershell
git diff --check
git status --short
git diff --stat
```

Warnings de LF → CRLF na working copy do Windows não equivalem a erro de whitespace.

## PostgreSQL real

Testes de:

- Flyway;
- constraints;
- locks;
- concorrência;
- SQL específico;
- rollback;

não devem depender apenas de banco em memória.

## Estratégia de review

### Antes do commit

Uma auditoria independente, findings-first, sem editar.

Ela deve procurar:

- bypass de autorização;
- migration incompleta;
- constraints ausentes;
- falha de concorrência;
- N+1 relevante;
- contrato HTTP incorreto;
- teste falso-verde;
- escopo acidental.

### Correções

Todos os findings reais são agrupados e corrigidos em um único passe sempre que possível.

### Pull request

- um review geral;
- no máximo um re-review focado quando a correção alterou lógica de produção;
- não pedir novo review completo após cada mudança pequena.

## Critérios de severidade

### Blocker

- corrupção de dados;
- acesso cross-tenant/cross-book;
- migration que pode falhar ou perder dados;
- segredo exposto;
- fluxo principal inutilizável.

### High

- autorização contornável;
- lost update;
- retry que duplica evento;
- erro de timezone que altera métricas persistentes;
- constraint essencial apenas na aplicação.

### Medium

- contrato inconsistente;
- caso relevante sem teste;
- performance problemática em uso normal;
- estado de UI incorreto ao navegar.

### Low

- melhoria concreta de baixo risco sem impacto de segurança ou integridade.

## Exemplos de findings reais já encontrados

- estado de contribuidor permanecia ativo ao trocar de livro;
- mudança de timezone poderia transformar registros antigos em datas futuras e inflar o melhor streak;
- atividade com saldo líquido zero desaparecia do detalhamento;
- autosave antigo poderia atuar sobre outra cena;
- retries exigiram fingerprint, não apenas chave idempotente.

## Estado de lint

No snapshot documentado, `npm run lint` não era utilizável de forma não interativa porque o Next solicitava criação de configuração ESLint.

Isso é dívida de tooling, não motivo para alterar o escopo de uma feature de domínio.

## Registro de resultados

A descrição final da PR deve informar:

- número exato de testes backend;
- failures, errors e skipped;
- número de testes frontend;
- resultado do build;
- migration test executado;
- `git diff --check`;
- riscos conhecidos e itens fora de escopo.