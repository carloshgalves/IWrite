# Qualidade, testes e processo de revisão

## Objetivo

O processo de qualidade do IWrite precisa encontrar dois tipos de problema:

1. falhas diretamente observáveis por testes;
2. falhas semânticas difíceis de perceber, como vazamento entre tenants, retries incompatíveis, session races, migrations inseguras, stale state e telemetria que expõe dados.

A cobertura automatizada é um gate importante, mas não substitui revisão de invariantes.

## CI atual

`.github/workflows/ci.yml` executa em PRs e pushes relevantes e contém dois jobs principais.

### Backend

- PostgreSQL 16 como service;
- teste do entrypoint OpenTelemetry;
- Java 21;
- suíte Maven backend.

### Frontend

- Node 20;
- `npm ci`;
- `npm test`;
- build de produção;
- upload do artifact de build.

`npm test` executa Vitest com cobertura e o projeto possui threshold de **linhas ≥85%**. Na consolidação da PR #159 foram registrados:

- frontend: **87,16% de linhas**, 375 testes / 41 arquivos;
- backend: **92,01% de linhas**, 841 testes após o healthcheck.

Esses números representam aquele marco; o gate automatizado é a garantia contínua do frontend.

## E2E

`.github/workflows/e2e.yml` possui execução manual e agendada com:

- Java/Node configurados;
- `npm ci`;
- Chromium/Playwright;
- senhas demo aleatórias e efêmeras por execução;
- stack Docker E2E isolada;
- readiness de backend/frontend;
- artifacts de falha;
- cleanup com remoção do volume do ambiente de teste.

E2E cobre fluxos reais de autenticação, sessão e manuscrito. Nem toda feature precisa de E2E, mas fronteiras críticas de identidade, cache, concorrência e colaboração devem receber cenários ponta a ponta quando o risco justificar.

## Camadas de validação

### Testes focados

Executados durante a implementação para reduzir o ciclo de feedback.

Exemplos:

- service/integration test da feature;
- migration test da versão nova;
- controller/MockMvc do contrato alterado;
- frontend test do componente afetado;
- teste de concorrência determinístico quando houver race relevante;
- teste de privacidade sobre logs/telemetria quando a superfície exporta dados.

### Backend local

Suba o PostgreSQL:

```bash
docker compose up -d --wait db
```

Linux/macOS:

```bash
chmod +x ./mvnw
./mvnw -s .mvn/local-settings.xml clean test jacoco:report
```

Windows:

```cmd
mvnw.cmd -s .mvn\local-settings.xml clean test jacoco:report
```

### Frontend local

```bash
cd web
npm ci
npm test
npm run build
```

`npm run lint` também é usado em validações de PRs recentes quando relevante, embora ainda não seja um gate separado no workflow `ci.yml` atual.

### Higiene do diff

```bash
git diff --check
git status --short
git diff --stat
```

Warnings de LF/CRLF da working copy não equivalem automaticamente a erro de whitespace.

## PostgreSQL real

Testes de:

- Flyway;
- constraints;
- FKs compostas;
- locks;
- concorrência;
- SQL específico;
- índices;
- rollback;
- comportamento de canonicalização/backfill;

não devem depender apenas de banco em memória.

Migrations críticas devem testar explicitamente um estado anterior relevante com dados legados.

## Concorrência e idempotência

O repositório já encontrou classes de bugs que não aparecem em testes puramente sequenciais. Casos relevantes devem preferir sincronização determinística por latch/barreira em vez de `sleep` como mecanismo principal.

Exemplos já cobertos em diferentes fases:

- autosave/troca de cena;
- ledger e `operationId`;
- criação concorrente de convites;
- rate limiting;
- cadastro concorrente;
- ownership de sessão durante cadastro/login concorrentes.

## Segurança e isolamento

Mudanças em autenticação, colaboração, MCP, IA ou acesso a recursos devem considerar ao menos:

- usuário autorizado;
- usuário sem acesso no mesmo tenant;
- outro tenant;
- recurso inexistente;
- recurso revogado;
- ID conhecido enviado pelo cliente;
- ausência/expiração da sessão.

Quando aplicável, acesso negado deve permanecer indistinguível de recurso inexistente.

## Privacidade em observabilidade e analytics

Testes devem impedir que superfícies operacionais exportem:

- manuscrito;
- prompt/resposta completos;
- títulos privados quando não necessários;
- email/IDs brutos em analytics;
- senha;
- cookie/token;
- API key;
- stack trace de erro tratado quando a política atual o proíbe.

OpenTelemetry, logs estruturados, Umami e MCP possuem testes específicos de sanitização/allowlist.

## Performance

`loadtest/carga.js` é o cenário k6 autenticado atual. Ele modela sessões independentes, recursos próprios por VU, leitura/escrita/autosave e cleanup.

Resultados de load test são evidência contextual, não promessa de capacidade de produção. Sempre registrar hardware/topologia, VUs, thresholds, limitações e versão/blob do script medido.

## Estratégia de review

### Antes do merge

Review deve procurar, além de testes vermelhos:

- bypass de autorização;
- enumeração de recurso;
- migration incompleta;
- constraint ausente;
- falha de concorrência;
- retry não idempotente;
- session/cache race;
- N+1 ou query problemática em caminho crítico;
- contrato HTTP incorreto;
- teste falso-verde;
- vazamento de dado em log/trace/analytics;
- acoplamento acidental a provider/ambiente;
- escopo não relacionado.

### Correções

Findings reais devem ser agrupados e corrigidos com testes de regressão que provem o defeito sempre que possível.

### Pull request

A descrição da PR deve registrar:

- objetivo e escopo;
- invariantes/decisões relevantes;
- migrations e compatibilidade;
- validações executadas;
- findings corrigidos;
- riscos conhecidos;
- itens explicitamente fora de escopo.

## Critérios de severidade

### Blocker

- corrupção/perda de dados;
- acesso cross-tenant/cross-book;
- migration que pode perder dados ou impedir startup sem caminho seguro;
- segredo exposto;
- fluxo principal inutilizável.

### High

- autorização contornável;
- lost update;
- retry que duplica efeito;
- race de sessão que troca/destrói identidade indevidamente;
- constraint essencial apenas na aplicação;
- vazamento relevante em telemetria.

### Medium

- contrato inconsistente;
- caso relevante sem teste;
- performance problemática em uso normal;
- estado de UI incorreto ao navegar/trocar identidade;
- documentação que leva a configuração operacional incorreta.

### Low

- melhoria concreta de baixo risco sem impacto relevante em segurança, integridade ou UX principal.

## Findings reais que moldaram o processo

- autosave antigo atuando sobre outra cena;
- stale contributor após troca de livro;
- timezone transformando registro histórico em data futura relativa;
- atividade com saldo líquido zero desaparecendo;
- idempotência exigindo fingerprint além da chave;
- CORS quebrando a imagem combinada;
- capacidade/races em rate limiter;
- sessão compartilhada entre abas exigindo reconciliação e geração de cache/mutations;
- cadastro concorrente expondo races de ownership da sessão;
- MCP e chat model criando ciclo de dependência;
- telemetria exigindo vocabulário fechado para não exportar valores configurados brutos;
- healthcheck superficial não verificando PostgreSQL.

## Governança restante

A issue #89 acompanha a parte administrativa ainda pendente: branch protection, checks obrigatórios e política de merge/release. A existência do pipeline em si já está consolidada.