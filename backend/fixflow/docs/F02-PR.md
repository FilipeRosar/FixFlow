# F-02: autenticação global e seleção autorizada de oficina

A aplicação passa a autenticar identidades globais pelo Spring Security e a
autorizar cada seleção de oficina antes de configurar o contexto do banco.
Uma identidade válida sem vínculo ativo recebe 403; credenciais ausentes,
inválidas ou suspensas recebem 401.

O executor mantém JPA e JDBC na mesma transação com tenant autorizado, e os
testes cobrem isolamento, rollback e reutilização de conexões. A V9 preserva o
legado, migra identidades novas como suspensas e vínculos como convidados/viewer,
exigindo revisão e redefinição das credenciais inválidas antes da ativação.

## Critérios de aceite

- [x] Autenticação real, principal sem credenciais e seleção por requisição.
- [x] Bloqueio de acesso suspenso, inexistente e com identidade forjada pelo cliente.
- [x] Nenhum endpoint define TenantScope diretamente.
- [x] JPA/JDBC dentro da transação autorizada; GUC e ThreadLocal limpos ao terminar.
- [x] Leituras e escritas cruzadas bloqueadas por RLS; permissões das tabelas atuais preservadas.
- [x] Fixtures automáticas e isoladas no banco de testes.
- [x] Runtime sem privilégios administrativos, BYPASSRLS, participação em outras roles ou ownership.
- [x] V8/V9 confirmadas nos dois bancos locais.
- [x] Legado preservado com trilha, conflitos bloqueados e backfill idempotente.
- [x] Fluxo, permissões, ativação posterior e recuperação documentados em `docs/F02.md`.

## Validação

Executar `./mvnw clean verify`. Resultado final e contagens da migração registrados
em `docs/F02-review.md`.

## Implantação

Aplicar V9 via Flyway com o migrator. Revisar conflitos e locks antes de executar
em outro ambiente. As contas novas permanecem suspensas até verificação de
titularidade e redefinição de senha; nenhum acesso é liberado automaticamente.
Não remover `app_users` nesta entrega.
