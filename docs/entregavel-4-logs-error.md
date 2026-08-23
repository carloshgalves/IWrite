# Entregável 4 de logs — decisão arquitetural e divergência deliberada

Este documento registra de forma explícita como o IWrite trata o **item 4 dos entregáveis de logs** descrito no guia oficial da disciplina [`docs/opentelemetry-logs.md`](opentelemetry-logs.md).

## Requisito oficial

O guia oficial da disciplina pede, no item 4 da seção de entregáveis, que a equipe:

1. provoque um erro tratado;
2. registre esse erro em nível `ERROR` com `logger.error(...)`;
3. inclua a exceção/`Throwable` no evento de log;
4. localize esse evento no Loki.

O arquivo oficial é mantido no repositório **sem ser reescrito para refletir a implementação do IWrite**. A implementação específica do projeto está documentada separadamente em [`docs/otel-correlated-logs.md`](otel-correlated-logs.md).

## Estado no IWrite

**O item 4 não é reproduzido literalmente. Esta é uma divergência deliberada de arquitetura e segurança, não uma omissão acidental.**

No IWrite, erros tratados **não passam o `Throwable` ao logger**. Portanto, esses eventos não exportam `exception.message`, `throwableProxy` nem stack trace para o Loki.

O projeto registra apenas metadados sanitizados e de vocabulário fechado, por exemplo:

- `iwrite.error.type`: somente o nome simples da classe da exceção;
- `iwrite.error.category`: categoria pública sanitizada;
- `iwrite.result`: resultado controlado da operação;
- `trace_id` e `span_id`: injetados pelo OpenTelemetry Java Agent para correlação com o Tempo.

A regra está descrita em [`docs/otel-correlated-logs.md`](otel-correlated-logs.md), especialmente nas seções **Campos proibidos** e **Níveis**.

## Motivo da decisão

O IWrite manipula conteúdo de manuscrito, títulos privados, prompts, respostas de IA, identificadores e credenciais. Mensagens e stack traces de exceções podem carregar valores originados nessas superfícies ou revelar detalhes internos desnecessários.

Por isso, a política de logging adotada é de **minimização de dados**:

- exceção tratada e esperada: `WARN`, sem `Throwable`;
- falha interna inesperada: `ERROR`, mas ainda com evento estruturado sanitizado;
- mensagem da exceção: não exportada;
- stack trace de erro tratado: não exportado;
- conteúdo, IDs brutos, e-mail, token, header, cookie, prompt e resposta de IA: proibidos nos eventos estruturados.

Essa política também é coerente com a instrumentação manual de traces: [`docs/otel-business-signals.md`](otel-business-signals.md) registra que `recordException` não é utilizado porque anexaria `exception.message` e `exception.stacktrace` ao span sem sanitização.

## O que substitui a demonstração literal do item 4

Em vez de publicar um stack trace deliberadamente, a implementação demonstra:

1. eventos de negócio com severidade adequada (`INFO`, `WARN`, `ERROR`);
2. classificação explícita de falhas internas versus resultados tratados;
3. atributos de erro sanitizados (`iwrite.error.type` / `iwrite.error.category`);
4. correlação do evento com o trace através de `trace_id`;
5. pesquisa do evento no Loki por `service_name`, `scope_name`, operação, resultado e severidade;
6. testes que verificam que mensagem de exceção e stack trace não vazam.

A consulta abaixo pertence à **evidência acadêmica histórica** produzida quando o service name da disciplina ainda era `dsc-eq22`; ela é preservada como registro da validação feita naquela época:

```logql
{service_name="dsc-eq22"} | scope_name="com.iwrite.business.events" | severity_text=~"WARN|ERROR"
```

No runtime atual, a consulta equivalente deve usar a identidade operacional vigente:

```logql
{service_name="iwrite-backend"} | scope_name="com.iwrite.business.events" | severity_text=~"WARN|ERROR"
```

## Evidências de implementação

| Evidência | Onde verificar |
|---|---|
| Enunciado oficial do item 4 | [`docs/opentelemetry-logs.md`](opentelemetry-logs.md) |
| Política de campos proibidos | [`docs/otel-correlated-logs.md`](otel-correlated-logs.md) |
| Política de severidade | [`docs/otel-correlated-logs.md`](otel-correlated-logs.md) |
| Traces sem `recordException` | [`docs/otel-business-signals.md`](otel-business-signals.md) |
| Emissão dos eventos estruturados | `src/main/java/com/iwrite/observability/BusinessTelemetry.java` |
| Eventos/auditoria LLM | `src/main/java/com/iwrite/llm/gateway/LlmExecutionGateway.java` |
| Testes de privacidade dos logs | `src/test/java/com/iwrite/observability/StructuredLogEventsTest.java` |
| Testes do gateway de LLM | `src/test/java/com/iwrite/llm/gateway/LlmExecutionGatewayTest.java` |

`StructuredLogEventsTest` captura eventos Logback reais e inspeciona as superfícies do evento, incluindo mensagem, key-value pairs, MDC, argumentos e `throwableProxy`, para impedir vazamento de conteúdo privado, credenciais, mensagem de provider e stack trace de erro tratado.

## Classificação para avaliação

Para evitar ambiguidade em avaliação humana ou automatizada:

| Item do guia de logs | Estado no IWrite |
|---|---|
| 1. Log real no Loki filtrado por `service_name` | ✅ Implementado e demonstrado |
| 2. Log estruturado de negócio e filtro por campo | ✅ Implementado e demonstrado |
| 3. Correlação log ↔ trace com `trace_id` / Tempo | ✅ Implementado e demonstrado |
| 4. `logger.error(..., exception)` com stack trace de erro tratado | ⚠️ **Não reproduzido literalmente; substituído deliberadamente por logging estruturado sanitizado** |

Se a rubrica exigir de forma estritamente literal a presença de um `Throwable`/stack trace no Loki, então o item 4 deve ser considerado **não atendido literalmente**. O repositório não tenta mascarar esse fato.

A decisão foi manter a propriedade de segurança e privacidade da aplicação em vez de criar um caminho artificial que exportasse uma exceção apenas para produzir um print de avaliação.

## Conclusão

A ausência de stack trace de erro tratado no Loki é **comportamento intencional e testado**. Os demais requisitos de logs — ingestão no Loki, estruturação, severidade e correlação com Tempo — permanecem implementados e verificáveis. Esta divergência fica documentada aqui para que nenhum avaliador interprete a ausência de `logger.error(..., throwable)` como falha desconhecida ou requisito esquecido.
