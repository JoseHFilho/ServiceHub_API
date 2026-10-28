# Preparação de implantação

Este guia descreve os controles acrescentados após a auditoria. Os testes e o
estado final de cada controle são registrados em
[ENTREGA_PARCIAL_AUDITORIA.md](ENTREGA_PARCIAL_AUDITORIA.md).
Configurações de exemplo não comprovam que a infraestrutura pública existe.

## Identidades PostgreSQL

Instalações novas usam três identidades distintas:

| Variáveis no arquivo privado | Uso |
|---|---|
| `DB_ADMIN_USERNAME` / `DB_ADMIN_PASSWORD` | Bootstrap do PostgreSQL; nunca fornecidas à API |
| `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD` | Dono do banco/schema e objetos; credenciais separadas do Flyway |
| `DB_USERNAME` / `DB_PASSWORD` | DML da API, sem propriedade, CREATE ou privilégios administrativos |

`scripts/prepare-env.ps1` gera segredos independentes, sem imprimir valores ou
sobrescrever arquivos. O Compose traduz a identidade de migration para
`SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD`. Fora do Compose, forneça essas
variáveis explicitamente e a URL JDBC correta. O datasource compartilhado
continua sendo usado nos testes H2 quando não há credencial Flyway separada.

`docker/postgres/10-provision.sh` roda **somente no initdb de volume vazio**.
Não é ferramenta de alteração de permissões de instalações existentes. O
callback de Flyway concede DML nas quatro tabelas de negócio, na tabela de
contadores `auth_rate_buckets` e uso de sequences,
sem conceder escrita no histórico ou propriedade de objetos. Novas tabelas de
aplicação devem ser incluídas explicitamente na política de grants.

Em `prod` com PostgreSQL, a aplicação recusa iniciar com conta administrativa,
CREATE no schema, escrita no histórico Flyway ou identidade de runtime igual
à de migrations. Não há provisionamento automático de ADMIN da aplicação.

Para uma instalação existente: inventariar privilégios, ensaiar em uma cópia
restaurada e revisar o plano com o operador antes de alterar permissões ou
configuração. Não reutilizar os arquivos novos para executar `compose up` sobre
um projeto/volume anterior sem esse procedimento. Nenhum banco preexistente foi
reconfigurado durante o desenvolvimento destas correções.

## Controle de admissão e corpos

No PostgreSQL, contadores por operação/origem/conta ficam em
`auth_rate_buckets` e são atualizados atomicamente. As chaves são HMACs com
separação de domínio, sem e-mail/IP em texto. Slots de concorrência usam locks
advisory transacionais numa conexão dedicada até terminar a operação pública;
rollback/fechamento libera o slot. O pool precisa reservar conexões para o
trabalho da aplicação além desses slots. Defaults: dois slots, janela de 60s,
login 30/origem, 10/conta, 120/global; cadastro 5/origem, 3/conta, 30/global.

As réplicas devem compartilhar banco, segredo JWT e **a mesma política** de
janela/limites/slots. A proteção é compartilhada nesse banco. H2 usa memória
somente para desenvolvimento/testes: isso não é proteção distribuída. Falhas do
armazenamento PostgreSQL retornam 503, sem fallback em memória. Rejeições de
frequência/concorrência retornam 429 e Retry-After, sem bloqueio permanente da
conta. Eventos são agregados em métricas e o log de rejeição é limitado para
não amplificar uma enxurrada de tentativas.

Variáveis: `AUTH_WINDOW_SECONDS`, `AUTH_LOGIN_IP_LIMIT`,
`AUTH_LOGIN_ACCOUNT_LIMIT`, `AUTH_LOGIN_GLOBAL_LIMIT`,
`AUTH_REGISTRATION_IP_LIMIT`, `AUTH_REGISTRATION_ACCOUNT_LIMIT`,
`AUTH_REGISTRATION_GLOBAL_LIMIT`, `AUTH_MAX_CONCURRENT`. Mudanças de política
entre réplicas precisam ser coordenadas. A limpeza remove até 1000 chaves
expiradas por minuto; limites muito maiores exigem planejar capacidade/limpeza.

O controle limita tentativas também de contas inexistentes; não usa existência
da conta para decidir a quota. Continua necessária proteção de borda contra
ataques de rede e tráfego que exceda a capacidade de receber/rejeitar requests.

O orçamento público de login/cadastro é 8192 bytes (`AUTH_BODY_BYTES`) e o das
escritas REST é 65536 (`REST_BODY_BYTES`). Content-Length excessivo é rejeitado
antes da leitura; sem comprimento o filtro lê no máximo limite + 1 byte. O
GraphQL conserva seu limite próprio de 65536. Campos desconhecidos também
participam do orçamento. Senhas legadas excepcionalmente grandes ficam sujeitas
ao orçamento de login; nunca são truncadas. Não são gravados corpos rejeitados.

## TLS opcional e proxy confiável

