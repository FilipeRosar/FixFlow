import { JourneyStep } from '../models/journey-step';

export const JOURNEY_STEPS: readonly JourneyStep[] = [
  {
    number: '01',
    title: 'Receba e registre',
    subtitle: 'O ponto de partida de cada serviço.',
    description:
      'Registre o cliente, o aparelho e o problema relatado. Um bom reparo começa com as informações certas.',
  },
  {
    number: '02',
    title: 'Diagnostique e alinhe',
    subtitle: 'Mais contexto para o próximo passo.',
    description:
      'Organize o diagnóstico e o orçamento para alinhar o serviço com o cliente antes de avançar.',
  },
  {
    number: '03',
    title: 'Repare e acompanhe',
    subtitle: 'Cada serviço no seu ritmo, com controle.',
    description:
      'Acompanhe o andamento do reparo e mantenha as informações do serviço reunidas até a conclusão.',
  },
  {
    number: '04',
    title: 'Entregue e mantenha o histórico',
    subtitle: 'O atendimento continua depois do reparo.',
    description:
      'Conclua a ordem e preserve o histórico do atendimento para as próximas visitas do cliente.',
  },
];
