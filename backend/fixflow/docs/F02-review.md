# F-02 — Revisão e evidências locais

Data: 2026-11-02. Branch: `feat/fix-1-fundacao`.

## Dados legados e execução da V9

Auditoria executada com o migrator, sem publicar emails ou hashes. Para ler todas
as linhas protegidas por FORCE RLS, a inspeção usou policies temporárias somente
para o migrator dentro de uma transação revertida ao final.

| Banco | Legados antes/depois | Identidades criadas | Vínculos criados | Redefinições exigidas |
| --- | --- | --- | --- | --- |
| `fixflow` | 2 / 2 | 2 | 2 | 2 |
| `fixflow_test` | 0 / 0 | 0 | 0 | 0 |

Antes do backfill não havia conflitos de email/hash entre registros ou colisões
de UUID. Os dois hashes legados de `fixflow` não eram BCrypt válidos. As duas
identidades resultantes estão `SUSPENDED`, e os dois vínculos, `INVITED/VIEWER`.
Não houve ativação automática. A origem está preservada com mapeamento auditável.

A V9 foi aplicada e testada primeiro em `fixflow_test`, incluindo conflitos,
idempotência, credenciais inválidas e preservação de contas preexistentes. Depois
foi aplicada ao `fixflow` pelo startup normal do Spring/Flyway, usando porta
aleatória, e a execução auxiliar foi encerrada após iniciar corretamente.

V8 e V9 estão com sucesso nos dois bancos. Checksum da V8: `304237816`.
Os testes também comparam o checksum da V9 entre os bancos e verificam a ausência
das policies temporárias do backfill.

## Auditoria de privilégios

Nos dois bancos, `fixflow_runtime`:

- não é SUPERUSER, CREATEROLE, CREATEDB, REPLICATION ou BYPASSRLS;
- não participa de outras roles;
- não é proprietário do banco, schema público ou relações públicas;
- não possui CREATE em banco/schema público;
- não acessa diretamente `users` nem a trilha de migração;
- não pode executar o backfill;
- mantém leitura sujeita a RLS nas tabelas de oficina.

Não foi necessário alterar atributos administrativos da role. As verificações
fazem parte de `DatabaseInfrastructureIntegrationTest`.

## Revisão das alterações

- Substituídos os arquivos vazios/esqueletos do índice pela implementação completa
  na preparação do commit da F-02.
- Autenticação, contexto transacional, respostas HTTP e regras ArchUnit revisados.
- `PasswordConfig` passa a ser carregada pelo Spring e concentra o encoder BCrypt
  de custo 12; removido o bean duplicado de `SecurityConfig`.
- Fixtures e testes de escrita permanecem exclusivos de `fixflow_test`.
- Arquivo de credenciais locais e caches Python ignorados pelo Git.
- Migrations já aplicadas V1–V8 preservadas nesta finalização; apenas V9 adicionada.
  O aviso de whitespace de V3 (linha em branco no EOF) foi mantido para preservar
  seu checksum. Não executar `repair` nem editar essa migration por formatação.
- Descrição de PR: [F02-PR.md](F02-PR.md).
- Mensagem de commit: [F02-commit-message.txt](F02-commit-message.txt).

## Validação final

Comando: `./mvnw clean verify`.

Resultado: **BUILD SUCCESS — 85 testes, 0 falhas, 0 erros, 0 ignorados**.
Execução concluída em 2026-11-02 às 21:15:35 -03:00, em 57,361 segundos.

## Limites da entrega

Recuperação de senha, verificação de titularidade e ativação administrativa de
identidades/vínculos não são expostas por endpoints nesta entrega. As duas contas
migradas precisam concluir essas etapas antes de receber acesso.

`app_users` não foi removida. Escritas de RLS foram validadas numa cópia
descartável da estrutura e policy real, sem ampliar permissões do runtime nas
tabelas atuais. Commit e publicação do PR são etapas separadas da preparação.
