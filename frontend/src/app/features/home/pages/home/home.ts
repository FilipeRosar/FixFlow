import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-home',
  imports: [RouterLink],
  templateUrl: './home.html',
  styleUrl: './home.css',
})
export class Home {
  protected readonly planFeatures = [
    { name: 'Mensalidade', values: ['R$ 69,90', 'R$ 119,90', 'R$ 199,90'] },
    { name: 'Usuários', values: ['2', '6', 'Ilimitados¹'] },
    { name: 'Ordens de serviço/mês', values: ['100', '500', 'Ilimitadas¹'] },
    { name: 'Clientes e equipamentos', values: ['Ilimitados', 'Ilimitados', 'Ilimitados'] },
    { name: 'Cadastro e acompanhamento de OS', values: ['✓', '✓', '✓'] },
    { name: 'Orçamento e aprovação por link', values: ['✓', '✓', '✓'] },
    { name: 'E-mails automáticos', values: ['✓', '✓', '✓'] },
    { name: 'Estoque de peças e movimentações', values: ['—', '✓', '✓'] },
    { name: 'Financeiro básico de OS e pagamentos', values: ['✓', '✓', '✓'] },
    { name: 'Dashboard', values: ['Básico: OS por status', 'Completo: OS e indicadores financeiros', 'Completo + relatórios gerenciais'] },
    { name: 'Anexos e fotos', values: ['1 GB', '10 GB', '50 GB'] },
    { name: 'Perfis de acesso²', values: ['✓', '✓', '✓'] },
    { name: 'Consulta de auditoria', values: ['—', '✓', '✓ + exportação'] },
    { name: 'WhatsApp automatizado³', values: ['—', 'Franquia de mensagens', 'Franquia maior'] },
    { name: 'Automações, API e integrações³', values: ['—', '—', '✓'] },
    { name: 'IA assistiva³', values: ['—', '—', 'Franquia de uso'] },
    { name: 'Suporte', values: ['E-mail', 'E-mail + chat', 'Prioritário'] },
  ];
  protected readonly steps = [
    {
      title: 'Receber e registrar',
      description:
        'Vincular cliente e equipamento à ordem de serviço, com defeito relatado, acessórios e estado de entrada.',
    },
    {
      title: 'Diagnosticar',
      description:
        'Atribuir o técnico e registrar o diagnóstico e a solução proposta para o equipamento.',
    },
    {
      title: 'Orçar e aprovar',
      description:
        'Reunir serviços e peças em um orçamento e enviar um link para a decisão do cliente.',
    },
    {
      title: 'Executar o reparo',
      description:
        'Acompanhar o serviço, registrar peças utilizadas e manter o histórico do atendimento.',
    },
    {
      title: 'Registrar o pagamento',
      description:
        'Registrar recebimentos manuais, parciais ou totais, e acompanhar a situação financeira da ordem.',
    },
    {
      title: 'Entregar e acompanhar',
      description:
        'Registrar a entrega e o prazo de garantia, preservando o histórico para atendimentos futuros.',
    },
  ];
  protected readonly features = [
    {
      title: 'Clientes e equipamentos',
      description:
        'Contatos, aparelhos e histórico de atendimentos conectados para facilitar as próximas consultas.',
    },
    {
      title: 'Ordens de serviço',
      description:
        'Etapas definidas, técnico responsável, diagnóstico, fotos e histórico de cada atendimento.',
    },
    {
      title: 'Orçamento por link',
      description:
        'Serviços e peças em uma proposta que o cliente poderá aprovar ou rejeitar sem criar uma conta.',
    },
    {
      title: 'Peças e estoque',
      description:
        'Cadastro de peças, entradas, ajustes e registro do consumo vinculado ao serviço realizado.',
    },
    {
      title: 'Pagamentos e indicadores',
      description:
        'Recebimentos manuais e visão básica de faturamento, ordens por status e tempo de atendimento.',
    },
    {
      title: 'Comunicação e histórico',
      description:
        'E-mails em eventos importantes e registros para acompanhar as decisões e a evolução do atendimento.',
    },
  ];
  protected readonly faqs = [
    {
      question: 'O FixFlow é para qual tipo de assistência?',
      answer:
        'O foco são assistências técnicas independentes de informática e eletrônicos, com equipes de 1 a 10 pessoas: computadores, notebooks, impressoras e periféricos.',
    },
    {
      question: 'Como funciona o teste grátis?',
      answer:
        'Escolha o Starter, informe seus dados e confirme seu e-mail. Só então criamos a assistência e iniciamos 14 dias de teste, com configuração guiada e sem cartão. Ao terminar, você pode contratar ou manter consulta e exportação dos dados.',
    },
    {
      question: 'O cliente precisará criar uma conta para aprovar o orçamento?',
      answer:
        'No fluxo planejado, não. O cliente receberá um link com acesso restrito ao atendimento para consultar a proposta e aprovar ou rejeitar o orçamento. Esse recurso ainda está em desenvolvimento.',
    },
    {
      question: 'Haverá envio automático pelo WhatsApp?',
      answer:
        'Essa integração está prevista para uma fase futura. O planejamento inicial contempla envio por e-mail e a possibilidade de copiar o link do orçamento para compartilhar manualmente.',
    },
    {
      question: 'O sistema emitirá nota fiscal?',
      answer:
        'A emissão de NF-e e NFS-e não faz parte do escopo inicial. O foco do FixFlow é organizar os atendimentos, os reparos e os registros operacionais da assistência.',
    },
  ];
}
