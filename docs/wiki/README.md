# IWrite Wiki — fonte versionada

Esta pasta é a fonte oficial da documentação histórica e arquitetural do IWrite.

A documentação foi reconstruída retrospectivamente a partir do histórico de commits, pull requests, migrations, testes e código existente. Ela não tenta inventar um diário diário anterior: os registros antigos são organizados por marcos verificáveis.

## Escopo deste primeiro bootstrap

O conteúdo representa o projeto até a PR #52, que encerrou a fase B7d-a de dashboards e semântica de progresso de escrita.

A fase C1 de ownership, colaboradores e autorização por livro ainda estava em implementação e não é descrita como concluída nesta versão.

## Páginas

- [Home](Home.md)
- [Roadmap e estado atual](Roadmap.md)
- [Diário de desenvolvimento](Development-Log.md)
- [Arquitetura](Architecture.md)
- [Decisões arquiteturais](Architectural-Decisions.md)
- [Migrations e evolução do banco](Database-Migrations.md)
- [Qualidade, testes e processo de revisão](Quality-and-Review.md)

## Processo de atualização

A documentação deve ser atualizada por marco ou PR mergeada, e não por quantidade de dias trabalhados.

Após cada fase relevante:

1. atualizar o diário de desenvolvimento;
2. mover a fase no roadmap;
3. registrar decisões arquiteturais novas;
4. atualizar migrations, contratos e comandos de validação quando necessário.

## GitHub Wiki

Os arquivos em `docs/wiki/` são mantidos no repositório principal para permitir revisão por PR, histórico de diff e sincronização com o código.

A aba Wiki do GitHub pode ser atualizada posteriormente a partir destes arquivos. O repositório principal continua sendo a fonte de verdade.