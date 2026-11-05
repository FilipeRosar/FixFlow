# Desenvolvimento do backend FixFlow

Visão do produto e decisões de arquitetura: [documento técnico](documento-tecnico-fixflow.md).
Início rápido: [README do repositório](../README.md).

## Banco local

O PostgreSQL deve estar disponível em `localhost:5432/fixflow`, com os usuários
`fixflow_runtime` e `fixflow_migrator` já configurados.

Na raiz do projeto, configure uma vez as senhas existentes no banco:

```bash
python3 scripts/configure-local.py
```

O comando cria `application-local.properties`, ignorado pelo Git e legível apenas
pelo usuário atual. O Spring carrega esse arquivo ao iniciar a aplicação e os
testes. Para mudar host, banco, usuários ou senhas, edite esse arquivo local.
O comando não altera usuários nem senhas no PostgreSQL.

Variáveis de ambiente têm prioridade sobre os valores locais. Para remover
valores antigos do terminal antes de usar o arquivo:

```bash
unset DB_URL DB_USERNAME DB_PASSWORD DB_MIGRATOR_USERNAME DB_MIGRATOR_PASSWORD
./mvnw clean verify
```

Para iniciar a aplicação:

```bash
./mvnw spring-boot:run
```

Se a porta 8080 estiver ocupada:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

Execute os comandos a partir da raiz deste projeto. Para testes pela IDE,
configure essa mesma pasta como diretório de trabalho.

## Autenticação e seleção de oficina (P0)

As rotas protegidas usam JWT de 15 minutos em `Authorization: Bearer <accessToken>`.
Faça login em `POST /api/v1/auth/login` com `X-Auth-Request: true`, e utilize o token
na seleção `/api/oficinas/{tenantId}/selecao`. O JWT fica restrito ao tenant escolhido;
cada operação valida novamente o vínculo. Consulte a seção US-002 para o contrato
completo e a renovação por cookie.

- `401`: token ausente, inválido/expirado ou identidade/vínculo/empresa indisponíveis.
- `403`: token autenticado tentando acessar outro tenant ou falha de CSRF/origem.
- `400`: identificador de oficina inválido.

O executor obtém a identidade exclusivamente do `SecurityContext`, abre uma
transação `REQUIRES_NEW` e consulta `authorize_tenant_membership`. Somente após a
autorização configura `TenantContext` e `SET LOCAL app.tenant_id`. JPA e JDBC usam
a mesma conexão e transação. Todo acesso ao banco da operação deve ocorrer
dentro do callback; não transfira trabalho para outra thread nem retorne consultas
JPA para executar depois. Em erro, a transação sofre rollback. Ao terminar, o
contexto Java anterior é restaurado e a configuração SQL local deixa de valer.
Um filtro limpa o ThreadLocal no início e no fim da requisição, inclusive quando
a autenticação falha.

Controllers dependem da interface da camada de aplicação. As regras ArchUnit
impedem dependência HTTP de infraestrutura, uso de `TenantScope` fora do executor
e alteração direta do tenant fora do escopo. UUID e userId enviados pelo cliente
nunca substituem a identidade autenticada.

Os testes HTTP e do executor usam o perfil `integration-test` (`fixflow_test`),
credenciais reais de teste e fixtures com UUIDs aleatórios. O migrator prepara e
remove as fixtures; o runtime executa as consultas de autorização e isolamento.
Como `tenant_memberships` usa FORCE RLS, a preparação abre uma policy temporária
restrita aos IDs da fixture e ao migrator, removida na mesma transação antes do
commit. A aplicação executa com as policies originais e sem privilégios adicionais.

```bash
./mvnw -Dtest=ArchitectureTest,AuthorizedTenantExecutorIntegrationTest,TenantSelectionIntegrationTest,TenantMembershipAuthorizationRepositoryIntegrationTest test
```

## Testes e infraestrutura (P1)

Todos os testes que sobem o Spring usam `integration-test` e executam em
`fixflow_test`. `DatabaseInfrastructureIntegrationTest` também consulta `fixflow`
para conferir as migrations V8 e V9, permissões, RLS e restrições administrativas
da role de execução.
Os dois bancos precisam estar disponíveis e migrados para executar essa verificação.

`MembershipFixtures` cria identidades, oficinas, vínculos e usuários legados com
IDs aleatórios, e remove os dados ao terminar cada teste. A criação é atômica e
o fechamento é idempotente. O helper recusa URLs fora de `fixflow_test`. Nos testes
com `@Transactional`, a preparação e a limpeza acontecem antes e depois da
transação de teste, usando `@BeforeTransaction` e `@AfterTransaction`.

`TenantWriteIsolationIntegrationTest` usa uma cópia descartável da estrutura de
`app_users` e da sua policy real. Somente essa tabela recebe permissões de CRUD
para o runtime. Isso exercita INSERT, UPDATE e DELETE com RLS sem conceder escrita
nas tabelas de produção, que continuam com SELECT. A tabela é removida no teardown.

A cobertura inclui:

- Leituras por JPA/JDBC e bloqueio de UPDATE/DELETE cruzados nos dois sentidos.
- INSERT autorizado com commit, DELETE próprio e bloqueio de INSERT em outra oficina.
- Bloqueio de alteração do `tenant_id` e de escrita sem contexto.
- Rollback de escritas quando ocorre exceção Java, erro SQL ou `Error`.
- Pool de tamanho 1 com comparação de `pg_backend_pid()`, comprovando a reutilização
  física da conexão após commit e rollback, sem vazamento de `app.tenant_id`.
- Restauração do contexto Java e SQL da transação externa ao suspender e retomar
  transações com `REQUIRES_NEW`.
- Limpeza de ThreadLocal inclusive quando a requisição termina com 401.
- Remoção de dados, tabelas e policy de fixture mesmo após falha no corpo do teste.

Para executar a suíte completa:

```bash
./mvnw clean verify
```

## Observabilidade (F-03)

O console emite logs JSON (Logstash). Cada requisição recebe `requestId` e
`correlationId` no MDC e nos headers de resposta `X-Request-Id` e
`X-Correlation-Id`. IDs recebidos são aceitos apenas com 1–128 caracteres
alfanuméricos, ponto, hífen ou underscore; valores inválidos são substituídos por
UUIDs. Sem correlation ID, usa-se o request ID.

`userId` vem do principal autenticado. `tenantId` acompanha exclusivamente o
escopo autorizado da oficina, inclusive nos logs de conclusão ou falha da operação.
Ao sair do escopo, restaura-se o tenant anterior; ao terminar a requisição,
limpam-se os campos do MDC. Headers de identidade enviados pelo cliente não
preenchem esses campos. Logs de conclusão HTTP incluem método, status e duração,
sem registrar credenciais, corpo ou query string. O contexto é síncrono e não é
propagado automaticamente para outras threads.

As probes são públicas e não expõem detalhes internos:

- `GET /health`: saúde agregada.
- `GET /health/liveness`: estado de vida da aplicação, independente do banco.
- `GET /health/readiness`: disponibilidade da aplicação e conexão com PostgreSQL.

As probes retornam `200` quando disponíveis e `503` quando indisponíveis.
Os endpoints de saúde substituem `/actuator/health`; apenas health está exposto.

### Auditoria (F-04)

Injete `AuditService` na operação de aplicação e chame `record(actorId, action,
resourceType, resourceId, details)` dentro da transação de negócio e do tenant
já autorizado. A propagação `MANDATORY` rejeita chamadas sem transação; falhas na
inserção também invalidam a transação de negócio. `details` recebe JSON válido;
não inclua senhas, tokens ou segredos. `actorId` nulo identifica eventos de sistema.
O tenant vem do contexto, e o horário é gerado pelo PostgreSQL.

