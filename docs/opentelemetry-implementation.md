# OpenTelemetry no IWrite

## Estado operacional

O backend do IWrite inclui o **OpenTelemetry Java Agent** para auto-instrumentação de HTTP, JDBC/PostgreSQL, métricas da JVM e logs, com exportação por OTLP.

A instrumentação é uma capacidade atual do produto e **não depende da infraestrutura da antiga disciplina**. Há dois modos suportados:

1. **LGTM local**, via `docker-compose.observability.yml`, para desenvolvimento e diagnóstico;
2. **backend OTLP externo/gerenciado**, escolhido e configurado por ambiente.

O repositório não define um endpoint remoto oficial de produção. URL, autenticação, retenção e custos pertencem à configuração do ambiente operacional escolhido.

## Agente

- versão: `2.30.0`;
- SHA-256 validado no build: `9d6bc2ad8dd8fb7f730984988e57b8ac0a82d81c7b3b8ae795378718733a509d`;
- runtime: `/app/otel/opentelemetry-javaagent.jar`;
- inicialização: `docker/start.sh` anexa `-javaagent` somente ao processo Java.

O Next.js não é instrumentado pelo agente Java.

## Desabilitado por padrão

Com `IWRITE_OTEL_ENABLED=false` ou ausente:

- o agente não é carregado;
- variáveis `OTEL_*` não são exigidas;
- a aplicação continua funcionando sem backend de observabilidade.

## Habilitação

Com `IWRITE_OTEL_ENABLED=true`, o startup exige:

| Variável | Obrigatória | Exemplo |
|---|---|---|
| `OTEL_SERVICE_NAME` | sim | `iwrite-backend` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | sim | `https://otel.example.com` |
| `OTEL_EXPORTER_OTLP_HEADERS` | quando `IWRITE_OTEL_AUTH_REQUIRED=true` | `Authorization=Bearer <TOKEN>` |

`IWRITE_OTEL_ENABLED` e `IWRITE_OTEL_AUTH_REQUIRED` aceitam somente `true` ou `false`.

Quando OTel está habilitado e `IWRITE_OTEL_AUTH_REQUIRED` não foi explicitado, o default é `true`. Isso evita conectar acidentalmente a um endpoint remoto que deveria exigir autenticação. O override LGTM local define `false` explicitamente.

O startup nunca imprime valores de headers/tokens em mensagens de erro.

Defaults aplicados quando não sobrescritos:

```env
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=otlp
OTEL_LOGS_EXPORTER=otlp
```

## LGTM local

O arquivo `docker-compose.observability.yml` sobe Grafana + Tempo + Loki + Mimir/Prometheus localmente e configura:

```env
IWRITE_OTEL_ENABLED=true
IWRITE_OTEL_AUTH_REQUIRED=false
OTEL_SERVICE_NAME=iwrite-backend
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-lgtm:4318
```

Comando:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up --build
```

Grafana: `http://localhost:3001`.

A imagem LGTM permanece fixada por versão e digest:

```text
grafana/otel-lgtm:0.30.0@sha256:46ca028e294bd728e8e930a28e887f640a8f2a9533cc283f79bcc6ab73d2ffd8
```

Esse compose é somente para desenvolvimento/diagnóstico; não define a topologia de produção.

## Backend OTLP externo

Um ambiente real pode apontar para qualquer backend compatível com OTLP. Exemplo com placeholders:

```env
IWRITE_OTEL_ENABLED=true
IWRITE_OTEL_AUTH_REQUIRED=true
OTEL_SERVICE_NAME=iwrite-backend
OTEL_EXPORTER_OTLP_ENDPOINT=https://otel.example.com
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer <TOKEN>
```

Endpoint, token e demais headers devem vir de secret/configuração externa. Nenhum domínio institucional ou credencial histórica é requisito do produto.

## Segurança e privacidade

- tokens e headers ficam fora do Git;
- `docker/start.sh` não ecoa `OTEL_EXPORTER_OTLP_HEADERS`;
- `docker-compose.observability.yml` não contém credenciais;
- `db.statement` permanece sanitizado, com valores literais substituídos por placeholders;
- parâmetros JDBC vinculados não são capturados como conteúdo;
- não habilitar captura irrestrita de MDC ou argumentos de log;
- conteúdo de manuscrito, prompts, respostas de IA, cookies e secrets não devem entrar em spans/logs.

O override local explicita:

```env
OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED=true
OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES=true
```

## Verificação do startup

```bash
docker run --rm iwrite-otel-test /app/start.sh --check
docker run --rm -e IWRITE_OTEL_ENABLED=true iwrite-otel-test /app/start.sh --check
sh docker/start.test.sh
```

O segundo comando deve falhar citando a primeira configuração obrigatória ausente, sem revelar valores sensíveis.

## Diagnóstico local

Depois de subir o stack local e gerar tráfego autenticado, consulte os sinais pelo Grafana ou pelas APIs internas do container LGTM.

### Traces

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:3200/api/search?tags=service.name%3Diwrite-backend' | head -c 2000
```

Um trace de `GET /api/books` deve conter spans HTTP e JDBC correlacionados.

### Métricas JVM

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes%7Bservice_name%3D%22iwrite-backend%22%7D' | head -c 2000
```

### Logs

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s -G 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={service_name="iwrite-backend"}' \
  --data-urlencode 'limit=50' | head -c 3000
```

## Sinais de negócio e logs correlacionados

- spans/métricas manuais: `docs/otel-business-signals.md`;
- logs estruturados/correlação: `docs/otel-correlated-logs.md`;
- observabilidade operacional futura: issue #90;
- monitoramento/alertas: issue #93.

## Limitações conhecidas

- o download do agente no build depende do GitHub Releases, embora o SHA-256 fixe o conteúdo esperado;
- o agente adiciona aproximadamente 24 MB à imagem mesmo quando desabilitado;
- os guards de configuração vivem em `docker/start.sh`; executar o `.jar` diretamente ignora essas verificações;
- produção ainda precisa definir backend, retenção, dashboards permanentes, alertas e orçamento operacional.

## Histórico acadêmico

A implementação foi originalmente validada durante a disciplina DSC/UFPB contra uma infraestrutura institucional compartilhada e com o service name histórico `dsc-eq22`.

Esses endpoints, tokens e identificadores permanecem apenas nos documentos de entrega/evidência histórica. O guia conceitual `docs/opentelemetry.md` também é material original da disciplina e não deve ser interpretado como configuração de produção atual.
