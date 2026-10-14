# Autenticação e resolução de tenant (issues #135, #136 e #133)

Notas técnicas de `com.iwrite.auth`, `com.iwrite.user.context` e `web/src/features/auth`.

## Fluxo

```
cookie de sessão (HttpOnly, SameSite=Lax)
        │
        ▼
SecurityContext restaurado por HttpSessionSecurityContextRepository
        │
        ▼
IWriteUserDetails  (userId, tenantId, role — capturados no login)
        │
        ▼
AuthenticatedCurrentUserProvider  ──►  TenantMembership relida no banco a cada requisição
        │
        ▼
CurrentUserProvider.userId() / tenantId() / effectiveZoneId()
        │
        ▼
services e repositories já existentes, com o filtro por tenant que sempre tiveram
        │
        ▼
recurso de outro tenant → 404
```

Nenhuma etapa consulta o cliente. O usuário vem do `Authentication` guardado na sessão e o tenant
vem da linha de `tenant_memberships` que o sustenta.

## Invariantes

### O `tenantId` do navegador nunca é autoritativo

`AuthenticatedCurrentUserProvider` lê o principal do `SecurityContextHolder` e resolve o tenant pela
membership persistida. Corpo, query string e cabeçalhos não participam da decisão: uma requisição
que envie o `tenantId` de outro workspace continua resolvendo o tenant do próprio autor. Aceitar o
valor do cliente transformaria o isolamento multi-tenant em uma convenção do frontend — quem
controla o navegador controlaria o tenant.

Coberto por `AuthenticatedTenantResolutionIntegrationTest`, nos três vetores (corpo, query param e
cabeçalho), mais o caso em que `userId` e `role` são enviados na requisição.

### Recurso de outro tenant responde 404

A autorização centralizada (`BookAccessService`, `CurrentUserMembershipService`) já filtrava por
tenant; esta fatia apenas passou a alimentá-la com a identidade autenticada. Um livro de outro
tenant e um UUID que nunca existiu produzem a mesma resposta — mesmo status e mesmo corpo, exceto
pelo id que o próprio cliente enviou de volta. Um `403` distinguiria "existe, mas não é seu" de
"não existe" e permitiria enumerar recursos alheios.

### Exatamente uma membership por usuário

`AuthSessionService.loadUserByUsername` recusa o login quando o usuário não tem exatamente uma
membership ativa. Zero e várias falham, ambas com a mesma mensagem genérica de credencial inválida.

Escolher silenciosamente um workspace entre vários tornaria a seleção de tenant implícita e
dependente de ordenação de linha. Enquanto não existe seletor de workspace, recusar é a única opção
que não inventa um contexto pelo usuário.

### Revogação de membership

O principal carrega `userId` e `tenantId`, mas eles são chave de busca, não autorização permanente.
Toda requisição relê a membership:

- `GET /api/auth/me` responde `401` e **invalida a sessão**;
- requisições protegidas respondem `401` antes de qualquer consulta com escopo de tenant;
- o usuário precisa autenticar novamente.

Excluir o usuário ou o tenant tem o mesmo efeito: ambos cascateiam para `tenant_memberships`, então
uma única leitura cobre os três casos.

Fora de `/api/auth/me` a sessão é apenas recusada, não destruída — ela passa a responder `401` em
todas as chamadas seguintes e expira sozinha. Destruir a sessão dentro de um caminho de leitura de
domínio seria um efeito colateral em lugar impróprio.

Observação de esquema: `books.owner` é chave estrangeira para `tenant_memberships`
(`fk_books_owner_tenant_membership`). A membership de quem possui manuscritos não pode ser
removida sem antes tratar os livros — o banco impede.

### Identidade de desenvolvimento e identidade autenticada

