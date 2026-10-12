# Política de Supply Chain Security

Relacionado à issue #80. Esta é a política vigente a partir da Slice 80B
(governança de dependências, hardening do GitHub Actions e agora também
hardening de containers, image scanning e SBOM).

## GitHub Actions

- Toda action de terceiros referenciada em `.github/workflows/*.yml` deve
  usar **SHA de commit completo e imutável**, nunca uma tag móvel
  (`@v4`) ou branch.
- O SHA vem sempre acompanhado de um comentário com a versão/tag
  correspondente, por exemplo: `uses: actions/checkout@<SHA> # v4.4.0`.
- Atualizações de SHA de actions são feitas via Dependabot
  (`package-ecosystem: github-actions`) e passam pelo CI antes do merge.

## Dependabot

- Dependabot (`.github/dependabot.yml`) é o mecanismo padrão de
  atualização de dependências para Maven (`/`), npm (`/web`) e GitHub
  Actions (`/`), com verificação semanal.
- PRs de atualização **nunca são mergeadas sem o CI passar**.
- Auto-merge não está habilitado: toda atualização passa por revisão
  humana antes do merge.

## Vulnerabilidades

- O workflow `dependency-review.yml` analisa, em cada PR para `master`,
  apenas as dependências introduzidas ou alteradas pela própria PR.
- Vulnerabilidade de severidade **CRITICAL** nas dependências
  adicionadas/alteradas bloqueia o merge, salvo aceite explícito e
  documentado (issue ou comentário na PR justificando a exceção).
- Vulnerabilidades **HIGH** não bloqueiam automaticamente, mas devem ser
  avaliadas e priorizadas pelo time em até um ciclo de sprint.
- Vulnerabilidades pré-existentes e não tocadas pela PR não são
  transformadas em bloqueio imprevisível dessa PR — são tratadas
  separadamente via Dependabot/backlog.
- Qualquer exceção a uma vulnerabilidade CRITICAL ou HIGH precisa de
  justificativa registrada e prazo definido para revisão/remediação.

## Segredos

- Relatórios de dependency review, logs de CI e artifacts de build não
  devem conter segredos. Segredos de teste (ex.: senhas efêmeras de E2E)
  são gerados em runtime e mascarados (`::add-mask::`), nunca versionados
  ou expostos em log plano.
- O mesmo vale para os artifacts de segurança de containers (SBOMs e
  relatórios de scan, ver abaixo): eles descrevem componentes técnicos da
  imagem (pacotes de SO, dependências de linguagem, CVEs), nunca segredos,
  `.env`, tokens/credenciais ou conteúdo de usuário.

## Containers (Slice 80B)

- Imagens base operacionais (usadas em `Dockerfile`, `web/Dockerfile`,
  `docker-compose.yml`, `docker-compose.e2e.yml`,
  `docker-compose.llm-stub.yml` e no service container de PostgreSQL do
  CI) são fixadas por **tag + digest imutável**
  (`imagem:tag@sha256:<digest>`): a tag mantém a legibilidade, o digest é
  a identidade imutável que impede troca silenciosa da imagem. O digest
  fixado é sempre o do manifest/index multi-arch da tag, não de uma
  plataforma específica.
- `grafana/otel-lgtm` (observabilidade) já seguia esse padrão antes da
  80B e permanece inalterado, salvo necessidade real comprovada.
- Dependabot (`package-ecosystem: docker` para `/` e `/web`, e
  `docker-compose` para `/`) mantém essas imagens atualizáveis — o
  digest pinning não as transforma em dependências congeladas
  manualmente para sempre.
- O workflow `container-security.yml` builda a imagem principal
  (`Dockerfile`) e a imagem do frontend (`web/Dockerfile`) localmente
  (sem publicar em nenhum registry) e escaneia as duas com Trivy em
  cada PR relevante para `master`, em execução manual e semanalmente
  (para detectar CVEs publicadas após o merge).
- Vulnerabilidade **CRITICAL** na imagem (com ou sem correção
  disponível) falha o gate correspondente; não é silenciada com
  `continue-on-error` nem allowlist. Exceção segue a mesma regra da
  seção anterior: justificativa registrada e prazo de revisão.
- Vulnerabilidades **HIGH** aparecem no relatório para avaliação, sem
  bloquear automaticamente o merge, seguindo a mesma política definida
  para dependências.
