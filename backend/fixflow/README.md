# FixFlow

Backend de um SaaS B2B para gestão de assistências técnicas pequenas e médias. O FixFlow foi pensado para centralizar o atendimento, rastrear equipamentos e registrar decisões ao longo de todo o serviço.

```text
Cliente → Equipamento → Ordem de Serviço → Diagnóstico → Orçamento
        → Aprovação → Manutenção → Pagamento → Entrega → Garantia
```

Cada assistência é um **tenant**, com seus próprios dados e vínculos de usuários. O produto atende proprietários, atendentes e técnicos; o acesso do cliente final por link de orçamento faz parte do escopo planejado.

## Estado do projeto

O backend está em desenvolvimento, com foco na fundação técnica. Este repositório contém a aplicação Spring Boot; a interface Angular e os módulos de negócio descritos no documento técnico fazem parte da evolução do produto.

| Disponível no código | Escopo |
|---|---|
| Identidade e autorização de oficina | Identidade global, senha BCrypt, vínculos de usuários com oficinas e seleção autorizada por requisição |
| Isolamento entre tenants | Contexto transacional, PostgreSQL RLS e separação das roles de execução e migração |
| Observabilidade | Logs JSON, IDs de requisição e correlação, contexto de usuário/tenant e probes de saúde |
| Auditoria base | `AuditService`, gravação na transação de negócio e tabela `audit_log` append-only para a role de execução |
| Persistência e qualidade | Migrations Flyway, testes de integração e regras de arquitetura com ArchUnit |
| Integração contínua | GitHub Actions com build, testes, lint e gate de segurança High/Critical |
| API | OpenAPI, Swagger UI e tratamento global de erros |

O [documento técnico](docs/documento-tecnico-fixflow.md) reúne requisitos, fluxos, modelo de dados, ADRs e backlog. Ele descreve a arquitetura alvo; para executar esta versão, use as versões e rotas abaixo. A autenticação atual usa **HTTP Basic**. JWT com refresh rotativo está previsto no roadmap.

## Stack atual

| Tecnologia | Uso |
|---|---|
| Java 25 | Linguagem e runtime |
| Spring Boot 4.1.1 | Aplicação e configuração |
| Spring Security | Autenticação e autorização |
| Spring Data JPA / Hibernate e JDBC | Persistência e acesso transacional |
| PostgreSQL | Banco relacional, RLS e JSONB |
| Flyway | Versionamento do schema |
| Spring Boot Actuator | Probes de saúde |
| springdoc-openapi | Documentação da API |
| JUnit, Spring Test e ArchUnit | Testes e validação das fronteiras de arquitetura |
| Maven Wrapper e Docker | Build e empacotamento |

## Executar localmente

### Pré-requisitos

- JDK 25.
- Python 3 para o assistente de configuração local.
- PostgreSQL disponível em `localhost:5432`, com o banco `fixflow` criado.
- Roles `fixflow_runtime` e `fixflow_migrator` previamente provisionadas, com suas senhas e permissões.
- Banco `fixflow_test` para os testes de integração.

A role de execução deve operar sem privilégios administrativos, sem `BYPASSRLS` e sem ser proprietária das tabelas. A role de migração aplica o schema pelo Flyway. O assistente abaixo apenas salva as credenciais existentes; o provisionamento do PostgreSQL é uma etapa prévia.

### Configurar credenciais

Na raiz do repositório:

```bash
python3 scripts/configure-local.py
```

Informe as senhas das duas roles. O script cria `application-local.properties`, ignorado pelo Git e com acesso restrito ao usuário atual. Para atualizar uma configuração existente, edite esse arquivo.

| Variável | Finalidade | Padrão / configuração |
|---|---|---|
| `DB_URL` | URL JDBC da aplicação e do Flyway | `jdbc:postgresql://localhost:5432/fixflow` |
| `DB_USERNAME` | Role de execução | `fixflow_runtime` |
| `DB_PASSWORD` | Senha de execução | Informada na configuração local |
| `DB_MIGRATOR_USERNAME` | Role de migração | `fixflow_migrator` |
| `DB_MIGRATOR_PASSWORD` | Senha de migração | Informada na configuração local |

Variáveis de ambiente têm prioridade sobre o arquivo local. Execute os comandos a partir da raiz do projeto, inclusive ao configurar a IDE.

### Iniciar a API

```bash
./mvnw spring-boot:run
```

O Flyway aplica as migrations ao iniciar. A aplicação atende em `http://localhost:8080`.

Para usar outra porta:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
```

### Documentação e saúde

| Rota | Finalidade |
|---|---|
| `/swagger-ui.html` | Interface da documentação da API |
| `/api-docs` | Especificação OpenAPI em JSON |
| `/health` | Saúde agregada |
| `/health/liveness` | Estado da aplicação, independente do banco |
| `/health/readiness` | Disponibilidade da aplicação e conexão com PostgreSQL |

Essas rotas são públicas na configuração atual. As probes retornam `200` quando disponíveis e `503` quando indisponíveis, sem expor detalhes internos.

## Autenticação e isolamento

As rotas protegidas usam o e-mail e a senha da identidade global. A identidade, o vínculo e a oficina precisam estar ativos para autorizar uma operação no tenant.

```bash
curl --user 'seu-email@example.com' \
  http://localhost:8080/api/oficinas/UUID_DA_OFICINA/selecao