A migration V10 cria `audit_log` com RLS obrigatório e índice por tenant/horário.
`fixflow_runtime` recebe apenas `SELECT` e `INSERT`, sem `UPDATE`, `DELETE` ou
`TRUNCATE`. A role proprietária de migração continua administrativa.

Validação: `./mvnw -Dtest=AuditServiceIntegrationTest,ArchitectureTest test`.


## Outbox transacional (F-06)

Injete `OutboxService` e chame `record(aggregateType, aggregateId, eventType, payloadJson)`
na mesma transação que altera o negócio, dentro do tenant autorizado. A propagação
`MANDATORY` rejeita chamadas sem transação. O JSON é validado pelo PostgreSQL.
Rollback da operação também remove o evento.

`OutboxPublisher` consulta os tenants com eventos vencidos por uma função restrita,
abre o contexto de cada tenant e processa até 100 eventos por ciclo, um por transação.
O RLS protege payloads; a função `pending_outbox_tenants()` revela somente IDs de tenants
com trabalho pendente. O lock `FOR UPDATE SKIP LOCKED` fica retido até o registro do resultado.
A role runtime pode inserir/ler eventos e atualizar somente os campos de processamento.

A entrega usa `ApplicationEventPublisher` com `OutboxEvent`. Consumidores devem usar
`@EventListener` síncrono, propagar falhas e ser idempotentes por `event.id()`.
Não use `@Async` nem `@TransactionalEventListener` para confirmar entrega: a falha
precisa retornar ao publicador. Os listeners executam em uma transação separada,
com o mesmo tenant, para que falhas SQL não impeçam a persistência do retry.
A entrega é **pelo menos uma vez**: uma queda entre a entrega e a confirmação pode
repetir o evento; múltiplos listeners também podem receber novamente eventos.
Sem listeners registrados, a publicação Spring é considerada bem-sucedida.

A primeira falha aguarda 1 segundo; as seguintes aguardam 2, 4 e 8 segundos.
A quinta falha marca `FAILED`, preservando a classe do erro em `last_error`, sem
armazenar mensagens que possam conter dados sensíveis. Para reprocessar após corrigir
a causa, na transação do tenant, execute:

```sql
UPDATE outbox_event
SET status = 'PENDING', attempts = 0, next_attempt_at = clock_timestamp(), last_error = NULL
WHERE id = :event_id AND status = 'FAILED';
```

O intervalo entre ciclos é `fixflow.outbox.poll-delay-ms` (padrão 1000).
`fixflow.outbox.scheduling-enabled=false` desabilita o agendamento, como no perfil
de integração. Cada publicador ativo precisa de conexões para a transação do lock
e a transação do consumidor; dimensione o pool considerando ambas.

Validação: `./mvnw -Dtest=OutboxIntegrationTest,ArchitectureTest test`.


## Cadastro de Owner e empresa (US-001)

`POST /api/contas` é público e recebe JSON:

```json
{
  "companyName": "Oficina Exemplo",
  "fullName": "Maria Silva",
  "email": "maria@example.com",
  "password": "uma-senha-longa"
}
```

Retorna `201` com `userId`, `tenantId` e `role: "OWNER"`. Empresa, identidade e vínculo
nascem ativos, em uma única transação. A identidade criada já pode autenticar em `POST /api/v1/auth/login` e acessar `/api/oficinas/{tenantId}/selecao`. Não cria sessão nem token JWT.

Nomes têm até 150 caracteres; e-mail, até 254. Espaços nas extremidades de nomes/e-mail
são removidos e o e-mail é persistido em minúsculas. A senha exige de 10 a 72 caracteres
e no máximo 72 bytes UTF-8 (limite BCrypt); não é normalizada nem devolvida na resposta.
O hash usa o `PasswordEncoder` BCrypt existente, com custo 12.

Dados inválidos ou JSON malformado retornam `400`. E-mail já cadastrado retorna `409`,
inclusive com diferenças de caixa e em requisições concorrentes. A constraint global
`uq_users_email` decide a unicidade; um conflito desfaz também o tenant recém-inserido.
Uma identidade existente não recebe outra empresa por esse endpoint.

A migration V12 fornece `register_owner` como operação `SECURITY DEFINER` com
`search_path` fixo. A função gera IDs, fixa o papel `OWNER`, configura o tenant durante
a inserção e restaura o contexto anterior. Runtime recebe somente permissão de executar
a função, mantendo as restrições de escrita direta sobre identidades, tenants e vínculos.
A exceção CSRF cobre apenas `POST /api/contas`; outras operações mantêm a proteção atual.

Validação: `./mvnw -Dtest=OwnerRegistrationIntegrationTest,ArchitectureTest test`.


### Organização do cadastro

Os casos de uso ficam em `application.registration.usecase`, com implementação em
`infrastructure.registration.usecase`. Os DTOs de saída ficam em
`application.registration.dto`; os DTOs HTTP de entrada e suas validações ficam em
`web.registration.dto`. Os controllers ficam em `web.registration.controller`.
Cada DTO é uma classe própria, sem declarações aninhadas no controller ou no caso de uso.


## Sessões JWT e refresh (US-002 / FIX-18)

O módulo `modules.identity` separa `domain`, `application/usecase`, `application/port`,
`application/dto`, `infrastructure` e `web/controller`, `web/dto`. `AuthenticateSession`
não conhece Spring, JDBC nem HTTP. Os adaptadores fazem persistência transacional,
BCrypt, CSPRNG/SHA-256 e assinatura/validação JWT com Spring Security Nimbus.

### Contrato HTTP

- `POST /api/v1/auth/login`: `{email, password, tenantId?}`. Retorna `accessToken`,
  `tokenType: "Bearer"`, `expiresIn: 900`, `userId`, `tenantId`, `role` e cookie refresh.
- `POST /api/v1/auth/refresh`: sem corpo obrigatório; recebe cookie `fixflow_refresh`,
  rotaciona e retorna o mesmo contrato com novos tokens.
- Ambos exigem `X-Auth-Request: true`. Se houver `Origin`, deve corresponder exatamente
  a `APP_ORIGIN` (padrão `http://localhost:4200`). A aplicação não habilita CORS permissivo.
  Formulários de outra origem não podem enviar o header; a checagem de origem reforça
  a proteção contra login CSRF e uso indevido do cookie de refresh.
- `401` genérico para credencial inválida, bloqueio, refresh inválido/reutilizado/expirado
  ou identidade sem acesso ativo. `400` para JSON ou campos inválidos; `403` para CSRF/origem.
- Respostas de sessão têm `Cache-Control: no-store`. O refresh nunca aparece no JSON.

JWT: RS256 com `kid`, `iss`, `aud: fixflow-api`, `sub`, `tid`, `role`, `jti`, `iat`, `exp`.
A validação verifica assinatura, emissor, audiência, expiração sem tolerância adicional,
claims obrigatórias e identidade/vínculo/empresa ativos. Alterar a role invalida o JWT
anterior; o refresh emite um novo JWT com a role atual. Tokens são restritos ao tenant
selecionado no login; sem `tenantId`, escolhe-se o vínculo ativo mais antigo.

### Persistência e concorrência

V13 cria `auth_session` e `refresh_token` e adiciona contagem/bloqueio à identidade global.
Runtime não tem acesso direto a essas tabelas; usa funções restritas com `search_path`
fixo. RLS continua habilitado e forçado, com políticas de proprietário explícitas para
as funções de autenticação. A seleção de memberships retorna somente o usuário solicitado.

Refresh: 32 bytes aleatórios, Base64URL sem padding, somente hash SHA-256 armazenado.
Validade deslizante de 7 dias, limitada a 30 dias desde a criação da família. Gerações
consumidas são mantidas durante a vida da família para permitir detectar reuso. Retenção
operacional deve remover somente famílias expiradas e seus tokens, nunca gerações
consumidas de famílias ainda ativas.