- Para cada imagem construída, o workflow gera um SBOM CycloneDX JSON
  representando a imagem final (não apenas `pom.xml`/`package-lock.json`)
  e retém, como artifact de CI com retenção limitada (14 dias), tanto o
  SBOM quanto o relatório de vulnerabilidades (JSON) da execução.

## Exceções de vulnerabilidade documentadas

- Exceções ao gate CRITICAL de `container-security.yml` são registradas em
  `.trivyignore.yaml` (versionado, na raiz do repositório), nunca como
  supressão silenciosa: cada entrada tem `id` (CVE), `statement` com a
  justificativa e `expired_at` com o prazo de revisão. Passado o prazo, a
  entrada deixa de valer e a CVE volta a bloquear o gate.
- `.trivyignore.yaml` só é aplicado ao step de gate do workflow, nunca ao
  relatório JSON — toda vulnerabilidade ignorada continua aparecendo no
  relatório completo (artifact), só não derruba o job.
- Exceções vigentes (revisão em 2026-11-22):
  - `CVE-2026-22732` (spring-security-web): o advisory oficial do Spring
    cobre toda a linha 6.4.0–6.4.14 (inclui o 6.4.13 já adotado nesta
    slice); o fix 6.4.15 é Enterprise-only, e o fix OSS só existe a partir
    de 6.5.9 — fora da linha 6.4.x gerenciada pelo Spring Boot 3.4.1.
    Remover esta exceção exige um upgrade de linha do Spring
    Security/Spring Boot, ou uma mitigação específica, coordenados com a
    evolução de auth/session (PR #162 e sucessoras).
  - `CVE-2026-59873` (pacote `tar` embutido no npm que vem dentro da
    imagem `node:24-alpine`): não é dependência do IWrite; aguardando o
    upstream publicar uma imagem com npm corrigido.
- **CVE-2026-47884**, **CVE-2026-47890**, **CVE-2026-47892**
  (Spring Framework 6.2.15, revisão até **2026-11-12**, tracking **#254**):
  as dependências continuam em versão afetada, mas as condições de exploração
  descritas nos advisories não foram identificadas no código atual.
  A 47884 exige `XsltView` com rota catch-all que renderiza view sem nome
  explícito; a 47890 exige SSE com fragmentos de views; a 47892 exige endpoints
  funcionais WebFlux sob `DispatcherServlet`. O IWrite emprega controllers
  REST/Spring MVC; busca estática em 12/10/2026 não encontrou `XsltView`,
  `SseEmitter`, `ServerSentEvent`, `text/event-stream`,
  `RouterFunction` ou `RouterFunctions` no código de produção.
  A ausência de referências diretas não é uma prova formal de ausência de
  exploração indireta. Estas são **exceções temporárias de aplicabilidade**,
  não atualizações das bibliotecas nem alegação de CVEs corrigidas.
  Os três registros permanecem no relatório completo e SBOM do Trivy; apenas
  a política de bloqueio tem prazo de expiração. Reavaliar imediatamente se
  o produto passar a renderizar views, emitir SSE com fragmentos ou expor
  rotas funcionais WebFlux; caso contrário, revisar a dependência e o upgrade
  para uma linha Spring corrigida antes de 12/11/2026.
  Advisories: https://spring.io/security/cve-2026-47884/,
  https://spring.io/security/cve-2026-47890/,
  https://spring.io/security/cve-2026-47892/.
- `CVE-2025-41232` (spring-security-core) **não** é mais uma exceção:
  corrigida via `spring-security.version=6.4.13` no `pom.xml` (patch dentro
  da própria linha 6.4.x já gerenciada pelo Spring Boot 3.4.1). Esse patch
  torna `BCryptPasswordEncoder.encode()` estrito para senhas > 72 bytes;
  isso exigiu ajustar o fixture de teste legado de
  `com.iwrite.auth.CredentialProvisioningRunnerIntegrationTest` para semear
  um hash bcrypt precomputado em vez de hashear uma senha oversized ao
  vivo — nenhuma lógica de produção mudou (o login já rejeitava senha
  oversized antes de qualquer comparação de hash).

## Fora de escopo desta slice

Tratado em slices futuras da #80 (80C+):

- Publicação das imagens em GHCR ou qualquer outro registry.
- Assinatura/proveniência de imagens (ex.: Cosign, SLSA, attestations).
- Release workflow, promoção dev/staging/prod e deploy.
- Branch protection (issue #89) e política completa de release.
