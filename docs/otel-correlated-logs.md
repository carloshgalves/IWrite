# Logs estruturados correlacionados com traces

Este guia descreve a superfície **operacional atual** de logs estruturados do IWrite. A configuração local usa `OTEL_SERVICE_NAME=iwrite-backend`; identificadores `dsc-eq22` pertencem apenas às evidências acadêmicas históricas.

## Caminho do dado

```text
código (SLF4J 2: log.atInfo().addKeyValue(...))
  -> Logback
  -> instrumentação Logback do OpenTelemetry Java Agent
  -> OTLP/HTTP
  -> backend configurado (LGTM local ou OTLP externo)
```

No stack local, os logs chegam ao Loki e compartilham `trace_id`/`span_id` com os traces do Tempo.

## Por que não existe um segundo appender

O OpenTelemetry Java Agent já instrumenta o Logback. Adicionar outro `OpenTelemetryAppender` faria eventos serem exportados em duplicidade. O projeto mantém um único caminho de exportação e usa a API fluente do SLF4J 2 para atributos estruturados.

Exemplo conceitual:

```java
log.atInfo()
        .addKeyValue("otel.event.name", "iwrite.scene.content.save")
        .addKeyValue("iwrite.operation", "scene_content_save")
        .addKeyValue("iwrite.result", "success")
        .addKeyValue("iwrite.duration_ms", durationMs)
        .log("Business operation completed");
```

A mensagem permanece estável; consultas devem usar atributos estruturados, não parsing de texto.

## Configuração segura

O override `docker-compose.observability.yml` habilita:

```yaml
OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES: "true"
```

Deliberadamente **não** habilitamos captura irrestrita de MDC nem argumentos de log. Isso evita exportar texto livre, parâmetros do cliente ou conteúdo de manuscrito.

## Identificadores

`trace_id`/`span_id` vêm do OpenTelemetry e servem para navegar do log ao trace. `llmExecutionId` é um UUID de auditoria do gateway LLM e não substitui o trace distribuído.

Nenhum ID de usuário, tenant, livro ou cena é exportado como atributo de negócio de alta cardinalidade.

## Eventos atuais

### `iwrite.scene.content.save`

Fluxo: `PATCH /api/scenes/{sceneId}/content`.

Atributos controlados incluem:

- `iwrite.operation=scene_content_save`;
- `iwrite.result` dentro do vocabulário permitido (`success`, `no_change`, `idempotent_retry`, `conflict`, `validation_error`, `not_found`, `failure`);
- `iwrite.duration_ms`;
- `iwrite.scene.source`;
- bucket de tamanho de conteúdo;
- indicação booleana de alteração;
- tipo sanitizado de erro quando necessário.

### `iwrite.scene.analysis`

Representa análise assistida de cena. Pode registrar provider/model family categorizados, presença de foco, buckets de entrada, fallback e resultado. Prompt, resposta e foco livre nunca são exportados.

### `iwrite.mcp.invocation`

Só existe quando MCP está habilitado. Registra tool/resource type, resultado, duração e categoria de erro sanitizada. Argumentos, títulos, IDs do cliente e respostas não entram no log estruturado.

### `iwrite.llm.execution`

Registra metadados de execução LLM: feature, provider categorizado, model family, prompt version, status, categoria de erro, duração, tokens e fallback. API keys e identificadores de modelo não passam diretamente para telemetria sem normalização.

## Campos proibidos

Não registrar em mensagem, atributos estruturados, MDC exportado, argumentos ou throwable tratado:

- conteúdo de cena/manuscrito;
- título de livro/cena;
- prompt ou resposta da IA;
- `focus` livre;
- email, nome de usuário;
- tenant/user/book/scene IDs;
- API key, token, cookie ou header;
- URL com query sensível;
- mensagem de exceção não sanitizada.

## Severidade

- `INFO`: operação concluída conforme esperado;
- `WARN`: resultado conhecido/tratado, como conflito ou validação;
- `ERROR`: falha interna ou de infraestrutura inesperada.

Uma indisponibilidade esperada de provider não deve ser confundida com defeito interno, e uma falha de auditoria/configuração deve poder elevar a severidade.

## Consultas no LGTM local

Suba o stack:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

### Todos os logs do backend

```logql
{service_name="iwrite-backend"}
```

### Eventos de negócio

```logql
{service_name="iwrite-backend"} | iwrite_operation != ""
```

### Salvamentos com conflito

```logql
{service_name="iwrite-backend"} | iwrite_operation="scene_content_save" | iwrite_result="conflict"
```

### Falhas internas

```logql
{service_name="iwrite-backend"} | severity_text="ERROR"
```

No pipeline LGTM usado pelo projeto, `service_name` é label indexado; a severidade do log record é exposta como `severity_text`; atributos de log record chegam como structured metadata e nomes com pontos podem aparecer normalizados com underscores (`iwrite.result` -> `iwrite_result`).

## Correlação log -> trace

1. localize um evento no Loki;
2. leia o `trace_id` do log record;
3. abra esse trace no Tempo;
4. confira spans HTTP/JDBC e spans de negócio relacionados;
5. não use `llmExecutionId` como se fosse `trace_id`.

## Validação de privacidade

A verificação deve confirmar que os logs continuam úteis para operação sem incluir conteúdo ou credentials. Testes automatizados usam vocabulários fechados/canários para impedir regressões de privacidade.

## Relações

- configuração do agente e LGTM: `docs/opentelemetry-implementation.md`;
- spans/métricas de negócio: `docs/otel-business-signals.md`;
- MCP: `docs/mcp-server.md`;
- operação de observabilidade em produção: issue #90.

## Histórico acadêmico

As evidências da disciplina foram produzidas com o service name histórico `dsc-eq22` e continuam preservadas em `docs/entrega/`, relatórios e evidências. Essas consultas são registros de uma validação passada; para o runtime atual use `service_name="iwrite-backend"` como mostrado acima.