Login bloqueia a linha da identidade; refresh bloqueia a família antes da leitura da
geração. Assim a contagem de falhas e a revogação não dependem de locks em memória.
Falhas retornam um resultado da transação; a exceção HTTP só é lançada após commit,
preservando contadores e revogações. Reuso concorrente falha de forma conservadora:
uma requisição pode receber `200`, mas a segunda revoga a família e impede usar o descendente.
No Angular, chamadas concorrentes compartilham uma única renovação. Abas independentes
que disputem a mesma geração podem exigir novo login, conforme essa política de reuso.

Bloqueio: quinta falha dispara 1 minuto; próximas sequências disparam 5 e 15 minutos.
Tentativas durante o bloqueio não prorrogam o prazo. Sucesso zera contagem e progressão.
Identidades inexistentes ou indisponíveis passam por uma verificação BCrypt simulada e
recebem a mesma mensagem genérica. Limitação distribuída por IP é uma evolução separada;
o controle implementado aqui é o bloqueio por conta exigido pela US-002.

A revogação por reuso bloqueia refreshes da família. Access tokens emitidos antes dela
podem durar até os 15 minutos; suspensão de usuário, vínculo ou tenant é checada em cada
requisição. Logout e recuperação de senha pertencem à US-003.

### Execução e testes

`python3 scripts/configure-auth-local.py` gera chaves persistentes para desenvolvimento.
Não execute com segredos de produção. Em produção mantenha HTTPS/cookie Secure, configure
PEMs via `AUTH_PRIVATE_KEY`/`AUTH_PUBLIC_KEY` e a origem exata em `APP_ORIGIN`. O perfil de
integração permite chaves efêmeras e ativa Basic apenas para compatibilidade dos testes
anteriores; T9 desabilita Basic explicitamente.

```bash
./mvnw -Dtest=AuthenticationT9IntegrationTest,ArchitectureTest test
./mvnw verify
```

T9 cobre ausência/expiração/adulteração de JWT, emissor/audiência/tenant inválidos,
rotação, hash e atributos do cookie, reuso com revogação persistida, concorrência,
expiração absoluta, bloqueio e progressão, CSRF/origem e suspensão imediata.

## US-003 — Logout e recuperação de senha

