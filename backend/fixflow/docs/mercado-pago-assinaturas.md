# Assinaturas pelo Mercado Pago

O provedor padrão para contratar a assinatura do FixFlow é o Mercado Pago. A integração usa assinaturas sem plano associado (`POST /preapproval`, status `pending`), com recorrência de um mês em BRL. Cada contrato guarda o preço escolhido no servidor; valores enviados pelo navegador não são utilizados. Os preços padrão são Starter R$ 69,90, Professional R$ 119,90 e Business R$ 199,90.

O cadastro e os 14 dias de teste continuam sem cartão. Em **Configurações → Assinatura**, o proprietário seleciona o plano, confirma o preço e abre o checkout do Mercado Pago para autorizar a cobrança recorrente. O checkout abre em outra aba e pode ser retomado enquanto estiver pendente. Dados do cartão são preenchidos no Mercado Pago.

## Configuração

Defina no ambiente do backend (ou no `.env` utilizado pelo Docker Compose):

```dotenv
SUBSCRIPTIONS_ENABLED=true
SUBSCRIPTIONS_PROVIDER=mercadopago
MERCADOPAGO_ACCESS_TOKEN=<access token da conta recebedora>
MERCADOPAGO_WEBHOOK_SECRET=<assinatura secreta dos webhooks>
MERCADOPAGO_BACK_URL=https://seu-dominio/inicio/configuracoes/assinatura?assinatura=retorno
MERCADOPAGO_NOTIFICATION_URL=https://seu-dominio/webhooks/mercadopago/subscriptions
SUBSCRIPTION_STARTER_PRICE=69.90
SUBSCRIPTION_PROFESSIONAL_PRICE=119.90
SUBSCRIPTION_BUSINESS_PRICE=199.90
```

Use credenciais próprias para testes e para produção. O backend valida que as URLs públicas usam HTTPS. O proxy precisa encaminhar `/webhooks/mercadopago/subscriptions` para o backend e preservar os parâmetros de consulta e os cabeçalhos `x-signature` e `x-request-id`. As credenciais ficam apenas no backend. Nenhuma chave pública ou SDK no navegador é necessário para esse checkout hospedado.

A URL de notificação é enviada na criação da assinatura. Habilite os eventos `subscription_preapproval` e `subscription_authorized_payment` na configuração de notificações da aplicação quando disponíveis. O webhook valida HMAC SHA-256 usando `data.id` da query string, `x-request-id` e `ts` antes de consultar os recursos na API.

A migration V53 adiciona a URL do checkout e a função de conciliação do cancelamento externo. Execute as migrations antes de subir a nova versão. Mantenha o provedor existente até cancelar/concluir contratos Asaas anteriores: não troque provedores com contratos ativos. A opção `SUBSCRIPTIONS_PROVIDER=asaas` preserva o adaptador anterior para essa transição.

## Confirmação e acesso

A autorização da recorrência ou o retorno do checkout não libera o plano. O servidor consulta a fatura em `/authorized_payments/{id}` e o pagamento correspondente em `/v1/payments/{id}`, verificando referência da assinatura, valor e moeda. Apenas pagamentos aprovados e sem valor reembolsado são enviados à conciliação existente como `CONFIRMED`. As referências locais recebem prefixos `sub_mp` e `pay_mp` para distinguir provedores e preservar a validação do banco.

A conciliação usa o contrato persistido para determinar a empresa e impede uma segunda liberação pelo mesmo pagamento ou ciclo. Webhooks duplicados podem ser processados novamente sem renovar duas vezes. Tentativas de criação ficam persistidas; após uma falha ambígua, a atualização busca o contrato remoto pela referência antes de prosseguir, sem criar outra assinatura automaticamente.

O retorno `?assinatura=retorno` solicita atualização ao backend; também existe o botão **Atualizar pagamentos**. Acesso pago só avança depois da confirmação. Cancelar a renovação chama `PUT /preapproval/{id}` com `status=cancelled` e mantém o acesso até o fim do período pago. Cancelamentos feitos no Mercado Pago são refletidos via webhook ou atualização manual. O processamento de outbox repete cancelamentos locais que falharam temporariamente.