| | `DevelopmentCurrentUserProvider` | `AuthenticatedCurrentUserProvider` |
|---|---|---|
| Origem da identidade | ids fixos de configuração | sessão autenticada |
| Registrado quando | `iwrite.current-user.development.enabled=true` | a mesma propriedade é `false` ou ausente |
| Escopo do bean | singleton | por requisição |
| Valida membership | não | sim, a cada requisição |

As duas condições são complementares, então existe **exatamente um** `CurrentUserProvider` em
qualquer perfil. A escolha não depende de `@Primary` nem de ordem de registro de beans — depende de
uma única chave de configuração, e o padrão (propriedade ausente) é o provedor autenticado.

O perfil de demonstração mantém `IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=false`, portanto o provedor
de desenvolvimento não é sequer instanciado.

## Frontend (#133)

### Proxy de mesma origem

O navegador só conversa com a origem que serviu a página. O Next reescreve `/api/*` para
`BACKEND_ORIGIN` no servidor (`web/next.config.mjs`), então nenhuma URL de backend vai para o bundle.

```
navegador ──/api/...──► Next (3000) ──rewrite──► backend (8085)
```

Isso não é cosmético. Um cookie `SameSite=Lax` **não** é enviado em XHR cross-site, e o
double-submit de CSRF precisa ler um cookie que o navegador trataria como de terceiros. Falar com
outra porta funcionava para leitura anônima e não funciona para sessão.

O backend expõe seu probe público em `/ping`, fora de `/api`, então existe uma regra explícita
`/api/ping → BACKEND_ORIGIN/ping`, antes da regra coringa. Ela dá ao proxy uma verificação que não
depende de sessão.

`NEXT_PUBLIC_API_URL` era a variável documentada e implantada antes de `BACKEND_ORIGIN` existir;
`next.config.mjs` ainda a lê, mas só como *fallback* quando `BACKEND_ORIGIN` está ausente, e ela é
**legada/depreciada** — configuração nova deve usar `BACKEND_ORIGIN`. Se qualquer uma das duas
estiver definida com um valor que não seja uma URL absoluta válida, o build falha alto em vez de
cair silenciosamente no padrão local (`http://127.0.0.1:8085`): um erro de digitação em produção não
pode virar todo request reescrevendo silenciosamente para o próprio container do frontend.

**Cuidado com CORS.** O rewrite é server-side, mas o Next repassa o header `Origin` do navegador.
O backend continua aplicando `app.cors.allowed-origins` (padrão `http://localhost:3000`) e responde
`403 Invalid CORS request` quando a origem do frontend não está na lista. Um teste com `curl` não
percebe isso, porque `curl` não envia `Origin`. Ao servir o frontend em outra porta, ajuste
`APP_CORS_ALLOWED_ORIGINS`.

### Ciclo de sessão

- `apiRequest` usa caminhos relativos, envia o cookie e anexa `X-XSRF-TOKEN` em todo método que não
  seja `GET`, buscando `/api/auth/csrf` antes quando ainda não há cookie — é isso que torna possível
  a primeira mutação, o próprio login;
- a sessão é **uma** query TanStack (`["auth","session"]`), então `/api/auth/me` é pedido uma vez
  para toda a aplicação;
- não estar logado resolve para `null`, não para erro: manter isso distinto de uma falha é o que
  permite diferenciar "entre novamente" de "não conseguimos acessar o IWrite agora";
- um `401` em qualquer requisição encerra a sessão em um único handler nas caches de query e
  mutation, e o guard redireciona uma vez — nenhuma tela trata 401 por conta própria;
- o logout limpa a cache inteira. Tudo que estava nela foi buscado como aquele usuário naquele
  tenant.

### Rotas

| Rota | Comportamento |
|---|---|
| `/login` | Pública. Quem já tem sessão é levado para `/library`. |
| `/library` | Biblioteca. Antes era `/`. |
| `/` | Redireciona para `/library`. |
| demais | Protegidas pelo `SessionGuard`. |

