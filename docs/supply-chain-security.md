# Política de Supply Chain Security

Relacionado à issue #80. Esta é a política vigente a partir da Slice 80A
(governança de dependências e hardening do GitHub Actions).

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

## Fora de escopo desta slice

Tratado em slices futuras da #80:

- Scanner de imagens Docker (ex.: Trivy).
- Geração de SBOM.
- Assinatura/proveniência de imagens.
- Publicação em GHCR e digest pinning de imagens base.
- Política completa de release e branch protection (issue #89).