Enquanto a conta aguarda o primeiro pagamento e tem um contrato ativo, a tela também consulta a confirmação a cada 15 segundos quando está visível e ao recuperar o foco. A consulta não cria cobranças e deixa de ocorrer após a liberação do acesso. Para receber confirmações com a página fechada, configure os eventos `subscription_preapproval` e `subscription_authorized_payment` nos Webhooks da aplicação da conta vendedora e mantenha a assinatura secreta correspondente no backend.

O e-mail de ativação é gerado pela conciliação do pagamento aprovado e enviado aos proprietários cadastrados no FixFlow. Endereços fictícios `@testuser.com` do Mercado Pago não permitem conferir a entrega em uma caixa de e-mail real; nos testes locais, confira o capturador de e-mails configurado ou use um destinatário real no ambiente apropriado.

Reembolsos e contestações são reconhecidos pelo adaptador. A regra existente de conciliação preserva períodos já concedidos; estornos não revogam automaticamente acesso anterior e exigem conciliação operacional. A troca de plano durante o teste, sem contrato em andamento, usa `POST /api/v1/subscription/plan`, dispensa o gateway e preserva o prazo original. A migration V54 fornece a função restrita para essa alteração; planos pagos continuam sendo aplicados apenas pela conciliação de pagamento.

Para trocar um plano contratado, o proprietário seleciona outro plano e confirma a substituição. O servidor valida os limites de usuários e de ordens do mês, cancela a recorrência anterior e cria um novo checkout. Se o cancelamento falhar, não cria outro contrato. O novo plano só entra em vigor após pagamento integral, acrescentando um mês à vigência já paga, sem cálculo proporcional. Se o checkout não for concluído, o acesso anterior permanece até o fim do período vigente e a recorrência anterior continua cancelada. A confirmação na tela explica essas condições. Alterações automáticas do preço de contratos existentes continuam fora desse fluxo.

## Validação antes de ativar produção

Com uma conta compradora de teste e as credenciais adequadas, contratar um plano, concluir o checkout, receber uma cobrança aprovada e confirmar o acesso. Reenviar a mesma notificação e conferir que a vigência não avança duas vezes. Cancelar pelo FixFlow e pelo Mercado Pago em contratos separados e conferir que a renovação é interrompida. Esses testes externos precisam de credenciais e URLs públicas; testes locais simulam a API sem criar cobranças.

Documentação oficial: [assinaturas pendentes](https://www.mercadopago.com.br/developers/pt/docs/subscriptions/integration-configuration/subscription-no-associated-plan/pending-payments), [faturas](https://www.mercadopago.com.br/developers/pt/reference/online-payments/subscriptions/get-authorized-payment/get) e [webhooks](https://www.mercadopago.com.br/developers/pt/docs/subscriptions/additional-content/your-integrations/notifications/webhooks).

### Desenvolvimento local: retorno pelo mesmo túnel do backend

Use `MERCADOPAGO_BACK_URL=https://SEU-TUNEL/public/subscriptions/return` e
`MERCADOPAGO_NOTIFICATION_URL=https://SEU-TUNEL/webhooks/mercadopago/subscriptions`.
O retorno público redireciona para `APP_ORIGIN` (por padrão `http://localhost:4200`),
na página de assinatura. Ele não concede acesso nem confia em parâmetros de pagamento.
Assim, um único túnel HTTPS na porta 8080 atende às duas URLs.

Ao executar Java diretamente, as variáveis `MERCADOPAGO_*`, `SUBSCRIPTIONS_*` e
`SUBSCRIPTION_*` são lidas do `.env` no diretório atual ou na raiz do repositório
quando iniciado em `backend/fixflow`. Variáveis do ambiente têm prioridade.
Reinicie o backend após alterar o arquivo. Os testes não carregam credenciais locais.

A exportação da empresa baixa `fixflow-dados-csv.zip`, com CSVs UTF-8 separados por
ponto e vírgula, um por categoria, e instruções para abrir em planilhas.
