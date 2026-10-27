# Etapa 2 — Segurança e validação

Implementação restrita à Etapa 2, em 27/10/2026. JWT, GraphQL, gRPC e novos
endpoints dos demais módulos não foram implementados. O banco original não foi
acessado para aplicar alterações. Não houve commit ou push.

## Análise e preservação

Foram revisados os relatórios da Etapa 1, a V5, entidades, DTOs, controllers,
services, repositories e testes. A branch permanece
`appmod/java-upgrade-20260830185720`. As alterações anteriores, ainda não
commitadas, foram mantidas.

As migrations **V1–V5 foram preservadas**. Não foi necessária nova migration:
o campo `password_hash` existente comporta os formatos usados. As 44
verificações da etapa anterior continuam na suíte, com os contextos de
autenticação e as expectativas do novo formato de senha atualizados.

## Funcionalidades implementadas e configuração

- Spring Security 7.1.1 e Bean Validation nas versões gerenciadas pelo Spring
  Boot 4.1.1. Java/release 25 mantido.
- `SecurityFilterChain` com autorização por rota, contexto stateless, sem
  formulário de login, sem logout de sessão e sem cache de requisições.
- `AuthenticationManager` com `DaoAuthenticationProvider`, usuário carregado
  do banco por `UserDetailsService` e `PasswordEncoder` compatível com hashes
  novos e antigos.
- HTTP Basic transitório habilitado **somente em `dev` ou `test`, desde que
  `prod` não esteja ativo**. O perfil `prod` prevalece mesmo se combinado com
  `dev`/`test`. Sem o mecanismo transitório, rotas privadas continuam fechadas.
- CSRF habilitado também com HTTP Basic. Cookie `XSRF-TOKEN`, header
  `X-XSRF-TOKEN`, `SameSite=Strict` e cookie Secure fora de `dev`/`test`.
  O cookie contém o token CSRF, não uma sessão de autenticação.
- Novo endpoint público **GET `/api/csrf`**, com o token e o nome do header.
  Clientes precisam conservar o cookie e enviar o header nas escritas.
- `@EnableMethodSecurity`, `@PreAuthorize` e verificações explícitas de papel
  e propriedade nos services, inclusive para chamadas sem controller.
- JSON padronizado para 400, 401 e 403, preservando os contratos de 404, 409
  e demais erros. Nenhuma mensagem inclui senha, hash ou valor rejeitado.
- Swagger documenta HTTP Basic transitório e CSRF. A listagem de usuários,
  por exemplo, declara autenticação e acesso administrativo.

Não há conta administrativa padrão, senha gerada de demonstração ou promoção
automática para ADMIN. Os papéis continuam vindo do banco. O provisionamento
de contas privilegiadas exige um operador autorizado e não foi realizado.
Para uso fora do ambiente local, credenciais Basic exigem transporte HTTPS.

## Regras de autorização

| Recurso/operação | Regra |
| --- | --- |
| GET hello, status, health e CSRF | Público |
| POST `/api/users` | Público, com CSRF; sempre cria CLIENT ativo |
| GET `/api/users` | ADMIN |
| GET/PUT/PATCH/DELETE `/api/users/{id}` | Próprio titular ou ADMIN |
| Swagger/OpenAPI | Público em dev/test; exige ADMIN nos demais perfis |
| Consulta de serviços pela camada Service | Autenticado; somente ofertas ativas |
| Criação/alteração/desativação de serviços | PROVIDER proprietário ou ADMIN |
| Consulta individual de solicitação/avaliação | Cliente/revisor, prestador envolvido ou ADMIN |
| Listagem geral de solicitações/avaliações | ADMIN |
| Criação de solicitação | CLIENT para si próprio ou ADMIN |
| Alteração de solicitação | Participante autorizado, sem substituir cliente, serviço ou preço histórico |
| Registro/alteração de avaliação | Cliente real do pedido ou ADMIN; o avaliador deve corresponder ao cliente |
| Exclusão física nos services existentes de solicitações/avaliações | Restrita a ADMIN; sem novos endpoints HTTP |

Os services de serviços, solicitações e avaliações foram protegidos para seu
reuso futuro. A máquina completa de estados e os controllers/DTOs desses
módulos continuam pertencendo à Etapa 4. A desativação de usuários e serviços
continua lógica, com bloqueio por pedidos abertos e preservação do histórico.