Implementação e configuração SMTP: [README — US-003](../README.md#logout-e-recuperação-de-senha-us-003).
Os testes de integração verificam respostas idênticas, validade de 30 minutos, uso
único sob concorrência, invalidação por novo link, revogação de refresh e CSRF.

## Gestão de usuários e roles (US-004)

O Owner acessa **Gerenciar usuários** na página inicial da empresa. O contrato protegido
por JWT é `/api/oficinas/{tenantId}/usuarios`:

- `GET`: lista `userId`, `fullName`, `email`, `role` e `status` dos vínculos da oficina.
- `POST`: recebe `{fullName, email, password, role}` e retorna `201`. A senha inicial
  exige 10–72 caracteres e até 72 bytes UTF-8; o hash BCrypt nunca é retornado.
- `PATCH /{userId}/role`: recebe `{role}` e retorna `204`.
- `DELETE /{userId}`: suspende o vínculo da oficina e retorna `204`.

Roles atribuíveis: `OWNER`, `ATTENDANT`, `TECHNICIAN`. Roles legadas `ADMIN` e `VIEWER`
continuam legíveis, sem permissões novas, até a conversão explícita pelo Owner.
E-mail duplicado e tentativa de remover/rebaixar o último Owner ativo retornam `409`;
role inválida retorna `400`; usuário fora da oficina retorna `404`. Não é possível
vincular por esse endpoint uma identidade já existente nem alterar sua senha.

A migration V17 concede somente execução de uma função restrita, sem escrita direta
nas tabelas globais. O executor autoriza o tenant e a função revalida o Owner;
um lock da empresa serializa mudanças e protege o último Owner contra concorrência.
Criação, alteração e desativação geram auditoria na mesma transação. A suspensão
é local à oficina; outros vínculos da identidade permanecem independentes.
O JWT anterior à troca de role ou suspensão é rejeitado na requisição seguinte.
A renovação segue a validação de vínculo da US-002.

`RolePermissions` centraliza a matriz da seção 5. O conversor JWT carrega permissões
somente após revalidar o vínculo ativo. Method security está habilitado para os casos
de uso dos próximos módulos. O filtro já exige `workorder:create` em
`POST /api/v1/work-orders` e `finance:read` em `/api/v1/finance/**` e
`/api/v1/dashboard/**`. Os módulos de OS/financeiro ainda não existem; T5 usa
controllers de teste nessas rotas para exercitar a cadeia real de segurança,
com JWTs reais, incluindo respostas positivas para os perfis permitidos.
As restrições por OS atribuída, escrita limitada e justificativa serão aplicadas
pelos respectivos módulos, além da permissão da matriz.

Escritas de usuários com Bearer dispensam CSRF, pois o cookie refresh não autentica
essas rotas. Autenticação Basic dos testes mantém a exigência de CSRF.

Validação: `./mvnw -Dtest=UserManagementT5IntegrationTest,ArchitectureTest test`.

## Configurações da empresa (US-005)

Referências: documento técnico, seções **5** (RBAC), **11.5** (arquivos), **12.2**
(módulo tenants), **16.2** (API), **17.4** (validade padrão de 7 dias) e **19.3**
(estoque negativo e concorrência). A tela fica em **Configurações** (`/inicio/configuracoes`), no menu lateral
para o Owner, em uma página independente da Visão Geral. O logo salvo também aparece no menu e é carregado novamente após login.

### Contrato

`GET /api/v1/tenant` e `PUT /api/v1/tenant` exigem JWT e `tenant:manage` (Owner).
O tenant vem exclusivamente da identidade autenticada; body, query e headers de
identificação não selecionam outra empresa. PUT recebe e devolve o documento inteiro:

```json
{
  "logoDataUrl": null,
  "warrantyTerms": "Condições de garantia da empresa.",
  "quoteValidityDays": 7,
  "allowNegativeStock": false
}
```

`null` remove o logo; texto vazio remove os termos. Empresas novas e existentes sem
configuração explícita recebem termos vazios, validade de **7 dias** e estoque
negativo **bloqueado**. Limites adotados para esta US: validade inteira de **1 a 365
dias**, termos de até **10.000 caracteres**, logo PNG/JPEG de até **512 KiB** e
**2048 × 2048 pixels** (também limitado após reprocessamento). Dados inválidos retornam
`400`, ausência de autenticação `401`, perfil sem permissão `403`. PUT com Bearer
dispensa CSRF, como a gestão de usuários; cookies refresh não autenticam esta rota.

As migrations V18/V19 criam configurações separadas por tenant com FORCE RLS.
O estado final guarda **somente a referência privada do logo**, nunca o arquivo no
PostgreSQL. Identidades e dados da empresa continuam em módulos diferentes.
Preferências e evento `TENANT_SETTINGS_UPDATED` são gravados atomicamente; falha na
auditoria desfaz a mudança e remove o novo arquivo enviado.

### Arquivos privados

A porta `FileStorage` fornece armazenamento privado por tenant. O adaptador de
desenvolvimento usa volume local em `FILE_STORAGE_ROOT` (padrão `./storage`);
o Compose monta `fixflow_private_files` em `/app/storage` para sobreviver à recriação
do container. Não exponha essa pasta pelo servidor web. A imagem é validada por
formato declarado e leitor de imagem, decodificada e regravada como PNG sem EXIF/GPS.
Chaves são geradas pelo servidor: `tenants/{tenantId}/logos/{uuid}.png`; o adaptador
recusa chaves de outro tenant e caminhos livres. As imagens são devolvidas como data
URL apenas pelas respostas autenticadas de configurações/seleção de oficina.
Nenhuma URL pública permanente é criada. Logos substituídos ficam privados para
preservar futuras referências de documentos; retenção/limpeza deve considerar essas
referências. Em produção, o adaptador S3 privado e URLs temporárias da seção 11.5
continuam sendo a integração de infraestrutura a implementar, atrás desta porta.

### Aplicação das regras

`TenantPolicies.quoteDefaults(tenantId, issuedAt)` consulta os valores **persistidos**
e retorna um snapshot imutável com `validUntil`, termos e logo. A validade é calculada
por dias completos a partir da emissão. A criação/envio do orçamento deverá guardar
esse snapshot; alterações posteriores na empresa não recalculam versões enviadas.

`TenantPolicies.remainingStock(tenantId, balance, quantity)` consulta a configuração
persistida, exige quantidade positiva e rejeita saldo resultante negativo, exceto
quando a opção estiver habilitada. `InsufficientStockException` corresponde a
`422 / INSUFFICIENT_STOCK` na API.

Os módulos de orçamento, documentos e estoque **ainda não estão implementados**.
Essas políticas estão exercitadas contra o PostgreSQL e prontas para esses consumidores;
não há endpoints fictícios de emissão ou consumo. Ao implementar a baixa real, use o
UPDATE condicional da seção 19.3 e grave movimento/item na mesma transação: o cálculo
isolado de saldo não substitui a proteção contra concorrência. Como o executor abre
`REQUIRES_NEW`, não use esta chamada como operação de escrita ou lock de estoque.

### Validação

```bash
./mvnw -Dtest=CompanySettingsIntegrationTest,ArchitectureTest,TenantSelectionIntegrationTest test
```

Cobertura: defaults, round-trip, isolamento entre empresas, Owner/Atendente/Técnico,
suspensão, validação, logo privado/removível, auditoria/rollback e efeitos da alteração
persistida sobre validade, termos e saldo permitido. O frontend verifica edição,
validação, salvamento, descarte e recuperação de falhas.

## Limites de plano (US-006)

V20 adiciona `tenants.plan`: Starter (padrão) permite 2 vínculos ativos, incluindo
Owner, e 100 OS/mês; Professional permite 6 e 500; Business não impõe teto.
A alteração de plano é administrativa, sem endpoint público. Vínculos suspensos
não ocupam vaga. Reduzir o plano preserva leitura e edição dos dados existentes.
O trigger de memberships serializa novas vagas com lock do tenant, inclusive
para inserções diretas. A API retorna 409 com `code=PLAN_LIMIT_REACHED` e mensagem
de upgrade, exibida na tela de usuários.

O módulo de OS ainda não existe. Sua criação deve usar
`TenantLimitsService.createWorkOrder(tenantId, () -> inserirOS())`: a operação
valida permissão e consome quota na mesma transação da inserção; falhas revertem
a contagem. O callback deve usar a transação corrente, sem abrir outra transação.
A janela é o mês calendário de America/Sao_Paulo. Exclusões/cancelamentos não
restituem quota. Nenhuma checagem de plano é aplicada em consultas.

## Clientes e contatos (US-010)

Migration V21 cria `customers`, `customer_contacts` e a base de persistência
`work_orders` (vínculo com cliente e estado; abertura de OS permanece na US-020).
Todas têm RLS obrigatório e chaves compostas impedem vínculos entre empresas.

API autenticada no tenant selecionado: `GET/POST /api/v1/customers` e
`GET/PUT/DELETE /api/v1/customers/{id}`. Owner/Atendente podem escrever; Técnico
pode consultar. `q` busca nome sem diferenciar maiúsculas e telefone/documento
normalizados para dígitos. `page` começa em zero, `size` vai de 1 a 100 (padrão
20), com ordenação estável por nome e ID. Resposta contém `content`,
`totalElements`, `page` e `size`. PUT substitui os dados e a lista de contatos
(até 20, tipos PHONE/WHATSAPP/EMAIL). Documento é opcional; nome é obrigatório.

DELETE marca `deleted_at`, preservando os dados e contatos. Clientes excluídos
somem das consultas e retornam 404 em leitura/edição/exclusão. OS em qualquer
estado além de ENTREGUE/CANCELADA bloqueiam exclusão com 409 e mensagem na tela.
Triggers e locks de cliente protegem a regra também contra inserção concorrente
de OS. O futuro módulo de OS deve reutilizar `work_orders` e o serviço de quota
US-006, mantendo a inserção na mesma transação. Mutações de cliente geram auditoria
sem nome, documento ou contatos no payload. Interface: `/inicio/clientes`.

## Equipamentos (US-011)

V22 cria `equipment`, com RLS obrigatório e FK composta `(tenant_id, customer_id)`
para `customers`. Vínculo é obrigatório e nunca atravessa empresas, inclusive
por SQL direto. Cadastro e troca de cliente exigem cliente ativo; locks e trigger
serializam o vínculo com exclusão lógica concorrente do cliente. Equipamentos
anteriores continuam consultáveis quando o cliente é excluído, preservando o vínculo.

API no tenant selecionado: `GET/POST /api/v1/equipment`,
`GET/PUT/DELETE /api/v1/equipment/{id}` e
`GET /api/v1/customers/{customerId}/equipment`. Busca `serial` parcial e sem
sensibilidade a maiúsculas; filtro opcional `customerId`; paginação `page` base
zero e `size` 1–100 (padrão 20), ordenação estável por modelo/ID. Campos:
`customerId`, `category` (CELULAR/NOTEBOOK/DESKTOP/IMPRESSORA/PERIFERICO/OUTRO), `brand`,
`model`, `serialNumber`, `description`. Cliente, categoria e modelo obrigatórios.
Série é opcional e não impõe unicidade. Acessórios e estado físico pertencem à OS.

Owner/Atendente têm CRUD completo. Técnico consulta e pode alterar somente a
`description` via `PATCH /api/v1/equipment/{id}/description`; não pode alterar
cliente, série ou identificação. DELETE é lógico, com 404 nas consultas futuras.
Mutações geram auditoria sem conteúdo dos campos. Interface em
`/inicio/equipamentos`, com seleção paginada de clientes, filtro por série e
atalho pelo nome do cliente na listagem de clientes.

## Histórico do cliente (US-012)

`GET /api/v1/customers/{customerId}/history` retorna uma linha do tempo unificada
de OS e equipamentos vinculados ao cliente do tenant autorizado. Filtros:
`type=ALL|WORK_ORDER|EQUIPMENT` (padrão ALL), `sort=asc|desc` (padrão desc),
`page` base zero e `size` de 1 a 100 (padrão 20). Ordenação por `created_at`,
com desempate por tipo e UUID para garantir estabilidade entre páginas. V24
adiciona índices por tenant, cliente e data nas duas tabelas.

Resposta: cliente, `content`, `totalElements`, `page`, `size`, `sort` e
`workOrdersVisible`. Cada registro possui `id`, `type`, `createdAt`, `title`,
`status`, `serialNumber` e `deleted`. Inclui OS abertas/encerradas/canceladas e
mantém equipamentos excluídos logicamente, sinalizados como excluídos. A data é
a de criação do registro; o status de OS é o atual. Não representa eventos de
mudança de status ou histórico de transferência de propriedade do equipamento.
Cliente inexistente, excluído ou de outro tenant retorna 404 idêntico.

Owner e Atendente veem ambos os tipos. Técnico vê apenas equipamentos; os
contadores também excluem OS, e solicitar `type=WORK_ORDER` retorna 403. Quando
a US-020 implementar a atribuição de técnicos às OS, o histórico poderá adotar
esse escopo sem expor OS não atribuídas. A US-020 ainda está pendente: a consulta
usa a base real `work_orders` existente e não cria dados nem protocolos fictícios.

Interface: clique no nome do cliente para abrir
`/inicio/clientes/{customerId}/historico`, com filtros, datas, status, paginação
e atalho para os equipamentos do cliente.

## Exportação e anonimização do cliente (US-013)

Somente OWNER possui `customer:privacy`. Endpoints:

- `GET /api/v1/customers/{id}/export`: arquivo JSON, `Content-Disposition:
  attachment` e `Cache-Control: no-store`, com versão do formato, data de
  exportação, cadastro, contatos, equipamentos (inclusive excluídos logicamente)
  e todas as OS. Sem paginação para não omitir registros. Gera `CUSTOMER_EXPORTED`
  na auditoria. Whitelist dos campos evita exportar credenciais ou dados de outros clientes.
- `POST /api/v1/customers/{id}/anonymize`, corpo `{"confirmed":true}`: retorna 204.
  Exige confirmação explícita e bloqueia com 409 se qualquer OS não estiver em
  ENTREGUE/CANCELADA. Define nome `Cliente anonimizado`, remove documento e
  contatos, limpa marca/modelo/série/descrição dos equipamentos e retira cliente
  e equipamentos dos cadastros ativos. Mantém IDs, vínculos, categoria, datas e
  OS sem alterações. `anonymized_at` marca a ação. Repetir a operação é idempotente.

Mutações e `CUSTOMER_ANONYMIZED` são atômicos: falha na auditoria reverte todos
os campos e contatos. Auditoria guarda ator, tenant, cliente, ação e data, sem
copiar o conteúdo pessoal removido. V25 impede reintroduzir dados nos registros
anonimizados e protege a regra de OS aberta no banco. Locks do cliente serializam
anonimização com novos vínculos de OS/equipamentos/contatos. Histórico continua
disponível pelo ID, com os campos anonimizados. Exportação e anonimização também
aceitam clientes excluídos logicamente, sem expor outro tenant.

Na página de histórico, o proprietário pode baixar o JSON e confirmar a ação
permanente. Após anonimizar, o histórico permanece acessível e a ação fica desabilitada.
Não há tabelas financeiras implementadas ainda; nenhuma tabela de OS ou financeira
é alterada pela anonimização. A exportação cobre os dados hoje existentes. Novos
campos pessoais e módulos financeiros deverão integrar sua política de retenção,
exportação e remoção. Cópias já baixadas e backups seguem seu ciclo de retenção,
fora da alteração transacional do cadastro.

## US-020 — Abertura de ordem de serviço

`POST /api/v1/work-orders` está disponível para `OWNER` e `ATTENDANT`.
O corpo recebe `customerId`, `equipmentId`, `reportedIssue` (até 4000 caracteres),
`receivedAccessories` (lista, até 50 itens de 150 caracteres) e `physicalCondition`
(até 2000 caracteres). Defeito e estado físico são obrigatórios; nenhum acessório
é representado por `[]`. Cliente e equipamento precisam estar ativos na empresa,
e o equipamento deve pertencer ao cliente informado. Referências indisponíveis
retornam 404; dados inválidos retornam 400.

A resposta 201 contém `Location`, identificação, número, estado `RECEBIDA`, dados
de recebimento, `openedAt` e `version: 0`. `GET /api/v1/work-orders/{id}` permite
consultar a OS a proprietários e atendentes. A interface oferece o formulário em
`/inicio/ordens/nova`, acessível pelo menu e pelo botão Nova ordem de serviço.

A operação reutiliza `TenantLimitsService.createWorkOrder` como fronteira
transacional autorizada: quota, contador por tenant (`INSERT ... ON CONFLICT ...
UPDATE ... RETURNING`), OS, histórico inicial (`from_status` nulo, origem `USER`),
auditoria `WORK_ORDER_CREATED` e outbox `WorkOrderCreated` são gravados juntos.
Falhas revertem a operação inteira. Não há executor de tenant aninhado, pois ele
abriria uma transação independente. RLS isola contadores e histórico; a role de
execução só pode consultar e acrescentar histórico.

A migration V26 estende a base `work_orders`, preservando os registros anteriores
e numerando-os por empresa em ordem de criação. Os campos de recebimento ficam
opcionais no schema para compatibilidade com esses registros legados; a API exige
os dados completos para novas aberturas. As chaves compostas impedem referências
entre empresas. `WorkOrderT1IntegrationTest` cobre criação, sequência por empresa,
evento, histórico, auditoria, permissões, referências inválidas e concorrência.

A consulta de OS está disponível em `/inicio/ordens/:orderId`, acessível pelo
link da ordem no histórico do cliente. A tela carrega `GET /api/v1/work-orders/{id}`
e apresenta os dados do recebimento, cliente, identificação do equipamento e
status atual, com retorno ao histórico. A resposta da API inclui `customerName`,
`equipmentLabel` e `serialNumber`; as associações usam tenant e ID, inclusive
para registros com exclusão lógica. Falhas 403/404 e indisponibilidade da API
são tratadas sem mostrar dados de uma consulta anterior.


## US-021 — Máquina de estados da OS

`PATCH /api/v1/work-orders/{id}/status` recebe:

```json
{
  "toStatus": "EM_DIAGNOSTICO",
  "version": 0,
  "reason": null,
  "paymentOverride": false
}
```

`version` é obrigatório e deve ser o valor retornado pela consulta da OS. O
endpoint autoriza o vínculo atual, bloqueia a linha da OS e atualiza com
`WHERE version = ?`, incrementando a versão. Assim, duas requisições com a mesma
versão têm apenas um vencedor. Requisições antigas retornam 409 com
`WORK_ORDER_VERSION_CONFLICT`. A persistência usa JDBC, portanto aplica a
comparação de versão explicitamente em vez de uma anotação JPA `@Version`.

A classe de domínio `WorkOrderStateMachine` centraliza a tabela da seção 14,
as permissões e os guards. Aresta inexistente, estado terminal ou pré-condição
não satisfeita retorna 409 `INVALID_STATE_TRANSITION`; falta de permissão retorna
403; OS de outro tenant ou inexistente retorna 404. Apenas proprietário e técnico
atribuído executam etapas técnicas. Proprietário e atendente executam as etapas
de recepção. Aprovação manual exige justificativa. O contexto de domínio
`CUSTOMER` é restrito a aprovar/rejeitar: não pode ser escolhido pelo endpoint
autenticado e sua integração com link público pertence ao módulo de orçamento.

Cancelamento segue a linha “estados pré-manutenção” da tabela 14.2: RECEBIDA,
EM_DIAGNOSTICO, AGUARDANDO_ORCAMENTO, AGUARDANDO_APROVACAO, APROVADA,
ORCAMENTO_REJEITADO e SEM_REPARO. Exige motivo e ausência de início de manutenção
ou consumo de peças. O diagrama 14.1 mostra apenas os quatro primeiros casos;
a implementação segue a regra mais abrangente da tabela. ENTREGUE e CANCELADA
não permitem saída nem repetição do mesmo estado.

Cada alteração grava histórico com origem USER, ator, motivo, estados anterior e
novo, auditoria `WORK_ORDER_STATUS_CHANGED` e o evento especificado na seção 14.
A volta de ORCAMENTO_REJEITADO para AGUARDANDO_ORCAMENTO não tem evento definido
na tabela, mas continua sendo auditada. Falhas na outbox revertem status, versão,
histórico e auditoria. Entrega define data e, em reparos prontos, prazo de garantia.
Uma exceção de pagamento só pode ser autorizada pelo proprietário, com motivo,
e fica registrada no histórico/auditoria/evento.

### Contrato de integração dos guards

Atribuição, diagnóstico e itens executados já fornecem suas evidências reais.
Orçamento e pagamentos ainda precisam fornecer seus dados de negócio. A migration V27 cria a projeção
interna `work_order_transition_facts` para esse contrato. Ela **não é editável por
endpoint nem por flags do PATCH**. Cada módulo deve gravar sua evidência na mesma
transação da operação real, bloqueando primeiro a OS e depois a projeção. A
máquina não fabrica diagnósticos, itens, pagamentos ou baixas para liberar etapas.
Ausência de evidência bloqueia a transição com 409. Não há valores iniciais que
considerem pagamento quitado ou taxa de diagnóstico registrada automaticamente.
A taxa, mesmo isenta, deve ter registro explícito. Garantia usa os dias gravados
pela operação de negócio (zero significa sem prazo adicional).

A projeção armazena técnico (FK de vínculo por tenant, validado como ativo e com
papel técnico/proprietário), diagnóstico, referência e versão do orçamento, itens,
total, validade e decisão, serviços, consumo/baixa de peças, quitação, registro de
taxa, peça faltante e início de manutenção. Revisão revoga token e limpa a referência
ao orçamento anterior, incrementando sua versão; um novo envio exige nova evidência.
Envio gera token aleatório de 256 bits: só o hash SHA-256 fica na projeção; o token
original segue no evento QuoteSent para o consumidor de notificação, sem auditoria
ou logs. Aprovação, rejeição, cancelamento, revisão e entrega revogam o token.
A API pública de orçamento e o consumo desse token não fazem parte desta US.

A tela de consulta oferece transições por perfil, justificativa e exceção de
pagamento para o proprietário. A lista mostra as arestas possíveis; o servidor
verifica as pré-condições ao confirmar. Conflito de versão recarrega a consulta.

Validação: `WorkOrderStateMachineTest` cobre os 144 pares de estados, as 22
arestas permitidas, papéis e guards; `WorkOrderT4IntegrationTest` verifica o caso
T4 RECEBIDA → EM_MANUTENCAO, ciclo completo, efeitos, revisão de token, isolamento,
concorrência e rollback após falha de outbox. As evidências dos módulos futuros
são provisionadas diretamente pelas fixtures de teste, sem endpoints de bypass.


### Atribuição de técnico na consulta da OS

O painel **Técnico responsável** permite que proprietário e atendente atribuam um
usuário ativo de papel `TECHNICIAN` ou `OWNER` da mesma empresa. A lista usa
`GET /api/v1/work-orders/technicians` e expõe somente `userId` e `fullName`.
A migration V28 oferece uma função restrita ao tenant para esses dados, sem
conceder leitura da tabela global `users` à role de execução.

`PATCH /api/v1/work-orders/{id}/technician` recebe `technicianId` e `version`.
A atribuição atualiza a projeção dos guards, incrementa a versão da OS e grava
`WORK_ORDER_TECHNICIAN_ASSIGNED` na auditoria e `WorkOrderTechnicianAssigned` na
outbox na mesma transação. Preserva diagnóstico e demais evidências existentes.
OS encerradas não aceitam atribuição; técnico inexistente, suspenso, sem papel
técnico ou de outra empresa é rejeitado. A lista e o guard verificam tanto o
vínculo quanto o status global do usuário. A consulta retorna `technicianId` e
`technicianName`. Após atribuir, a interface usa a nova versão para iniciar o
diagnóstico. O técnico não pode se autoatribuir por este endpoint.


## US-022 — Atribuir técnico e minha fila

A atribuição permanece em `PATCH /api/v1/work-orders/{id}/technician`, restrita a
OWNER/ATTENDANT, com técnico ativo da mesma empresa, versão, auditoria e outbox.
`GET /api/v1/work-orders` lista as OS com paginação (`page=0`, `size=20`, máximo
100), `technicianId`, `status` e `openOnly=true`. Por padrão exclui ENTREGUE e
CANCELADA; `openOnly=false` inclui encerradas. Ordena por criação decrescente e
ID decrescente para desempate. Retorna `content`, `totalElements`, `page` e `size`.

RBAC é aplicado antes de consultar conteúdo ou totais: OWNER/ATTENDANT, com
`workorder:read`, podem consultar todas as OS da empresa e filtrar por técnico.
TECHNICIAN, com `workorder:read_assigned`, recebe obrigatoriamente o filtro da
própria identidade autenticada. Pedir outro `technicianId` retorna 403; omitir o
parâmetro jamais amplia o escopo. Filtros de estado, encerradas ou paginação
mantêm essa restrição. OS sem atribuição ou atribuída a outro técnico não aparece.
Reatribuição remove o acesso do técnico anterior tanto na fila quanto na consulta
e transições da OS. Totais também são calculados sobre o escopo autorizado.
O histórico do cliente continua sem expor OS aos técnicos; sua entrada de consulta
às OS atribuídas é a fila. A migration V29 adiciona os índices da listagem.

A interface `/inicio/ordens` exibe **Minha fila** para técnicos e **Ordens de
serviço** para proprietário/atendente, com estado, paginação, opção de incluir
encerradas e links para consulta. Somente proprietário/atendente recebem o filtro
de técnico e o atalho de abertura. Requisições anteriores são canceladas ao trocar
filtros. Estados sem resultados, carregamento e erros têm mensagens próprias.
`WorkOrderQueueIntegrationTest` verifica escopo obrigatório, filtro explícito de
outro técnico, totais, paginação, reatribuição, permissões e isolamento entre tenants.


## US-023 — Registrar diagnóstico

Na consulta da OS, **Diagnóstico técnico** recebe achados, causa provável e
solução proposta (todos obrigatórios, até 4000 caracteres cada). Proprietário e
técnico atualmente atribuído podem registrar/revisar apenas em `EM_DIAGNOSTICO`.
Atendente pode consultar; técnico sem atribuição não acessa conteúdo nem histórico.
Cada gravação acrescenta uma revisão imutável, com autor e data, em vez de alterar
o registro anterior. A última revisão aparece na consulta; o botão de histórico
carrega as revisões anteriores, da mais recente à mais antiga.

- `POST /api/v1/work-orders/{id}/diagnosis`: recebe `version`, `findings`,
  `probableCause` e `proposedSolution`. Retorna 201 com `diagnosis` e
  `workOrderVersion` atualizado.
- `GET /api/v1/work-orders/{id}/diagnosis`: lista o histórico autorizado.
- A resposta de consulta da OS inclui `diagnosis`, nulo enquanto não registrado.

A migration V30 cria `work_order_diagnoses` com RLS, FK composta por tenant/OS,
revisão única por OS e somente SELECT/INSERT para a role de execução. O serviço
bloqueia primeiro a OS e valida atribuição, estado e versão. Revisão, incremento
da versão, auditoria `WORK_ORDER_DIAGNOSIS_RECORDED` e evento `DiagnosisRecorded`
são atômicos. Conflito de versão retorna 409 `WORK_ORDER_VERSION_CONFLICT`.
Gravar diagnóstico não muda o estado automaticamente: o evento
`DiagnosisCompleted` continua sendo emitido na transição para orçamento.

O guard `EM_DIAGNOSTICO → AGUARDANDO_ORCAMENTO` agora deriva sua evidência da tabela
de diagnósticos, cujos três campos são obrigatórios. A antiga coluna textual
`work_order_transition_facts.diagnosis` deixa de ser fonte de autorização: flags
no PATCH ou texto legado na projeção não liberam a etapa. Não há migração que
invente causa provável ou solução para valores legados; é necessário registrar
um diagnóstico completo pela nova operação.

`WorkOrderDiagnosisIntegrationTest` verifica registro, revisão, preservação de
histórico, autor/data, guard real, leitura por atendente, técnico atribuído,
reatribuição, isolamento entre tenants, validação, concorrência e rollback.
As fixtures do T4 agora fornecem diagnóstico completo na tabela canônica.

## US-024 — Serviços, peças e consumo de estoque

A migration `V31` cria `parts`, `work_order_items` e o livro-razão
`inventory_movements`, com RLS obrigatório e chaves compostas por empresa.
A role de execução só pode consultar e inserir movimentações: não pode alterá-las
nem apagá-las. O item removido preserva seu registro com `removed_at`.

### API e acesso

- `GET /api/v1/work-orders/{id}/items`: itens ativos e `workOrderVersion`;
  proprietário/atendente consultam; técnico consulta apenas sua OS atribuída.
- `POST /api/v1/work-orders/{id}/items`: proprietário ou técnico atribuído,
  exclusivamente em `EM_MANUTENCAO`. Corpo: `id` (UUID estável para retry),
  `version` da OS, `type` (`SERVICE`/`PART`), `partId` (somente para peça),
  `description`, `quantity` positiva (até três casas), `unitPrice` não negativo
  (até duas casas). O custo da peça é obtido do catálogo pelo servidor.
- `DELETE /api/v1/work-orders/{id}/items/{itemId}?version=N`: mesmas permissões
  e estado; remove o item e, se peça, devolve sua quantidade com movimento `RETURN`.
- Inclusão/remoção retornam a lista ativa e a nova `workOrderVersion`.

A operação trava a OS antes de conferir versão e atribuição. A baixa utiliza
`UPDATE parts ... WHERE quantity_on_hand >= :quantity OR allow_negative_stock`.
Saldo, `EXIT`, item, custo histórico, versão da OS, projeção de serviços/peças,
auditoria e outbox participam da **mesma transação** do executor autorizado.
Falha em qualquer etapa desfaz tudo. Estoque insuficiente retorna
`422 INSUFFICIENT_STOCK`. Negativo depende da preferência explícita da empresa;
na ausência de configuração, fica proibido. A remoção usa o custo histórico do
item, gera uma única devolução e recalcula a evidência para a transição `PRONTA`.

O UUID do item funciona como chave idempotente: repetir a inclusão com os mesmos
dados não consome novamente nem incrementa a versão. Reutilizá-lo com dados
diferentes retorna `409`. Repetir após remover não reativa o item. Repetir a
remoção não devolve o estoque duas vezes. A identidade e a atribuição continuam
sendo verificadas nas repetições. O frontend conserva o UUID em falhas de rede e
atualiza a versão da OS após cada alteração.

### Base da dependência US-040

O catálogo de peças ainda não existia. Esta entrega inclui o recorte necessário:
`GET /api/v1/parts` para perfis com `inventory:read` e `POST /api/v1/parts` para o
proprietário (`inventory:write`). Cadastro recebe `sku`, `name`, `unit`, `cost`,
`salePrice`, `quantity` inicial e `minQuantity`. SKU é normalizado para maiúsculas
e único por empresa. Saldo inicial positivo gera `ENTRY` na mesma transação;
não há alteração livre do saldo. Edição/inativação do catálogo e novas entradas
ou ajustes após o cadastro continuam como evolução das US-040/US-041.

Na tela da OS, **Serviços e peças → Ver itens da OS** permite consultar os itens,
registrar serviços, selecionar peças com saldo, informar preço/quantidade e
remover itens. O proprietário também pode cadastrar a peça com saldo inicial
nesse painel. Atendentes e ordens fora de manutenção têm consulta somente.
O total mostrado corresponde aos itens executados, não a pagamentos ou orçamento.

### Validação

`WorkOrderItemsT6T7IntegrationTest` usa PostgreSQL real e cobre T6 (baixa e
reconciliação do livro-razão, insuficiência sem persistência, falha posterior à
baixa e rollback de devolução), T7 (duas OS concorrentes para a última unidade:
exatamente um `201` e um `422`), idempotência, quantidades fracionárias, estoque
negativo configurável, serviços e liberação de `PRONTA`, versão, atribuição,
perfis e peça de outra empresa. Os testes do painel Angular verificam retry com
UUID estável, remoção com versão atual, consulta somente e estoque insuficiente.

```bash
./mvnw -Dtest=WorkOrderItemsT6T7IntegrationTest,WorkOrderT4IntegrationTest,WorkOrderDiagnosisIntegrationTest,ArchitectureTest test
```

## US-025 — Anexos e fotos da OS

A migration `V32` adiciona `work_order_attachments`: apenas metadados, checksum
SHA-256, referência privada no S3, autor e data. RLS obrigatório e FK composta
impedem referências a OS de outras empresas. Os bytes não ficam no PostgreSQL.
O nome original é sanitizado (sem diretórios/caracteres de controle, até 180
caracteres), e a chave usa UUID gerado pelo servidor:
`tenants/{tenantId}/work-orders/{orderId}/{uuid}.{jpg|png|pdf}`.

### API

- `GET /api/v1/work-orders/{id}/attachments`: lista os metadados, sem chaves S3
  nem URLs permanentes. Proprietário e atendente consultam suas OS; técnico,
  somente as atribuídas a ele.
- `POST /api/v1/work-orders/{id}/attachments`: multipart com o campo `file`;
  `uploadId` UUID opcional permite retry idempotente. Os três perfis têm
  `workorder:attach`, e o técnico continua limitado à atribuição. Retorna `201`
  com os metadados e `Location` do endpoint de link temporário.
- `GET /api/v1/attachments/{id}/download-url`: confere novamente empresa,
  vínculo e atribuição antes de assinar. Retorna `url` e `expiresAt`, com
  `Cache-Control: no-store`. A assinatura SigV4 do SDK AWS dura **300 segundos**;
  `Content-Disposition: attachment`, MIME e cache são fixados pelo servidor.
  As URLs assinadas não são registradas em auditoria nem logs.

### Validação e limites

O arquivo recebido deve ter **até 10 MiB (10.485.760 bytes)**, ser não vazio e
ter extensão, MIME declarado e tipo detectado pelos bytes concordantes. O Apache
Tika recebe apenas os bytes, sem o nome fornecido pelo cliente. Tipos aceitos:
JPEG (`.jpg`/`.jpeg`), PNG, WebP e PDF. Executável disfarçado de `.jpg`, MIME falso,
conteúdo truncado/corrompido e tipos fora da lista retornam `422`.

Imagens são decodificadas e regravadas somente a partir dos pixels, eliminando
EXIF/GPS e conteúdo anexado. Há limite de 20 megapixels antes da decodificação
para conter imagens comprimidas desproporcionais. JPEG permanece JPEG;
PNG e WebP são gravados como PNG. O nome usado no download tem extensão coerente
com o conteúdo armazenado. O arquivo processado também deve caber em 10 MiB.
PDFs passam pelo PDFBox e são regravados; documentos criptografados, sem páginas,
com JavaScript, ações executáveis, links com protocolos inseguros ou arquivos
incorporados são rejeitados. Links HTTP, HTTPS e e-mail são aceitos.
O processamento limita a travessia a 100 mil objetos. Isto não representa uma
varredura antivírus; ClamAV/GuardDuty continuam na evolução prevista do projeto.

Há **20 anexos por OS**: a operação trava a OS antes de conferir a quantidade e
persistir. A tabela também restringe os slots a 1–20 com unicidade por empresa/OS.
Dois uploads para o último slot produzem um `201` e um `409`, sem ultrapassar 20.
Excesso de tamanho retorna `413`; falta do campo `file`, `400`.

O painel Angular envia `uploadId` estável enquanto o mesmo arquivo aguarda
confirmação. Repetir os mesmos bytes/nome com esse identificador retorna o anexo
existente, inclusive quando a OS já atingiu 20. Reutilizar a chave com outros dados
retorna `409`. O checksum do arquivo original controla retry; o checksum exposto
corresponde ao conteúdo processado efetivamente gravado.

### Consistência entre banco e S3

O `PUT` privado é feito após a autorização e antes do commit de metadados e
auditoria. Um rollback conhecido dispara `DeleteObject` compensatório, também
quando um timeout do PUT pode ter ocorrido após a aceitação pelo S3. Falha no
armazenamento não publica metadados. Não há transação distribuída entre S3 e
PostgreSQL: encerramento abrupto do processo, resultado de commit desconhecido
ou falha na compensação podem deixar objetos órfãos. A falha de compensação é
registrada somente pela chave técnica. A operação deve reconciliar periodicamente
objetos antigos com `storage_key` no banco, sem remover objetos recentes ou
referenciados. A retenção por data de entrega pertence à política de ciclo de
vida do produto; o template não expira indiscriminadamente anexos válidos.

### Configuração do S3 privado

Sem bucket configurado, a aplicação continua funcionando e a consulta de anexos
continua disponível; upload e emissão de links retornam `503` com mensagem de
configuração. Não existe fallback público nem gravação silenciosa no disco.

O template [`infra/attachments-s3.yaml`](infra/attachments-s3.yaml) cria um bucket
com os quatro bloqueios de acesso público, propriedade `BucketOwnerEnforced`,
criptografia SSE-S3/AES256 e política que exige TLS. O bucket é preservado se a
stack for removida. O template também disponibiliza uma política IAM de execução
para ler a configuração de privacidade e executar `PutObject`, `GetObject` e
`DeleteObject` somente no prefixo de anexos.

Provisionamento, executado pelo responsável pela conta AWS:

```bash
aws cloudformation deploy \
  --template-file docs/infra/attachments-s3.yaml \
  --stack-name fixflow-attachments \
  --region sa-east-1 \
  --capabilities CAPABILITY_IAM
aws cloudformation describe-stacks \
  --stack-name fixflow-attachments \
  --region sa-east-1 \
  --query 'Stacks[0].Outputs'
```

Anexe `RuntimePolicyArn` à role IAM da aplicação e configure:

```text
ATTACHMENTS_ENABLED=true
S3_BUCKET=<BucketName retornado pela stack>
S3_REGION=sa-east-1
```

O SDK usa a cadeia padrão de credenciais AWS; prefira role da aplicação. Em
desenvolvimento, um perfil AWS já configurado pode ser selecionado por
`AWS_PROFILE`, sem colocar chaves no repositório. O Compose deste repositório está preparado para desenvolvimento com LocalStack.
Em AWS, configure essas variáveis na workload sem endpoints locais ou credenciais
fictícias; as credenciais devem ser provisionadas pelo ambiente de execução. O adaptador verifica os quatro bloqueios públicos antes
de upload e assinatura, envia SSE-S3 e checksum SHA-256 e nunca define ACL pública.
A opção S3 desabilitada é o padrão até existir um bucket privado provisionado.

Referências de implementação: [URLs pré-assinadas no SDK AWS Java](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html),
[Apache Tika](https://tika.apache.org/) e [PDFBox 3](https://pdfbox.apache.org/3.0/migration.html).

### Testes

- `AttachmentValidatorT12Test`: executável com JPEG falso, divergência de MIME,
  arquivos vazios/corrompidos, limite exato de 10 MiB, sanitização, re-encode de
  JPEG/PNG/WebP, PDF válido e PDF com scripts.
- `S3FileStorageTest`: cliente S3 simulado com presigner real; verifica SSE-S3,
  checksum, ausência de ACL pública, recusa de bucket sem bloqueios e URL SigV4
  com `X-Amz-Expires=300` e cabeçalhos fixos.
- `WorkOrderAttachmentsT12IntegrationTest`: PostgreSQL real e storage simulado;
  cobre T12 antes de qualquer gravação externa, limites, concorrência pelo 20º
  slot, retry idempotente, isolamento, atribuição do técnico, acesso de atendente,
  erro no storage e compensação após rollback de auditoria.
- Painel Angular: listagem, link solicitado sob demanda, upload multipart sem
  sobrescrever o boundary, retry com UUID estável, limite de tamanho/quantidade
  e apresentação de erro de validação do backend.

Os testes não dependem de bucket nem usam credenciais AWS reais. A validação
contra um bucket real deve ocorrer depois do provisionamento.

```bash
./mvnw -Dtest=AttachmentValidatorT12Test,S3FileStorageTest,WorkOrderAttachmentsT12IntegrationTest,ArchitectureTest,RolePermissionsTest test
```

## S3 local com LocalStack e Docker Compose

O Compose inclui `localstack` e `localstack-gateway`. Para subir somente o
armazenamento (preservando os processos locais de backend/frontend):

```bash
docker compose up -d localstack-gateway
```

O gateway inicia o LocalStack como dependência. Em ambiente Podman, o comando
equivalente é `podman-compose up -d localstack-gateway`.
O bucket `fixflow-attachments` é inicializado por
`infra/localstack/ready.d/10-attachments.sh`, com bloqueio público, propriedade
do bucket e SSE-S3. O hook é idempotente. A imagem Community não persiste os objetos sozinha. O wrapper
`infra/localstack/entrypoint.sh` salva os objetos no volume
`fixflow_localstack_data` antes de encerrar o runtime; o hook restaura esse
snapshot no próximo início. O healthcheck só libera a aplicação após criar o
bucket e restaurar os arquivos. A preservação foi projetada para parada/reinício
normais do container; encerramento forçado ou falha do host pode perder uploads
posteriores ao último snapshot. Isso é uma limitação do emulador local, não uma
configuração de armazenamento para produção.

Foi fixada a imagem Community `localstack/localstack:4.5`, já disponível neste
ambiente, para desenvolvimento local sem autenticação de conta LocalStack.
A porta publicada fica em `127.0.0.1:4566`. O serviço S3 original só é acessível
pela rede dos containers; a porta publicada passa por Nginx e exige assinatura.
Essa camada é necessária porque a imagem Community usada não aplica os bloqueios
de ACL a requisições anônimas. `S3_SKIP_SIGNATURE_VALIDATION=0` faz o LocalStack
conferir a assinatura e a expiração dos URLs; o gateway não registra query strings.
A configuração local não equivale à autorização IAM de uma conta AWS real.

Para o backend executado no host:

```bash
cd backend/fixflow
./mvnw spring-boot:run -Dspring-boot.run.profiles=localstack
```

O perfil `application-localstack.properties` habilita anexos e configura o endpoint
`http://localhost:4566`, path-style e credenciais fictícias `test/test`. A
configuração local ignorada pelo Git deste ambiente também foi preparada para
esse endpoint; um backend que já estava rodando deve ser reiniciado para reler
as propriedades e incorporar as classes novas.

Quando a aplicação roda no Compose, o cliente utiliza
`http://localstack-gateway:4566`, enquanto o presigner utiliza
`http://localhost:4566` para produzir URLs acessíveis ao navegador. O host da URL
assinada não pode ser substituído depois de assinar: ele faz parte da assinatura.
`S3_ENDPOINT` e `S3_PUBLIC_ENDPOINT` permitem configurar esses endereços
separadamente. `S3_PATH_STYLE=true` evita subdomínios de bucket não resolvíveis
na rede dos containers. `S3_ACCESS_KEY`/`S3_SECRET_KEY` são fictícias neste Compose.
Sem endpoints/credenciais explícitos, o adaptador mantém o comportamento AWS
original, com o provedor padrão de credenciais.

Teste real, com os containers em execução:

```bash
cd backend/fixflow
LOCALSTACK_TEST=true ./mvnw -Dtest=LocalStackS3IntegrationTest test
```

O teste envia um PNG ao bucket inicializado, baixa os mesmos bytes por uma URL
assinada de 300 segundos, verifica MIME/disposição e rejeição de acesso anônimo
e de assinatura adulterada. Apaga o objeto de teste ao concluir. Por padrão,
essa classe é ignorada na suíte, e o perfil `integration-test` continua usando
storage simulado. O gateway resolve o endereço interno do LocalStack dinamicamente, usando o DNS
do container, para continuar funcionando quando o runtime muda de IP após reiniciar.
O teste real aceita apenas endpoints de loopback e credenciais
fictícias; não se conecta à AWS.

Referências: [S3 no LocalStack](https://docs.localstack.cloud/aws/services/s3/)
e [inicialização por hooks](https://docs.localstack.cloud/aws/capabilities/config/initialization-hooks/).
