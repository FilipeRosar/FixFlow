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

As rotas protegidas usam HTTP Basic com o email e a senha da identidade global
em `users`. O `GlobalAuthenticationProvider` verifica a senha com BCrypt e exige
usuário `ACTIVE`. O principal do Spring Security contém apenas `userId` e email;
as credenciais são descartadas após a autenticação. Não há login por formulário
nem sessão que guarde a oficina selecionada. A proteção CSRF permanece habilitada
para métodos que alteram estado.

```bash
curl --user 'seu-email@example.com' http://localhost:8080/api/oficinas/UUID_DA_OFICINA/selecao
```

O curl solicita a senha. A resposta contém `userId`, `tenantId` e `role` do vínculo
autorizado. A seleção é por requisição: ela valida o vínculo e não concede acesso
a chamadas futuras. Cada operação da oficina deve chamar
`AuthorizedTenantOperations.execute(tenantId, operation)` novamente.

- `401`: autenticação ausente, senha incorreta ou identidade suspensa.
- `403`: identidade autenticada sem vínculo ativo, vínculo suspenso, oficina
  suspensa ou inexistente. A resposta não revela qual dessas condições ocorreu.
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
nascem ativos, em uma única transação. A identidade criada já pode autenticar via
HTTP Basic e acessar `/api/oficinas/{tenantId}/selecao`. Não cria sessão nem token JWT.

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