Usuários inativos falham na autenticação. Usuário autenticado comum não pode
ler e-mail/perfil privado de outro usuário, listar administrativamente ou
alterar/excluir recursos alheios trocando o ID. Recursos inexistentes/inativos
continuam retornando 404 para quem tem autorização de consulta. Um titular
desativado não consegue autenticar uma nova requisição.

Escritas sem CSRF válido retornam 403 antes do processamento do corpo. Consultas
protegidas sem autenticação válida retornam 401. Usuários autenticados sem
permissão recebem 403. Erros de validação declarativa recebem 400.

## Estratégia PBKDF2/BCrypt

1. Novas senhas usam BCrypt com custo 12, salt aleatório e limite de 8–72
   caracteres, também limitado a 72 bytes UTF-8 nos DTOs de criação/alteração.
2. O encoder reconhece o formato histórico
   `pbkdf2$65536$<salt-base64>$<hash-base64>`, com salt de 16 bytes, saída de
   32 bytes e PBKDF2-HMAC-SHA256. A comparação usa `MessageDigest.isEqual`.
3. Hashes malformados, fatores de trabalho arbitrários e tamanhos inesperados
   são rejeitados sem imprimir o conteúdo e sem executar trabalho ilimitado.
4. Após a verificação correta, `DaoAuthenticationProvider` chama
   `UserDetailsPasswordService` para salvar o novo hash BCrypt. Senha incorreta
   e conta inativa não provocam atualização.
5. A atualização é condicional: ID, hash anterior e ativação ainda precisam
   corresponder ao registro. Isso impede sobrescrever uma troca de senha ou
   desativação ocorrida durante a autenticação.
6. A implementação antiga aceitava senhas acima de 72 bytes. Para manter essas
   contas utilizáveis, o upgrade usa BCrypt sobre um digest SHA-256 codificado
   em Base64, com prefixo de formato `{bcrypt-sha256}`. A verificação repete
   essa preparação somente para esse formato. A senha completa participa do
   cálculo e não é truncada. Os DTOs novos não aceitam senhas acima do limite.

Não há conversão direta de hash, mudança em lote ou armazenamento de senha em
texto puro. A senha é usada apenas durante sua recepção/verificação. Entidades
ignoram `passwordHash` na serialização; DTOs marcam senha como somente escrita
e o `toString()` do PATCH não inclui seus valores.

## Bean Validation e entradas

`UserDTO` e `UserPatchDTO` usam `@Valid`, `@NotNull`, `@NotBlank`, `@Email`,
`@Size`, `@Pattern`, `@AssertTrue` e a constraint `@Utf8Size`, conforme a
semântica de cada campo. O service de usuários também valida seus DTOs.

O PATCH aceita omissão/null quando há outro campo válido informado, preserva
os valores anteriores e rejeita corpo sem alteração ou campos em branco.
Nomes recebem normalização Unicode NFC e de espaços e são tratados como texto
simples, rejeitando marcação e caracteres de controle. E-mails recebem remoção
de espaços externos e minúsculas com `Locale.ROOT`. Senhas não são aparadas
nem normalizadas.

Os DTOs continuam sem propriedades de escrita para `role`, `active`,
`passwordHash`, IDs e metadados. Propriedades desconhecidas não são vinculadas;
o contrato anterior de ignorá-las foi mantido e testado. Queries de autenticação,
upgrade de hash e consultas dos repositories usam parâmetros. Nenhuma entrada
HTTP é concatenada em SQL.

## Arquivos criados e modificados nesta etapa

Criados no código principal:

- `config/SecurityConfig.java`
- `controller/CsrfController.java`
- `security/AccessPolicy.java`
- `security/ServiceHubPrincipal.java`
- `security/DatabaseUserDetailsService.java`
- `security/SecurityErrorHandler.java`
- `validation/InputNormalizer.java`
- `validation/Utf8Size.java`

Os caminhos acima são relativos a `src/main/java/com/servicehub/`.