`SessionGuard` envolve a aplicação a partir do layout raiz, então uma rota nova nasce protegida em
vez de depender de alguém lembrar. **Ele é conveniência, não autorização** — o backend recusa
requisição sem sessão por conta própria; o guard só evita uma tela cheia de falhas. Ter tido sessão
e perdê-la vira `?reason=expired`; nunca ter tido vira a tela de login normal.

### O que o navegador nunca guarda

Nada de sessão em `localStorage` ou `sessionStorage`. A identidade vive no cookie `JSESSIONID`
(`HttpOnly`, `SameSite=Lax`), inacessível ao JavaScript. O payload de `/api/auth/login` e
`/api/auth/me` não carrega `userId` nem `tenantId`, então o cliente não tem identificador para
devolver como afirmação de identidade — mesmo que a requisição traga um, o servidor resolve tudo
pela membership.

## Provisionamento de credencial (rollout e desenvolvimento)

`V30__create_user_credentials.sql` cria a tabela `user_credentials` vazia: nenhum usuário existente
(incluindo o usuário legado determinístico da V20) ganha uma credencial automaticamente. Sem uma delas,
`/api/auth/login` não tem o que verificar, e como não há cadastro público nem endpoint administrativo,
uma instalação que já existia antes da V30 fica sem nenhuma conta capaz de autenticar.

`CredentialProvisioningRunner` (`com.iwrite.auth`) resolve isso com um mecanismo único, usado tanto no
rollout de uma instalação existente quanto no fluxo local de desenvolvimento:

- desligado por padrão (`iwrite.auth.credential-provisioning.enabled`, `IWRITE_CREDENTIAL_PROVISIONING_ENABLED`);
- exige email e senha do usuário (`IWRITE_CREDENTIAL_PROVISIONING_EMAIL`/`_PASSWORD`), sem padrão — falha
  claramente no boot se o flag estiver ligado e faltar um dos dois;
- só provisiona a senha de um usuário que **já existe**; falha claramente no boot se o email não
  corresponder a nenhum `users.email` — nunca cria um usuário;
- armazena somente o hash (`PasswordEncoder` já configurado, `{bcrypt}`); nunca loga o email, a senha
  ou o hash;
- idempotente: se o usuário já tem credencial, não sobrescreve e não falha;
- não existe endpoint HTTP equivalente — é exclusivamente um `ApplicationRunner` de boot, então só
  quem controla as variáveis de ambiente do processo pode disparar o provisionamento.

Depois de provisionar, remova as três variáveis do ambiente: elas não têm efeito quando a credencial
já existe (o runner é idempotente), mas deixar a senha configurada no processo não tem motivo.

### Rotação de credencial legada (`replace-existing`)

Antes desta fatia, este runner hasheava qualquer senha configurada sem o limite efetivo do bcrypt (72
bytes UTF-8) nem a checagem de UTF-16 malformado que `/api/auth/login` passou a exigir depois
(`BcryptInputPolicy`). Uma instalação que provisionou uma credencial antes dessa mudança pode ter uma
senha armazenada que o login atual recusa de cara — sem um endpoint de redefinição de senha, essa
conta ficaria permanentemente sem acesso.

`iwrite.auth.credential-provisioning.replace-existing` (`IWRITE_CREDENTIAL_PROVISIONING_REPLACE_EXISTING`,
padrão `false`) é a válvula de escape:

- **sem credencial**: comportamento inalterado — valida `BcryptInputPolicy` e cria normalmente;
- **com credencial e `replace-existing=false`** (padrão): preserva o hash existente; a senha
  configurada **não é validada nem hasheada**, já que não seria usada — uma senha antiga insegura
  configurada por engano não bloqueia o boot;
- **com credencial e `replace-existing=true`**: exige uma senha nova bem-formada e de até 72 bytes
  (mesma `BcryptInputPolicy`), substitui o hash **na mesma linha**, na mesma transação — nunca cria
  uma linha nova, nunca toca `User`/`Tenant`/`TenantMembership`/`UserPersona` ou qualquer livro. Senha
  e hash nunca são logados, só a confirmação de que a rotação ocorreu.

