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

## Fora de escopo desta slice

Tratado em slices futuras da #80 (80C+):

- Publicação das imagens em GHCR ou qualquer outro registry.
- Assinatura/proveniência de imagens (ex.: Cosign, SLSA, attestations).
- Release workflow, promoção dev/staging/prod e deploy.
- Branch protection (issue #89) e política completa de release.
