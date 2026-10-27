# Etapa 3 — Autenticação JWT

Implementada e validada em 27/10/2026. Spring Boot 4.1.1 e Java/release 25
mantidos. Não houve alteração nas migrations V1–V5, no banco original ou nos
controllers dos módulos de serviços, solicitações e avaliações. GraphQL, gRPC
e a Etapa 4 não foram iniciados. Não houve commit ou push.

## Análise inicial e escopo

Foram revisados os relatórios das Etapas 1 e 2, a V5, `SecurityConfig`,
`DatabaseUserDetailsService`, `PasswordHashService`, `AccessPolicy`, DTOs,
controllers e testes. A branch continua
`appmod/java-upgrade-20260830185720`, com o trabalho anterior não commitado
preservado.

A base continha 92 verificações, autenticação Basic transitória em dev/test,
CSRF com cookie e autorização por papel/propriedade. A evolução substituiu
o transporte de autenticação por JWT, preservando BCrypt/PBKDF2, as regras de
propriedade e a exclusão lógica.

## Funcionalidades e endpoints

Novo endpoint: **POST `/api/auth/login`**.

Entrada JSON com `email` e `password`. O e-mail é normalizado e validado;
senha vazia é rejeitada. O login não aplica os limites de criação de senhas
novas aos candidatos, permitindo autenticar senhas legadas válidas, inclusive
as curtas ou maiores que 72 bytes que a implementação antiga aceitava.

O fluxo usa o `AuthenticationManager` existente e a conta do banco:

1. O provider verifica BCrypt ou o PBKDF2 histórico e rejeita conta inativa.
2. Uma senha PBKDF2 correta é atualizada pela estratégia gradual da Etapa 2.
   Não há conversão de hash sem a senha nem atualização em lote.
3. A conta é consultada novamente antes da emissão para obter ativação e papel
   atuais.
4. O serviço retorna `accessToken`, `tokenType="Bearer"`, `expiresIn` e
   `expiresAt`. A resposta tem `Cache-Control: no-store` e `Pragma: no-cache`.

Não há senha, hash ou e-mail dentro do JWT/resposta de autenticação. Credenciais
incorretas, usuário inexistente ou inativo retornam **401 com mensagem genérica**.
Corpo inválido retorna 400. As regras de autorização existentes continuam
retornando 403 para usuário autenticado sem permissão.

`GET /api/csrf` foi preservado como compatibilidade e marcado obsoleto. Agora
retorna `required=false` e `authentication="Bearer"`; não emite token ou cookie.
Nenhum outro endpoint de autenticação foi adicionado.

## Assinatura, configuração e validação

A biblioteca é **Nimbus JOSE + JWT 10.9.1**, integrada pelo suporte OAuth2
Resource Server/Jose do **Spring Security 7.1.1**, nas versões gerenciadas pelo
Spring Boot. Nenhuma versão do projeto foi atualizada para viabilizar JWT.

`JwtService` emite e verifica tokens **HS256**, com algoritmo explicitamente
selecionado e header `typ=JWT`. Claims emitidos:

| Claim | Conteúdo |
| --- | --- |
| `iss` | Emissor configurado |
| `sub` | ID positivo do usuário em texto |
| `iat` | Instante de emissão UTC |
| `exp` | Expiração UTC |
| `roles` | Papel único da conta, em uma lista |

A validação exige assinatura correta, emissor esperado, claims obrigatórios,
ID válido, papel conhecido e expiração posterior à emissão e ao instante atual.
Há rejeição de emissão mais de 30 segundos no futuro e de lifetime acima de
uma hora. O `exp` é verificado sem tolerância adicional. Tokens sem assinatura,
adulterados, malformados, com chave incorreta ou expirados são rejeitados.

| Variável | Configuração |
| --- | --- |
| `JWT_SECRET` | Obrigatória; 32–128 bytes aleatórios codificados em Base64 |
| `JWT_TTL` | Default `15m`; intervalo permitido de `1m` a `1h` |
| `JWT_ISSUER` | Default `servicehub-api` |

Não existe chave padrão de aplicação, inclusive em dev. Chave ausente,
malformada ou curta impede inicialização. O objeto de configuração mascara
seu `toString()`. `.env` está ignorado pelo Git; `.env.example` contém apenas
nomes/placeholders. O Java não lê esse arquivo automaticamente.

Exemplo local de geração sem imprimir o segredo:

```powershell
$env:JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
$env:JWT_TTL = '15m'
java -jar target\servicehub-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

Em execução persistente, forneça a mesma chave gerenciada entre reinícios se
quiser manter os tokens ainda válidos. Trocar a chave invalida assinaturas
anteriores. Nos testes, uma chave aleatória é gerada em memória por JVM de
teste, sem fallback empacotado no JAR e sem gravação/saída do valor.

## Filtro e autorização atual

Foi usada a alternativa do próprio Spring Security:
**`BearerTokenAuthenticationFilter`**, instalado pelo Resource Server, com
`JwtService` como decoder e `CurrentUserJwtConverter` como conversor.

O filtro lê apenas `Authorization: Bearer <accessToken>`, verifica o JWT e
carrega o ID assinado pelo `DatabaseUserDetailsService`. Essa consulta exige
que a conta ainda exista e esteja ativa. As authorities vêm do **papel atual
do banco**, e não do papel antigo dentro do token.

Assim, desativação/exclusão torna um JWT ainda assinado inutilizável com 401;
remoção de ADMIN impede imediatamente acesso administrativo com 403. Um
usuário continua podendo acessar seus próprios dados conforme seu papel atual.
Essa decisão implica uma consulta de conta por requisição autenticada.

`SecurityFilterChain`, `@PreAuthorize`, `AccessPolicy`, propriedade dos recursos
e desativação lógica foram mantidos. JWT funciona também em produção. Swagger
permanece público em dev/test e restrito a ADMIN nos demais perfis; `prod`
prevalece quando combinado com dev/test.

Há rejeição de múltiplos headers Authorization. Token em query string, corpo
de formulário ou cookie não autentica uma requisição. HTTP Basic foi removido
de **todos** os perfis, e não é aceito como fallback.

## Stateless e CSRF

O contexto usa `SessionCreationPolicy.STATELESS`,
`NullSecurityContextRepository` e ausência de request cache, formulário de
login e logout de sessão. Não há cookie automático de JWT, autenticação por
cookie ou sessão HTTP de login.

CSRF foi dispensado nessa configuração porque a autenticação depende
exclusivamente de um Bearer fornecido explicitamente no header. Nenhum
mecanismo de credenciais enviadas automaticamente pelo navegador permaneceu.
As verificações de autenticação, papéis e propriedade continuam habilitadas.
Se no futuro houver autenticação por cookies/Basic, a política CSRF terá de
ser revisada para esse novo modelo. CORS não foi aberto indiscriminadamente.

Use HTTPS fora do ambiente local. O sistema não escolhe nem persiste o storage
do token no cliente. Não foram adicionados refresh token, logout/revogação
individual ou blocklist; tokens expiram no prazo configurado. Uma troca de
senha não revoga, por si só, tokens já emitidos. Ativação e papéis são conferidos
imediatamente no banco, conforme os requisitos desta etapa.

## Swagger/OpenAPI e script HTTP

`bearerAuth` substitui `basicAuth`, com tipo HTTP, scheme `bearer` e formato
JWT. No Swagger em dev/test, obtenha o token no login e cole **apenas o valor
de `accessToken`** no botão Authorize. Os endpoints protegidos declaram esse
security requirement. Login, DTOs e respostas 400/401/403 estão documentados.
Persistência de autorização e suporte CSRF do Swagger estão desabilitados.

`scripts/verify-api.ps1` recebe `-Credential` de uma conta ADMIN, faz login e
usa o Bearer nas verificações seguintes, sem cookies. Não grava o token no
relatório nem mostra o corpo em falhas. Segue redirecionamentos GET de forma
limitada e somente para a mesma origem; não encaminha JWT a outro host,
porta ou protocolo.

## Testes e resultados reais

Ambiente: JDK **26.0.2.1**, compilação **release 25**, Maven Wrapper **3.9.16**.
Um JDK 25 não foi executado nesta sessão.

| Execução final | Testes | Falhas | Erros | Ignorados | Resultado |
| --- | ---: | ---: | ---: | ---: | --- |
| `.\mvnw.cmd -o clean verify`, H2 2.4.240 | 127 | 0 | 0 | 0 | BUILD SUCCESS e JAR gerado |
| Suíte com PostgreSQL 15.19 isolado | 127 | 0 | 0 | 0 | BUILD SUCCESS |
| Suíte com PostgreSQL 16.14 isolado | 127 | 0 | 0 | 0 | BUILD SUCCESS |
| Script HTTP real, JAR em `prod`, PostgreSQL 15.19 | 25 | 0 | 0 | 0 | Todas as verificações aprovadas |

Os 92 cenários anteriores foram preservados e adaptados ao transporte Bearer.
Há **35 verificações novas**: 16 em `JwtServiceTest` e 19 em
`JwtAuthenticationIntegrationTests`. A suíte verifica login para todos os
papéis, senha/conta inválida/inativa, compatibilidade PBKDF2/BCrypt, assinatura,
expiração, claims obrigatórios, configurações inseguras, BOLA, headers
malformados, alteração de role/ativação após emissão e Swagger JWT.

Os testes antigos de CSRF/cookie foram atualizados para confirmar ausência de
cookie de autenticação e de sessão, escrita com Bearer sem CSRF e rejeição
de cookie como credencial. Nenhum teste foi desativado ou ignorado.

Flyway e Hibernate inicializaram nas duas versões PostgreSQL. O JAR foi
iniciado em uma porta livre de loopback, no perfil `prod`, contra o banco
temporário PostgreSQL 15.19. O fluxo HTTP verificou login, health, Swagger,
CRUD, validação e exclusão lógica. Um fixture administrativo criado somente
nesse banco isolado foi desativado após o fluxo; seu token recebeu 401 na
requisição seguinte. O processo Java exclusivo do teste foi encerrado.

### Dificuldades encontradas e correções

- O helper de login dos testes inicialmente serializava o DTO de entrada com
  senha marcada `WRITE_ONLY`, omitindo-a do JSON e causando 400. O helper
  passou a montar o payload de entrada explicitamente; a proteção do DTO
  permaneceu habilitada.
- Duas asserções novas sobre mapas OpenAPI precisaram de ajuste de tipos
  genéricos para compilar. Os cenários foram mantidos.
- No teste HTTP do JAR em produção, o cliente .NET removia Authorization ao
  seguir automaticamente o redirecionamento do Swagger e recebia 401. O
  script passou a seguir explicitamente apenas redirecionamentos para a mesma
  origem, mantendo JWT sem vazar credenciais para destinos diferentes. O
  fluxo completo foi repetido e aprovado.

### Evidências salvas

- [Maven final](evidencias/etapa3-maven-final.json)
- [PostgreSQL 15](evidencias/etapa3-postgres15.json)
- [PostgreSQL 16](evidencias/etapa3-postgres16.json)
- [Verificações HTTP](evidencias/etapa3-http.json)
- [JAR em produção e bloqueio após desativação](evidencias/etapa3-runtime.json)
- [Migrations preservadas por SHA-256](evidencias/etapa3-migrations.json)
- [Containers e volumes preservados](evidencias/etapa3-preservacao.json)

Logs locais não versionados: `.tmp/etapa3-maven-final.log`,
`.tmp/etapa3-postgres15.log`, `.tmp/etapa3-postgres16.log`,
`.tmp/etapa3-runtime-check.log` e `.tmp/etapa3-api-stdout.log`.
As evidências não contêm senhas, chaves de assinatura ou tokens.

## Arquivos criados e modificados

Novos arquivos principais em `src/main/java/com/servicehub/`:

- `controller/AuthController.java`
- `service/AuthService.java`
- `security/JwtProperties.java`
- `security/JwtService.java`
- `security/CurrentUserJwtConverter.java`
- `model/dto/LoginRequest.java`
- `model/dto/TokenResponse.java`

Modificados: `SecurityConfig`, `SecurityErrorHandler`,
`DatabaseUserDetailsService`, `GlobalExceptionHandler`, `OpenApiConfig`,
`UserController` (descrição de erro), `CsrfController`,
`application.properties`, `pom.xml`, `.gitignore`, `README.md` e
`scripts/verify-api.ps1`. Foi criado `.env.example` sem segredo real.
`PasswordHashService`, `AccessPolicy` e as migrations V1–V5 não foram alterados.

Novos testes: `JwtTestSupport`, `security/JwtServiceTest` e
`security/JwtAuthenticationIntegrationTests`, sob `src/test/java/com/servicehub/`.
Os testes Spring existentes receberam a chave aleatória de teste e os testes
de segurança/produção/cookie foram adaptados para JWT, preservando seus casos.
Este relatório e as evidências acima também foram adicionados.

## Preservação e pendências para a Etapa 4

Comparação SHA-256 antes/depois: **zero migrations V1–V5 alteradas**. Não foi
aplicada V5 no banco original. Todos os **8 containers originais** continuam
presentes e os **6 volumes** permanecem sem mudança de nomes. O container
original `servicehub-db` continuou parado.

Foram criados e mantidos dois containers novos, somente com dados sintéticos
em `tmpfs`, sem volumes da aplicação:

- PostgreSQL 15: `servicehub-v5-test-e540acf28994457cbe9f577c48137e80`
- PostgreSQL 16: `servicehub-v5-test-b914fa4e02ec400299a27c37cc5d8bbe`

Nenhum container ou volume existente foi excluído. As variáveis temporárias
do teste HTTP foram restauradas ao final. Nenhuma conta ADMIN foi criada no
banco original.

A Etapa 3 está concluída. Para a Etapa 4 seguem pendentes os controllers e
DTOs REST de serviços/solicitações/avaliações e suas regras completas de
negócio e transição de estados. Nenhum desses módulos foi antecipado nesta
implementação.

## Referências técnicas consultadas

- [Spring Security: Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Security: CSRF e autenticação stateless](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html)
- [Spring Security: CSRF em aplicações servlet](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