Nunca reabilita login com senha acima de 72 bytes: bcrypt não distingue sufixos além desse limite, e
aceitar temporariamente reabriria a mesma colisão de prefixo que a política atual fecha.

Procedimento de upgrade para uma conta presa atrás de uma senha legada grande demais:

1. configure `IWRITE_CREDENTIAL_PROVISIONING_EMAIL` com o email da conta;
2. escolha uma senha nova segura de até 72 bytes UTF-8;
3. suba a aplicação uma vez com `IWRITE_CREDENTIAL_PROVISIONING_ENABLED=true`,
   `IWRITE_CREDENTIAL_PROVISIONING_REPLACE_EXISTING=true` e as três variáveis preenchidas;
4. confirme que a nova senha autentica em `/api/auth/login`;
5. remova todas as variáveis (`_ENABLED`, `_EMAIL`, `_PASSWORD`, `_REPLACE_EXISTING`).

No fluxo local (perfil `development`), o alvo natural é o usuário legado da V20
(`carlos.legacy@iwrite.local`, id `00000000-0000-0000-0000-000000000002`), que também é o id padrão de
`IWRITE_DEVELOPMENT_CURRENT_USER_ID`. Depois de logar com a credencial provisionada, o
`DevelopmentCurrentUserProvider` continua resolvendo o mesmo tenant/usuário fixo para as chamadas de
negócio, independentemente de qual conta autenticou a sessão — só a barreira do Spring Security
precisava de uma sessão real, a identidade de negócio em desenvolvimento já era fixa.

## Limitação de tentativas de login

`POST /api/auth/login` é público (necessariamente — é como a sessão começa), então nada além do
próprio `LoginRateLimiter` (`com.iwrite.auth`) impede tentativas ilimitadas. Duas dimensões
independentes, cada uma com janela fixa e contagem em memória:

- **origem** (`request.getRemoteAddr()` — nunca `X-Forwarded-For`, que qualquer cliente forja
  livremente; atrás de um proxy confiável, resolva o peer real via
  `server.forward-headers-strategy=framework`, não neste código);
- **conta** (email normalizado — minúsculas, sem espaço nas pontas), para impedir força bruta
  concentrada numa conta específica ainda que distribuída entre muitas origens.

Toda chamada conta, sucesso ou falha (mesma política do `McpSceneAnalysisLimiter`). Ao exceder
qualquer uma das duas, a resposta é sempre `429` com a mesma mensagem genérica
(`AuthMessages.TOO_MANY_LOGIN_ATTEMPTS`) — nunca revela qual dimensão estourou nem se a conta existe.

É uma janela, não um bloqueio permanente: ela sempre reabre sozinha, então ninguém consegue manter a
conta de outra pessoa fechada só enviando o email dela em tentativas com falha por mais que uma
janela — parar de tentar já é suficiente para a conta voltar a responder normalmente na janela
seguinte.

Armazenamento limitado (`iwrite.auth.login-rate-limit.max-tracked-keys`, padrão 10000): ao admitir
uma chave nova, cada dimensão descarta só janelas efetivamente expiradas — uma janela ainda ativa
nunca é removida apenas porque o mapa está cheio, nem para abrir espaço para uma chave nunca vista.
Se, mesmo após descartar as expiradas, o mapa continuar no limite, a chave nova é recusada
(fail-closed, o mesmo `429` de uma chave já esgotada) em vez de expulsar alguma janela ativa — assim
um ataque distribuído com muitos emails ou origens descartáveis não consegue apagar o orçamento
esgotado da vítima antes do fim da janela dela. Consultar ou incrementar uma chave já existente nunca
passa por essa decisão; existência, poda e inserção de uma chave nova formam uma única decisão atômica
por dimensão, sob um lock de admissão próprio de cada mapa (nunca compartilhado entre origem e conta).