Modificados: `pom.xml`, `application.properties`, `OpenApiConfig`,
`UserController`, `UserDTO`, `UserPatchDTO`, `UserResponse`, `User`,
`UserRepository`, `PasswordHashService` e os quatro services existentes.
Também foram atualizados `README.md` e `scripts/verify-api.ps1`, que agora
recebe `-Credential` de uma conta ADMIN e conserva/envia o cookie CSRF.

Novos testes em `src/test/java/com/servicehub/security/`:

- `SecurityIntegrationTests.java`
- `ProductionSecurityIntegrationTests.java`
- `CsrfAndSessionIntegrationTests.java`
- `DatabaseUserDetailsServiceTest.java`

Foram ampliados `PasswordHashServiceTest` e ajustados os contextos de
`UserServiceTest`, `UserControllerIntegrationTests`,
`DeactivationIntegrationTests` e `OpenApiIntegrationTests`, preservando as
verificações anteriores. O relatório da Etapa 1 recebeu um registro separado
da limpeza de containers autorizada pelo usuário.

## Testes executados, falhas encontradas e correções

O ambiente usa JDK **26.0.2.1**, mantendo o compilador em **release 25**, Spring
Boot **4.1.1** e Maven Wrapper **3.9.16**. Não foi executado um JDK 25 nesta
sessão.

A suíte foi executada durante a integração. Uma verificação antiga detectou
que a validação de métodos do Spring substituía a mensagem específica do
e-mail por uma mensagem genérica. O handler passou a tratar tanto erros de
corpo quanto `HandlerMethodValidationException`, conservando mensagens seguras
e o contrato anterior.

Os testes reais de cookie CSRF detectaram interferência do auxiliar `csrf()`
do Spring Security Test, que substitui o repositório de token no contexto
compartilhado. Os testes do contrato real usam um contexto separado, com cookie
real, sem esse auxiliar. A proteção CSRF permanece habilitada em todos eles.
Também foi alinhado o token retornado pelo endpoint ao valor bruto esperado no
header da configuração SPA, mantendo a proteção XOR do framework nos demais
atributos de requisição.

Resultado final de **`.\mvnw.cmd -o clean verify`**: **BUILD SUCCESS, 92 testes,
zero falhas, zero erros, zero ignorados**, com geração do JAR. São 44
verificações anteriores preservadas e 48 novas. A suíte inclui HTTP Basic real,
perfis CLIENT/PROVIDER/ADMIN, CSRF/cookie reais, BOLA, mass assignment,
usuários inativos, validação, BCrypt, PBKDF2, senha legada longa, condição
concorrente no upgrade e rotas fechadas em produção.

Evidência: [resumo Maven da Etapa 2](evidencias/etapa2-maven-final.json).
Log local não versionado: `.tmp/etapa2-final-verify.log`.
O script PowerShell de verificação HTTP teve sua sintaxe conferida, mas não
foi executado contra uma API externa nesta etapa.

**Não houve nova execução PostgreSQL nesta etapa.** As 132 execuções da Etapa 1
continuam documentadas separadamente e não foram apresentadas como validação
do código de segurança novo. A suíte desta etapa usou H2, incluindo contextos
Spring e MockMvc com a cadeia de segurança real.

## Containers e pendências para a Etapa 3

Os dois containers temporários da validação anterior foram removidos após
autorização explícita, pelos nomes exatos, com as evidências já salvas. Os
outros 8 containers e os 6 volumes foram preservados. Não foram criados novos
containers durante a Etapa 2. Consulte a [evidência de limpeza](evidencias/etapa1-limpeza-containers.json).

A Etapa 2 está implementada. Para a Etapa 3 continuam pendentes login JWT,
assinatura/expiração dos tokens, filtro Bearer, chave por variável de ambiente
e integração do esquema Bearer no OpenAPI. O mecanismo Basic deve ser
substituído na evolução. Produção continua sem mecanismo HTTP de autenticação
para as rotas privadas e, por isso, permanece fechada para elas.

Não houve aplicação de migrations ou alteração de dados no banco original,
remoção de outros containers/volumes, commit, push ou início de outra etapa.

## Referências técnicas

- [Spring Security: DaoAuthenticationProvider](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/dao-authentication-provider.html)
- [Spring Security: CSRF e configuração para SPA](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Spring Boot: starters e dependências gerenciadas](https://docs.spring.io/spring-boot/reference/using/build-systems.html)
