# Analytics de produto — Umami

## Finalidade

O Umami mede **uso do produto**: page views e eventos de funcionalidades como criar livro, salvar cena, analisar cena e exportar. Ele responde "o que as pessoas usam", enquanto OpenTelemetry responde "como o sistema está se comportando".

As duas integrações são independentes. Desligar analytics não afeta observabilidade e vice-versa.

## Estado operacional atual

O IWrite **não depende de nenhuma instância institucional ou da infraestrutura da antiga disciplina**.

A integração é opcional e desabilitada por padrão. Para habilitá-la, escolha uma instância Umami própria ou serviço gerenciado e configure o frontend por ambiente.

`web/.env.local.example` não contém URL nem Website ID reais.

## Variáveis de ambiente

| Variável | Quando necessária | Descrição |
|---|---|---|
| `NEXT_PUBLIC_UMAMI_ENABLED` | sempre | `true` habilita; qualquer outro valor desabilita analytics. |
| `NEXT_PUBLIC_UMAMI_SCRIPT_URL` | quando habilitado | URL do `script.js` da instância escolhida. |
| `NEXT_PUBLIC_UMAMI_WEBSITE_ID` | quando habilitado | Website ID do cadastro do IWrite nessa instância. |
| `NEXT_PUBLIC_UMAMI_HOST_URL` | opcional | Endpoint de coleta quando diferente da origem do script. |

Exemplo local de configuração, usando placeholders:

```env
NEXT_PUBLIC_UMAMI_ENABLED=true
NEXT_PUBLIC_UMAMI_SCRIPT_URL=https://umami.example.com/script.js
NEXT_PUBLIC_UMAMI_WEBSITE_ID=<WEBSITE_ID>
# NEXT_PUBLIC_UMAMI_HOST_URL=https://umami.example.com
```

Sem `enabled=true`, URL e Website ID válidos, `getUmamiConfig()` retorna `null`: nenhum script é carregado e nenhuma chamada de analytics é feita. Falhas do tracker são fail-open e nunca devem bloquear o produto.

## Implementação

- `web/src/lib/analytics/analytics.ts` concentra configuração, injeção do script, sanitização, fila e allowlist de eventos;
- `web/src/lib/analytics/umami-analytics.tsx` registra page view inicial e navegações client-side;
- nenhum componente deve chamar `window.umami.track` diretamente fora da camada de analytics;
- page views e eventos usam URL sanitizada explícita: query string e hash são removidos, e segmentos que parecem IDs viram `{id}`;
- referrer interno é sanitizado; referrer externo é reduzido à origem;
- título da página não é enviado;
- a fila pré-carregamento é limitada a 10 itens e descarta o item mais antigo quando cheia.

## Eventos atuais

| Evento | Momento | Propriedades permitidas |
|---|---|---|
| `book_created` | criação confirmada pelo backend | — |
| `scene_saved` | conteúdo persistido | `source`: `AUTO_SAVE` ou `MANUAL_SAVE` |
| `scene_analysis_requested` | análise válida enviada | — |
| `scene_analysis_succeeded` | resposta de IA válida | — |
| `scene_analysis_failed` | falha exibida ao usuário | `category`: `unavailable` ou `request_failed` |
| `book_exported` | download concluído | `target`: `manuscript` ou `notebook`; `format`: `txt`, `md` ou `docx` |

## Proteção de dados

A allowlist em `analytics.ts` é a única fonte de eventos, propriedades e valores aceitos.

Nunca devem ser enviados:

- conteúdo de manuscrito;
- títulos de livros/cenas;
- emails ou nomes;
- IDs brutos de usuário, tenant, livro ou cena;
- prompts ou respostas de IA;
- tokens, cookies ou secrets;
- stack traces;
- query strings ou hashes.

## Testes

Os testes de analytics cobrem integração desabilitada, configuração válida/inválida, carregamento único do script, page views, deduplicação, eventos de sucesso/falha, allowlist de propriedades e ausência de conteúdo/IDs privados.

## Validação em um ambiente real

Ao configurar uma nova instância Umami:

1. configure `NEXT_PUBLIC_UMAMI_*` apenas no ambiente de build/deploy;
2. navegue por `/`, `/dashboard` e um livro;
3. confirme page views no website correto;
4. crie livro, salve cena e exporte manuscrito para validar os eventos;
5. confirme que rotas dinâmicas aparecem como `/books/{id}` e que nenhum dado privado foi enviado.

## Histórico acadêmico

Em 08/08/2026 a integração foi validada de ponta a ponta contra a instância institucional usada na disciplina DSC/UFPB. Essa validação comprovou funcionamento, sanitização de UUIDs e envio de `book_created`, `scene_saved` e `book_exported`.

A instância, domínio, Website ID e eventual deploy da disciplina são **evidência histórica**, não configuração atual nem pendência operacional. Os registros permanecem em `docs/evidencias/`, `docs/entrega/` e `docs/evidencias-validacao-humana-2026-08-08.md`.