Estado em memória por instância. Implantação com múltiplas réplicas precisa de armazenamento
compartilhado (ex.: Redis) para um limite único combinado; sem isso, cada réplica aplica sua própria
janela independente.

Variáveis: `IWRITE_LOGIN_RATE_LIMIT_MAX_PER_ORIGIN` (padrão 20),
`IWRITE_LOGIN_RATE_LIMIT_MAX_PER_ACCOUNT` (padrão 8), `IWRITE_LOGIN_RATE_LIMIT_MAX_TRACKED_KEYS`
(padrão 10000), `IWRITE_LOGIN_RATE_LIMIT_WINDOW` (padrão `1m`).

Testado deterministicamente com relógio injetável (`LoginRateLimiterTest`, sem `sleep`) e fim a fim
via `MockMvc` (`LoginRateLimitingIntegrationTest`).

## Limitações desta fatia acadêmica

- **Seleção de workspace adiada.** Usuários com mais de uma membership não conseguem entrar. O
  seletor de tenant está fora do escopo de #136 e permanece na visão completa da #63.
- **Sessões em memória.** A sessão vive no servlet container; reiniciar o backend derruba todas as
  sessões e exige novo login. Não há armazenamento externo de sessão.
- Cadastro público existe desde a #143 — ver "Cadastro público (#143)" abaixo. Ainda não há
  recuperação de senha, verificação de email ou SSO — ver #63.
- **Sem seed de demonstração** nesta fatia; é a #137. O `docker-compose.yml` já roda com
  `IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=false`, porque com a identidade fixa ligada todo usuário
  autenticado cairia no mesmo workspace. A consequência é que, até a #137, subir o compose não dá
  nenhuma conta com a qual entrar além das criadas pelo próprio cadastro — as credenciais de
  demonstração são criadas lá.
