# IWrite Wiki — fonte versionada

Esta pasta é a fonte versionada da documentação arquitetural, histórica e de estado do IWrite.

Ela é revisada junto com o código e deve refletir o `master`, sem depender de memória, contas externas ou anotações informais.

## Estado desta sincronização

Atualizada em **22 de agosto de 2026** para refletir o produto após as PRs até #159 e a limpeza administrativa pós-disciplina.

A versão anterior desta wiki havia sido criada na PR #53 e permanecido congelada no estado da PR #52. Essa defasagem foi corrigida nesta sincronização.

## Páginas

- [Home](Home.md)
- [Roadmap e estado atual](Roadmap.md)
- [Diário de desenvolvimento](Development-Log.md)
- [Arquitetura](Architecture.md)
- [Decisões arquiteturais](Architectural-Decisions.md)
- [Migrations e evolução do banco](Database-Migrations.md)
- [Qualidade, testes e processo de revisão](Quality-and-Review.md)

## Escopo

A wiki documenta:

- capacidades existentes no `master`;
- arquitetura e invariantes atuais;
- decisões já tomadas;
- migrations publicadas;
- evolução por marcos/PRs;
- backlog técnico representado por Issues canônicas;
- processo de qualidade e revisão.

Ela não deve:

- marcar feature como concluída apenas porque existe uma issue fechada sem código correspondente;
- manter como “pendente” uma entrega comprovadamente presente no `master`;
- tratar infraestrutura acadêmica encerrada como dependência operacional atual;
- duplicar roadmap estratégico não versionado como se fosse decisão de implementação.

## Processo de atualização

Após um merge com impacto arquitetural/produto:

1. atualizar `Development-Log.md` com o marco;
2. atualizar `Roadmap.md` se o estado de issues/fases mudou;
3. registrar nova ADR quando houver decisão duradoura;
4. atualizar `Database-Migrations.md` quando houver nova migration relevante;
5. atualizar `Architecture.md` quando fronteiras/fluxos mudarem;
6. revisar README principal e documentação técnica relacionada;
7. manter issues canônicas sem duplicatas ou escopos obsoletos.

Atualizações devem ser feitas por PR/marco verificável, não por preenchimento artificial de dias sem mudança relevante.

## GitHub Wiki e `docs/wiki/`

`docs/wiki/` é a fonte principal porque:

- fica no mesmo histórico do código;
- recebe review em PR;
- permite comparar docs e implementação no mesmo commit;
- não depende de sincronização manual para preservar o conteúdo.

Se a aba Wiki do GitHub for usada, ela deve ser tratada como publicação/espelho desta pasta, nunca como fonte concorrente.

## Histórico acadêmico

O IWrite surgiu e foi avaliado na disciplina DSC/UFPB. O material acadêmico continua preservado em:

- `README-ENTREGA-DSC.md`;
- `docs/entrega/`;
- `docs/evidencias/`;
- guias/documentos acadêmicos específicos.

Esse material é histórico e auditável. Hosts, credenciais, Website IDs, buckets e contas institucionais não fazem parte do baseline atual do produto.

As tecnologias que foram implementadas nesse período — OpenTelemetry, Grafana, Tempo, Loki, Mimir, Umami, MCP, k6, auditoria e healthcheck — permanecem no produto quando tecnicamente úteis; apenas a dependência institucional foi encerrada.