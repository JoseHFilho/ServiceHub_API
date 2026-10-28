# GraphQL da ServiceHub API

Use **POST /graphql**, JSON e `Authorization: Bearer <accessToken>`.
O login continua em `POST /api/auth/login`. REST e GraphQL usam os mesmos
services, transações, DTOs de negócio e regras de autorização.

O schema completo está em
[servicehub.graphqls](../src/main/resources/graphql/servicehub.graphqls).
Cadastro público continua criando CLIENT; GraphQL não cria nem promove
contas de prestador ou administrador.

## Primeiro acesso

~~~powershell
$baseUrl = 'http://localhost:8080'
$loginBody = @{email='cliente@example.com';password='SUA_SENHA'} | ConvertTo-Json
$login = Invoke-RestMethod "$baseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
$headers = @{Authorization='Bearer ' + $login.accessToken}
$body = @{query='{services(category:LIMPEZA,page:{size:10}){content{id title price provider{id fullName avatarUrl}} totalElements}}'} | ConvertTo-Json
Invoke-RestMethod "$baseUrl/graphql" -Method Post -Headers $headers -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body))
~~~

## Queries

| Campo | Argumentos | Visibilidade |
| --- | --- | --- |
| `services` | `category`, `providerId`, `page` | Catálogo de serviços/prestadores ativos |
| `service` | `id!` | Serviço disponível |
| `providers` | `page` | Apenas PROVIDER ativo, projeção pública |
| `provider` | `id!` | Prestador ativo; cliente/ADMIN não é listado como prestador |
| `requests` | `status`, `serviceId`, `page` | Cliente/prestador participante; ADMIN vê todas |
| `request` | `id!` | Participante ou ADMIN |
| `reviews` | `requestId`, `serviceId`, `page` | Avaliador/prestador participante; ADMIN vê todas |
| `review` | `id!` | Avaliador, prestador participante ou ADMIN |

Listagens retornam `content`, `page`, `size`, `totalElements`,
`totalPages`, ordenadas por ID crescente. `page` recebe um objeto
`{page:0,size:20}`; omitido ou nulo usa 0/20. Os filtros são cumulativos e
não ampliam o escopo autorizado.

~~~graphql
query Catalogo($category: ServiceCategory!, $page: PageInput) {
  services(category: $category, page: $page) {
    content { id title description price category provider { id fullName avatarUrl } }
    totalElements totalPages
  }
}
~~~

Variáveis:

~~~json
{"category":"LIMPEZA","page":{"page":0,"size":10}}
~~~

~~~graphql
query Prestadores {
  providers(page: {size: 10}) { content { id fullName avatarUrl } totalElements }
}
~~~

~~~graphql
query Detalhes($serviceId: ID!, $providerId: ID!) {
  service(id: $serviceId) { id title price category providerId }
  provider(id: $providerId) { id fullName avatarUrl }
}
~~~

~~~graphql
query MinhasSolicitacoes {
  requests(status: PENDING, page: {size: 10}) {
    content { id serviceId serviceTitle clientId providerId status totalPrice scheduledAt notes }
    totalElements
  }
}
~~~

~~~graphql
query Historico($requestId: ID!, $serviceId: ID!, $reviewId: ID!) {
  request(id: $requestId) { id status totalPrice }
  reviews(serviceId: $serviceId) { content { id rating comment } totalElements }
  review(id: $reviewId) { id requestId reviewerId rating comment }
}
~~~

Substitua os IDs por registros existentes e autorizados.
Provider só contém ID, nome público e avatar. Não há tipo User nem campos
de email, senha, hash, telefone ou papel no schema.

## Mutations

Cada requisição aceita **uma mutation de negócio**. Identidade, preço
histórico e estado inicial são definidos pelo servidor. Campos desconhecidos
são recusados pela validação do schema.

~~~graphql
mutation CriarServico {
  createService(input: {
    title: "Limpeza residencial"
    description: "Limpeza completa do imóvel"
    price: "150.00"
    category: LIMPEZA
  }) { id title price providerId active }
}
~~~

Requer PROVIDER. Somente ADMIN pode representar um prestador ativo,
informando o argumento separado providerId, recusado para usuários comuns.

~~~graphql
mutation AtualizarServico($id: ID!) {
  updateService(id: $id, input: {price: "200.00"}) { id title price updatedAt }
}
~~~

Usa o PATCH existente: campo nulo/omitido é preservado; exige ao menos um
campo editável. Descrição vazia limpa o texto. Proprietário PROVIDER ou ADMIN;
a propriedade do serviço não é transferida.

~~~graphql
mutation Contratar($id: ID!) {
  createRequest(input: {serviceId: $id, notes: "Portaria recebe o prestador"}) {
    id clientId status totalPrice
  }
}
~~~

Requer CLIENT e serviço/prestador ativos. O cliente vem do JWT, o estado
inicial é PENDING e o preço é copiado sob proteção transacional.
ADMIN pode representar um CLIENT ativo pelo argumento clientId.
É proibido contratar o próprio serviço. Não existem entradas totalPrice,
status inicial ou reviewerId.

~~~graphql
mutation Avancar($id: ID!) {
  changeRequestStatus(id: $id, input: {status: ACCEPTED}) { id status totalPrice }
}
~~~