`compose.tls.yaml` é opt-in e requer Compose 2.24.4 ou superior. Remove a porta
da API, isola sua rede de entrada e acrescenta Caddy com usuário 10001,
filesystem somente leitura e volume próprio de certificados. A API confia em
headers somente do IP interno exato do proxy; o Caddy sobrescreve Forwarded/
X-Forwarded-* enviados pelo cliente. Sem esse override, o ambiente local ignora
esses headers e usa o endereço real da conexão.

Defina, num arquivo privado novo, `PUBLIC_DOMAIN`, `HTTP_PORT`, `HTTPS_PORT`,
`PROXY_SUBNET`, `PROXY_IP` e `TRUSTED_PROXY_PATTERN` coerentes. O subnet padrão é
172.30.240.0/28, IP do proxy 172.30.240.14, padrão exato `172[.]30[.]240[.]14`.
Escolha outra faixa se houver conflito. O bind permanece 127.0.0.1 por padrão;
alterá-lo para acesso externo requer operação deliberada no ambiente definitivo.

Para certificados públicos, use `docker/proxy/Caddyfile`, DNS do domínio
apontando para o ingresso e portas públicas 80/443 alcançando o proxy. Caddy
gerencia emissão e renovação ACME; mantenha seu volume privado persistente,
monitore falhas/validade e ensaie renovação no destino. Não armazene chaves no
Git, no contexto da API ou no arquivo público de exemplos.

Para ensaio isolado, use `CADDY_CONFIG=./docker/proxy/Caddyfile.local`, domínio
`servicehub.localhost` e portas livres em loopback. A CA interna é exclusiva do
teste. Exporte apenas seu certificado público para o cliente de teste e valide
a cadeia/SAN explicitamente; não use `curl -k` para declarar o teste aprovado.
Não instale a CA de teste na confiança global do Windows.

```powershell
docker compose -f compose.yaml -f compose.tls.yaml --env-file .env.tls config --quiet
docker compose -f compose.yaml -f compose.tls.yaml --env-file .env.tls up -d --build --wait
```

Somente o host configurado é encaminhado à API; Origin de outro site é recusado.
O contrato preparado é de mesma origem. Um frontend em domínio distinto exige
allowlist explícita revisada nos dois lados, não wildcard. Nenhum CDN/proxy
adicional é considerado confiável automaticamente. Os logs do proxy omitem
headers e URI para evitar credenciais ou parâmetros sensíveis.

HTTPS **público** só pode ser declarado operacional depois de validar DNS,
certificado público, firewall, redirect e renovação no ambiente fornecido pelo
operador. O ensaio local não comprova esses itens.

## Mudanças de papel e histórico

Não há endpoint REST/GraphQL para alterar papéis. A operação interna
`RoleAdministrationService.changeRole` exige ADMIN e revalida o ator e o alvo
sob locks ordenados. Qualquer mudança de papel é bloqueada enquanto o usuário
participar de solicitações PENDING, ACCEPTED ou IN_PROGRESS, como cliente ou
prestador. Não há cancelamento, exclusão ou transferência automática.

V8 acrescenta no PostgreSQL um trigger que aplica a mesma regra às alterações
diretas feitas por operador. Essas alterações exigem READ COMMITTED; isolamentos
com snapshot persistente são recusados para não permitir uma corrida com nova
contratação. O bloqueio da linha do prestador é compartilhado com o fluxo de
contratação. H2 valida a política na camada de serviço, sem simular o trigger
PostgreSQL; os testes específicos de banco usam PostgreSQL real.

Após a troca permitida, serviços de uma conta sem PROVIDER são ocultados do
catálogo e não aceitam novas contratações, mas permanecem registrados. Histórico
encerrado e avaliações continuam acessíveis aos participantes pelo ID, mesmo
quando seu papel atual mudou. Usuários comuns não podem promover a si próprios.

## Revogação de JWT por credenciais

V9 adiciona `users.credential_version`, inicialmente 0, sem reescrever senhas ou
remover contas. JWTs novos incluem `cv`. O conversor consulta a conta atual e
exige versão igual, além de conta ativa e permissões atuais, tanto para REST
quanto para GraphQL. Após trocar a senha, o token usado na própria alteração
também perde validade: o cliente deve fazer login novamente.

Tokens emitidos antes desta versão, sem `cv`, equivalem à versão 0 e deixam de
funcionar após a primeira mudança de credenciais da conta. Não há refresh token
nem endpoint de logout com revogação individual nesta entrega.

No PostgreSQL, um trigger incrementa a versão quando o hash muda, inclusive em
SQL de operador, e recusa retrocesso da versão. A aplicação incrementa a versão
também no H2. O upgrade de hash legado usa atualização condicional com hash e
versão anteriores; uma corrida com troca de senha não emite JWT válido para a
credencial antiga. O login relê a conta e revalida a senha quando a versão mudou
durante a autenticação. Se o hash for atualizado legitimamente, o novo JWT usa a
versão atual e permanece utilizável no login seguinte.

A garantia vale para requisições autenticadas após a troca confirmada no banco.
Operações já em execução precisam dos locks e revalidações próprios do serviço;
revogação não desfaz respostas, transações ou cópias de tokens anteriores.
Implante somente após ensaio de V7–V9 em cópia restaurada do ambiente de destino.
O banco original deste workspace não foi acessado nem migrado.