- A tela de login oferece cadastro (`/register`, #143), mas ainda não recuperação de senha nem
  login social, porque nada disso existe no backend. Botão que não faz nada é pior que ausência.

## Cadastro público (#143)

`POST /api/auth/register` cria o workspace pessoal completo em uma única transação
(`RegistrationService`, `com.iwrite.auth`) e devolve o mesmo contrato de `/api/auth/login` e
`/api/auth/me` — a sessão já sai autenticada, sem exigir um segundo login.

Entrada: `displayName`, `email`, `password`, `passwordConfirmation`, `primaryPersona`
(`WRITER`, `EDITOR`, `REVIEWER`, `BETA_READER` ou `OTHER`) e `timeZone` (IANA, detectado pelo
navegador com fallback seguro no frontend). Sem username: o login continua por email.

Ordem da transação, tudo ou nada:

1. normaliza e valida o formato do email (mesma normalização do login, `EmailNormalizer` —
   `trim` + minúsculas; nenhum dos dois duplica essa regra);
2. valida a política de senha e a confirmação;
3. valida a persona e o fuso horário;
4. verifica duplicidade (pré-checagem rápida antes de qualquer escrita) e cria `User` via
   `saveAndFlush`, capturando `DataIntegrityViolationException` da constraint `uk_users_email`
   para o caso de corrida concorrente — os dois casos (sequencial e concorrente) respondem `409`
   com a mesma mensagem estável, nunca `500`;
5. cria `UserCredential` (hash adaptativo, mesmo `PasswordEncoder` do login);
6. cria o `Tenant` pessoal e a `TenantMembership` `OWNER`;
7. registra a `UserPersona` principal.

Qualquer falha em qualquer etapa reverte a transação inteira; nenhuma entidade parcial sobrevive.
Depois de persistir, o controller reautentica com a própria credencial recém-criada pelo mesmo
`AuthenticationManager` que `/api/auth/login` usa (`AuthController#register` reaproveita
`establishSession`, extraído de `login`) — a sessão resultante é indistinguível de uma sessão de
login, não uma cópia construída à mão. `tenantId`, `userId` e `role` enviados pelo cliente nunca são
lidos; tudo é decidido pelo servidor.

### Persona

`user_personas` (`V31__create_user_personas.sql`): `user_id`, `persona`, `is_primary`,
`created_at`, `updated_at`; único em `(user_id, persona)` e um índice único parcial garante no
máximo uma persona principal por usuário. A migration faz backfill do usuário legado
(`carlos.legacy@iwrite.local`, localizado por email, não por id fixo) como `WRITER` principal.

Persona é puramente declarativa: personaliza o produto, nunca autoriza nada. Nenhuma consulta de
autorização existente ou futura deve inspecionar `user_personas` — a fundação foi desenhada para
suportar múltiplas personas por usuário mais tarde sem remodelagem destrutiva, mas esta fatia grava
só a principal, no cadastro.

### Política de senha

Aplicada em `PasswordPolicy` (`com.iwrite.auth`), única fonte de verdade: no mínimo 10 code points,
no máximo `MAX_UTF8_BYTES` = 72 bytes UTF-8, com ao menos uma letra e um dígito (por code point,
`Character.isLetter(int)`/`isDigit(int)`, não os overloads `char`). O frontend replica a mesma
checagem apenas como conveniência (`[...password].length`, `\p{L}`/`\p{Nd}`, `TextEncoder` para os
bytes) — o backend nunca confia nela. `passwordConfirmation` é comparada e descartada; nunca chega à
persistência nem é logada.

O limite de 72 bytes existe porque o `DelegatingPasswordEncoder` usa bcrypt, que ignora silenciosamente
qualquer byte além do 72º — sem o limite, duas senhas diferentes que compartilhem o mesmo prefixo de
72 bytes hashariam de forma idêntica. Recusar de vez (nunca truncar) fecha essa colisão pela raiz.
Aplicado em qualquer caminho que chame `passwordEncoder.encode` diretamente, inclusive
`CredentialProvisioningRunner` (que valida só o limite de bytes, não a política completa — a senha
provisionada por um operador nunca precisou de letra+dígito).

### Política de email: ASCII apenas

`EmailNormalizer.normalize` (trim + `toLowerCase(Locale.ROOT)`) e o `lower()` do PostgreSQL não são
garantidamente equivalentes para todo code point Unicode — só concordam sempre dentro do intervalo
ASCII (`U+0000`–`U+007F`). Em vez de reimplementar suporte parcial a EAI (email internacionalizado)
nesta fatia, o email de conta é restrito a ASCII de ponta a ponta: `EmailNormalizer.isAscii`,
verificado antes de qualquer lookup, chamada a bcrypt ou escrita, em `RegistrationService` e em
`CredentialProvisioningRunner`. Só o email — `displayName` e senha continuam aceitando Unicode
completo.

No banco, `V32`/`V33` (ver abaixo) abortam a migração inteira, antes de qualquer `UPDATE`, se
encontrarem um email legado não-ASCII — nunca escolhem uma conversão silenciosa para uma linha que
pode perder informação. `V33` também adiciona `chk_users_email_ascii`, fechando o mesmo espaço no
nível de banco (nenhuma escrita futura, aplicação ou SQL manual, pode reintroduzir um email
não-ASCII).

### Limitação de tentativas de cadastro

`RegistrationRateLimiter` tem orçamento próprio, nunca o do `LoginRateLimiter` — os dois agora
compartilham a mesma engine de janela fixa (`FixedWindowRateLimiter`, extraída do que antes vivia
só dentro de `LoginRateLimiter`), mas cada um com seu próprio estado e sua própria configuração.
Só a dimensão de origem: um cadastro sempre mira um email nunca visto, então uma dimensão de conta
não agregaria proteção — abuso distribuído por muitos emails diferentes da mesma origem já é pego
pela dimensão de origem. A checagem roda antes do bcrypt e das quatro escritas da transação.

Variáveis: `IWRITE_REGISTRATION_RATE_LIMIT_MAX_PER_ORIGIN` (padrão 10),
`IWRITE_REGISTRATION_RATE_LIMIT_MAX_TRACKED_KEYS` (padrão 10000),
`IWRITE_REGISTRATION_RATE_LIMIT_WINDOW` (padrão `1m`). Excedido, responde `429` com
`RegistrationMessages.TOO_MANY_REGISTRATION_ATTEMPTS` — mensagem própria, nunca a de login.

### Atribuição de propriedade da sessão em corridas

Um `session.getId()` que mudou não diz, sozinho, *qual* fluxo mudou — um login concorrente na mesma
sessão (a mesma aba/navegador) rotaciona o id exatamente da mesma forma que o próprio cadastro
rotacionaria. Comparar apenas ids faria o rollback de um cadastro que falhou depois de um login
concorrente invalidar a sessão do login por engano.

`AuthController#establishSession` (compartilhado por `login` e `register`) grava um `UUID` próprio
daquela chamada em `SESSION_OWNER_TOKEN_ATTRIBUTE` — um atributo de sessão do lado do servidor,
nunca serializado em nenhuma resposta — antes da mutação (se já existir uma sessão) e de novo depois
de `saveContext` (que pode ter criado ou rotacionado a sessão que o primeiro registro atingiu). O
rollback do cadastro (`discardPartialSession`) só invalida a sessão quando *ambos* são verdade: o id
mudou desde antes do cadastro **e** o marcador atual ainda é o token daquele cadastro — um login
concorrente que tenha assumido a sessão depois falha a segunda checagem, preservando-o. No commit,
o marcador é removido só se ainda pertencer àquele cadastro (`releaseSessionOwnerIfStillOwned`);
`login` faz o mesmo logo após estabelecer sua própria sessão, já que nada depois precisa mais dele.

Coberto por `RegistrationSessionOwnershipRaceIntegrationTest` (dois pontos de injeção deterministas,
por latch, chaveados pelo email do cadastro em corrida, para não afetar a chamada do login
concorrente) e, para o caso sem corrida, por
`RegistrationWhileAuthenticatedIntegrationTest#falhaAposMutacaoRealDaSessaoContinuaInvalidandoASessaoParcial`.

### Frontend

`/register` (`web/src/app/register/page.tsx` + `RegisterForm`) segue o mesmo estilo visual de
`/login`. Ao concluir, `useRegister` (`features/auth/session.ts`) executa exatamente a mesma
sequência de `useLogin` — cancela queries em voo, purga caches autenticados, avança a geração de
reconciliação, grava a sessão confirmada pelo servidor e só então navega para `/library` — via um
helper (`applyNewSession`) compartilhado entre os dois hooks. `SessionGuard` trata `/register` como
trata `/login`: renderiza sem esperar sessão, e quem já está autenticado é redirecionado para
`/library` em vez de permanecer na tela.

## Isolamento do banco de testes entre worktrees

`TestDatabaseInitializer` executa `DROP DATABASE ... WITH (FORCE)` no início da suíte. Worktrees
paralelos que apontem para o mesmo Postgres destroem a base de teste ativa uns dos outros no meio da
execução, produzindo falhas que parecem aleatórias.

Use uma instância dedicada por worktree e passe `TEST_DB_URL` explicitamente:

```bash
docker run -d --name iwrite-auth-testdb -p 5439:5432 \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres postgres:16

TEST_DB_URL=jdbc:postgresql://localhost:5439/iwrite_auth_test ./mvnw test
```

Sem `TEST_DB_URL` a suíte cai silenciosamente no padrão `localhost:5435/iwrite_test`, que é
compartilhado. O container temporário não está no `docker-compose.yml`: é ferramenta de
desenvolvimento local, não configuração do projeto.