Prestador participante ou ADMIN avança
PENDING → ACCEPTED → IN_PROGRESS → COMPLETED.
Cliente pode cancelar PENDING/ACCEPTED. Prestador ou ADMIN pode cancelar
qualquer estado aberto. COMPLETED/CANCELLED são finais; repetição, salto e
reabertura geram conflito.

~~~graphql
mutation Avaliar($id: ID!) {
  createReview(input: {requestId: $id, rating: 5, comment: "Serviço excelente"}) {
    id requestId serviceId reviewerId rating comment
  }
}
~~~

Somente solicitação COMPLETED e cliente real, ou ADMIN representando esse
cliente. Uma avaliação por solicitação; nota inteira de 1 a 5 e comentário
simples até 2000 caracteres.

~~~graphql
mutation Desativar($id: ID!) {
  deactivateService(id: $id) { id active }
}
~~~

Proprietário PROVIDER ou ADMIN. Solicitações abertas impedem a desativação.
Retorna active:false; registro, solicitações e avaliações são preservados.
O histórico privado segue acessível aos participantes e ADMIN.

## Tipos e validação

Decimal recebe e devolve **string decimal exata**, por exemplo `"150.00"`.
Não envie Float/Double, vírgula decimal ou notação científica.
O scalar evita arredondamento binário; o DTO compartilhado exige preço
positivo, até oito dígitos inteiros e duas casas decimais.

ID é exposto como string. Long representa contagens inteiras de 64 bits.
LocalDateTime usa ISO-8601 local, sem offset, como
`"2027-01-10T09:00:00"`. Agendamento informado deve ser atual ou futuro.

As mesmas validações REST se aplicam: título até 150 caracteres; descrição
e observações até 5000; comentário até 2000; textos normalizados em NFC,
aparados, sem HTML/controles indevidos. As 21 categorias existentes estão
no schema.

## Erros

Leia errors além do status HTTP. Falhas de negócio são erros de campo
GraphQL, normalmente com HTTP 200, permitindo dados parciais nas queries:

~~~json
{
  "errors": [{
    "message": "Acesso negado.",
    "path": ["request"],
    "extensions": {"code": "FORBIDDEN", "classification": "FORBIDDEN"}
  }],
  "data": {"request": null}
}
~~~

Códigos de negócio em extensions.code: BAD_REQUEST, UNAUTHORIZED, FORBIDDEN,
NOT_FOUND, CONFLICT e QUERY_LIMIT_EXCEEDED. Falhas inesperadas usam o
INTERNAL_ERROR opaco do Spring com identificador de correlação.
Erros de parsing, schema e variáveis são sanitizados; não devolvem conteúdo
arbitrário recebido, SQL, stack trace ou credenciais.

Rejeições anteriores à execução podem usar HTTP 400, 401 ou 413.
Sem JWT válido: 401 com errors e WWW-Authenticate: Bearer.
Corpo acima do limite: 413 com código PAYLOAD_TOO_LARGE.
Validação pode usar 400 conforme o media type negociado.
Não há correspondência obrigatória entre cada erro GraphQL e um status REST.

## Limites, N+1 e ferramentas

| Proteção | Limite |
| --- | ---: |
| Corpo HTTP, incluindo variables e transferência chunked | 65.536 bytes |
| Documento | 16.384 caracteres |
| Profundidade | 8 |
| Custo estimado por operação | 2000 |
| Aliases, inclusive via fragments | 10 |
| Campos raiz de query | 10 |
| Seleções expandidas, incluindo fragments | 200 |
| Operações definidas por documento | 1 |
| Campos raiz de mutation | 1 |
| Itens por página | 1–100 |

Custo conservador: campo comum custa 1 mais seus filhos; listagem custa
10 mais o tamanho da página multiplicado pelo custo dos filhos. O tamanho
padrão é 20. Fragmentos e variáveis são considerados. Limites são reaplicados
em documentos em cache, antes das gravações. Arrays de requisições no corpo
JSON são recusados.

As relações necessárias aos DTOs usam EntityGraph; Service.provider usa
BatchMapping/DataLoader e projeção em lote. Um teste com prestadores
distintos confirma no máximo quatro comandos SQL por página, incluindo
autenticação, consulta, contagem e lote de prestadores.

GraphiQL e publicação do schema por HTTP ficam desabilitados por padrão.
Em prod, as duas rotas são negadas mesmo se propriedades tentarem habilitá-las.
A introspecção é fechada fora de dev/test; prod prevalece quando combinado
com esses perfis. Em dev/test, introspecção exige JWT e respeita os limites.
Use o SDL versionado para ferramentas que precisem de introspecção profunda.

Os limites são por requisição, sem quota distribuída ou rate limiter de
infraestrutura. Não há subscriptions, WebSocket, federation ou gRPC nesta etapa.

## Verificação HTTP isolada

Após gerar o JAR e executar o verificador PostgreSQL:

~~~powershell
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 15 -KeepTestContainer
.\scripts\verify-graphql.ps1 -ContainerName 'servicehub-v5-test-<ID retornado pelo verificador>'
~~~

O script aceita somente containers PostgreSQL de teste em tmpfs, banco
schema_test e porta em loopback. Inicia seu próprio JAR em prod, cria fixtures
sintéticos, valida GraphQL e REST, desativa logicamente as contas após sucesso
e encerra a própria API. Não acessa o banco original nem remove containers
ou volumes. Resultado: docs/evidencias/etapa5-http.json.
