# Roadmap e estado atual

Esta página descreve **o backlog versionado/rastreado no GitHub**, não substitui decisões estratégicas discutidas fora do repositório.

## Fundações concluídas

### Produto de escrita

- livros, seções, capítulos e cenas;
- workspace, outline, TipTap, autosave e focus mode;
- personagens, locais, itens e planejamento de cenas;
- storyboard e kanban;
- notebook;
- metas, streaks e dashboards;
- histórico/restauração de cenas;
- exportação TXT/Markdown/DOCX.

### Integridade e multi-tenancy

- tenant, usuário e timezone;
- isolamento progressivo dos domínios;
- ownership explícito do livro e colaboração base (#54);
- ledger de palavras com idempotência, fingerprint, locking e rollback;
- fundação segura de convites (#55).

### Identidade

- autenticação real e sessão multi-tenant (#63 / PR #139);
- login/logout/restauração e reconciliação entre abas;
- cadastro público com workspace pessoal (#143 / PR #149);
- fundação de `user_personas` e persona primária.

### IA e observabilidade

- análise opcional de cenas;
- OpenAI e Anthropic via Spring AI;
- auditoria LLM;
- OpenTelemetry automático;
- spans/métricas de negócio;
- logs estruturados correlacionados;
- Grafana, Tempo, Loki e Prometheus/Mimir local;
- Umami;
- MCP mínimo;
- baseline k6 autenticado;
- healthcheck database-aware;
- CI/E2E e gate de cobertura frontend.

## Frente ativa principal — identidade e colaboração

Roadmap canônica: #142.

Estado:

- [x] #143 — cadastro público e workspace pessoal;
- [ ] #144 — completar perfil e múltiplas personas;
- [ ] #145 — papéis/capabilities granulares por livro;
- [ ] #146 — múltiplos workspaces e troca segura;
- [ ] #147 — aceite de convites e biblioteca compartilhada;
- [ ] #148 — UX específica para editor, revisor e leitor beta;
- [ ] #57 — entrega/reenvio de convites por email, independente de provedor.

Issues antigas #56 e #58 foram absorvidas pela #147. A #64 foi substituída pela #145.

## Produto e experiência

- #65 — comentários, sugestões e resolução editorial;
- #66 — busca global;
- #67 — PDF e EPUB;
- #68 — capas/anexos em object storage S3-compatible;
- #69 — feed de atividade e activity streak;
- #70 — notificações;
- #71 — consistência contextual/RAG;
- #72 — realtime, somente após fundações assíncronas estarem maduras;
- #110 — importação DOCX/Markdown/TXT/Scrivener;
- #111 — offline e sincronização resiliente;
- #112 — onboarding/templates;
- #113 — séries, universos e cânone;
- #117 — acessibilidade, responsividade e mobile.

## Produto comercial

- #108 — posicionamento, ICP, concorrência e hipóteses de preço;
- #109 — marca definitiva;
- #114 — portabilidade, exclusão e LGPD;
- #73 — planos, limites e medição;
- #74 — billing;
- #115 — API/jobs/outbox/retries;
- #116 — console administrativo e suporte.

Epics de fase:

- #118 — Secure Beta;
- #119 — Public Beta;
- #120 — SaaS Foundation;
- #121 — Commercial Launch;
- #122 — Publishing Suite & Editorial Intelligence.

## Segurança, banco e operação

Epics transversais:

- #81 — Security;
- #88 — Database;
- #95 — Infra/Operação.

Parte da fundação já existe e as issues foram atualizadas para diferenciar **núcleo implementado** de **operação ainda pendente**. Em particular:

- #89 não precisa criar CI do zero; resta governança/branch protection;
- #90 não precisa implementar OTel/Grafana/Loki/Tempo/Mimir do zero; resta operação de produção;
- #93 não precisa implementar `/ping`; resta monitoramento externo e alertas.

## Critério de prioridade

1. segurança, integridade e prevenção de perda de texto;
2. completar identidade/colaboração necessária ao beta;
3. portabilidade e experiência de uso;
4. operação segura com usuários reais;
5. monetização após ativação/retensão serem validadas;
6. recursos avançados como realtime apenas quando houver necessidade comprovada.

## Definição de concluído

Uma entrega relevante só é considerada concluída quando, conforme o escopo:

- migrations aplicam em PostgreSQL real;
- testes focados passam;
- suíte backend relevante passa;
- frontend/testes/build passam quando aplicável;
- isolamento/autorização são testados em features sensíveis;
- `git diff --check` e higiene do diff estão limpos;
- findings reais de review são resolvidos;
- documentação de estado atual é atualizada após o merge.

## Observação sobre a disciplina

A infraestrutura e os entregáveis acadêmicos são históricos. Grafana, Umami, OpenTelemetry, MCP, k6, auditoria e healthcheck continuam no produto porque são capacidades implementadas; o que deixou de existir é a dependência de contas/servidores institucionais.