```

O curl solicita a senha. Use uma identidade e uma oficina já cadastradas no banco; o cadastro público ainda faz parte do backlog. A resposta informa `userId`, `tenantId` e `role` do vínculo autorizado. Uma identidade pode ter vínculos com diferentes oficinas.

A seleção vale para a requisição atual. Cada operação deve passar por `AuthorizedTenantOperations.execute(tenantId, operation)`, que valida o vínculo antes de configurar o contexto Java e `SET LOCAL app.tenant_id` na transação. O PostgreSQL aplica RLS sobre os dados do tenant, e o contexto é limpo ao finalizar a operação.

Respostas da seleção: `401` para falha de autenticação, `403` para acesso à oficina negado e `400` para UUID inválido. A proteção CSRF permanece habilitada nos métodos que alteram estado.

## Testes

Para executar a suíte completa:

```bash
./mvnw clean verify
```

Os testes de integração usam PostgreSQL real e o perfil `integration-test`, com URL fixa para `localhost:5432/fixflow_test`. As credenciais vêm da mesma configuração local. As fixtures são criadas e removidas com a role de migração; as operações da aplicação usam a role de execução.

`DatabaseInfrastructureIntegrationTest` também consulta o banco `fixflow` para validar migrations e permissões. Os dois bancos precisam estar disponíveis e migrados para a suíte completa.

Para validar apenas a arquitetura, sem banco:

```bash
./mvnw -Dtest=ArchitectureTest test
```

Para validar auditoria e arquitetura:

```bash
./mvnw -Dtest=AuditServiceIntegrationTest,ArchitectureTest test
```

A cobertura da fundação inclui autorização de vínculos, isolamento de leitura/escrita, rollback, reutilização de conexão sem vazamento de tenant, limpeza de contexto, observabilidade e permissões append-only da auditoria.

## Integração contínua (F-05)

O workflow [CI](../../.github/workflows/ci.yml) executa em todos os PRs e pushes e também pode ser disparado manualmente. Requer a fundação F-01 (Java 25, Maven Wrapper e Dockerfile).

| Check | Validação |
|---|---|
| `Lint` | Checkstyle sobre fontes e testes: classes, instruções vazias, imports proibidos, `equals`/`hashCode` e fall-through |
| `Build and test` | PostgreSQL 17 descartável, roles restritas, migrations nos dois bancos e `clean verify` com a suíte completa |
| `Docker and security` | Trivy nas dependências do projeto, build Docker e Trivy nas dependências Java e pacotes do SO da imagem |

Vulnerabilidades **HIGH ou CRITICAL reprovam o check**, inclusive quando ainda não há correção disponível. Falhas do scanner também reprovam a execução. A configuração segue os parâmetros de severidade e código de saída da [documentação do Trivy](https://trivy.dev/docs/latest/ecosystem/cicd/). Os relatórios de testes, lint e segurança ficam nos artefatos da execução por sete dias. A imagem é construída para validação no runner.

O CI usa credenciais públicas exclusivas do PostgreSQL descartável, definidas em [init-db.sql](scripts/ci/init-db.sql), e não depende de secrets ou de `application-local.properties`. O perfil Maven `ci-db` aplica as migrations no banco `fixflow`; a inicialização dos testes aplica as mesmas migrations em `fixflow_test`.

O POM fixa patches de segurança além das versões gerenciadas pelo Spring Boot 4.1.1: [Jackson 2.21.7](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21.7), [Jackson 3.1.7](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7) e [Tomcat 11.0.25](https://tomcat.apache.org/security-11.html). Essas atualizações corrigem os achados High/Critical detectados ao implantar o pipeline; reavalie os overrides ao atualizar o Spring Boot.

Para executar apenas o lint localmente:

```bash
./mvnw validate
```

Para tornar os checks obrigatórios para merge, configure a proteção da branch no GitHub exigindo `Lint`, `Build and test` e `Docker and security`. A primeira execução no PR confirma o estado do pipeline com a base de vulnerabilidades atual.

## Organização do repositório

```text
src/main/java/br/com/fixflow/
├── FixflowApplication.java
└── shared/
    ├── application/       # Interfaces de autorização e auditoria
    ├── infrastructure/    # Persistência, segurança, tenant e observabilidade
    └── web/               # Controllers e tratamento de erros
src/main/resources/
├── application.properties
├── application-integration-test.properties
└── db/migration/          # Migrations Flyway
src/test/java/             # Testes e fixtures
scripts/                   # Configuração local
docs/                      # Especificação e guia de desenvolvimento
Dockerfile                 # Build Maven e execução com Java 25
```

A arquitetura alvo é um **monólito modular**, com camadas de domínio, aplicação, infraestrutura e web. Os módulos de negócio serão organizados por responsabilidade, com fronteiras verificáveis e interfaces para integrações externas. Migrations já aplicadas devem ser preservadas; alterações de schema entram em novos arquivos.

## Evolução planejada

O MVP descrito no documento técnico contempla:

- Gestão de clientes e equipamentos.
- Ordens de serviço com diagnóstico, atribuição de técnico e máquina de estados.
- Orçamentos versionados e aprovação/rejeição por link seguro, com evidência auditável.
- Estoque básico com movimentações imutáveis e baixa atômica.
- Pagamentos manuais, entrega e garantia.
- Anexos, notificações por e-mail, dashboard e permissões por perfil.
- JWT com refresh rotativo, Outbox transacional e deploy automatizado.

WhatsApp automatizado, gateways de pagamento, IA assistiva e recursos avançados ficam para fases posteriores. A arquitetura planejada usa Angular no frontend e serviços AWS para hospedagem, arquivos e e-mail; o ambiente local atual está descrito neste README.

## Documentação

- [Documento técnico do FixFlow](docs/documento-tecnico-fixflow.md): visão do produto, requisitos, arquitetura, modelo de dados e roadmap.
- [Guia de desenvolvimento](docs/desenvolvimento.md): detalhes de autorização, transações, fixtures, observabilidade e uso do `AuditService`.
