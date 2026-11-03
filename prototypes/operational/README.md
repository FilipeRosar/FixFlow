# FixFlow — referência visual da operação

Protótipo estático para orientar a implementação gradual no Angular em `frontend/`.
Este diretório é independente da aplicação Angular e não integra o backend.

## Visualizar

Na raiz do repositório:

```bash
python3 -m http.server 5500 --bind 127.0.0.1 --directory prototypes/operational
```

Abra http://localhost:5500. Também é possível abrir `index.html` usando Live Server no VS Code.
Não requer npm, instalação ou build. A fonte DM Sans é carregada do Google Fonts; sem conexão, usa Arial.

## Telas e interações demonstrativas

- Dashboard: indicadores, gráficos, atividades e pendências.
- Ordens: lista, quadro por etapa, busca, filtro e detalhes com histórico.
- Nova OS: formulário e confirmação simulada, sem criação de registros.
- Clientes, aparelhos, financeiro e configurações: referências de layout.

Todos os nomes, ordens, valores e indicadores são fictícios. Não há autenticação,
chamadas de API, persistência, emissão de documentos ou transações financeiras.
Os links de retorno ao site são placeholders nesta cópia independente.

## Usar como referência no Angular

Consulte `index.html` (estrutura), `app.css` (tokens e layout), `app.js` (dados e
navegação de demonstração) e `assets/` (logo).
Recrie as telas em componentes Angular, com rotas, formulários e contratos da API
conforme forem implementados. O JavaScript deste protótipo manipula DOM e usa
HTML dinâmico com dados fixos: não o utilize para renderizar entradas externas.
Não copie os dados fictícios para serviços de produção nem utilize a demonstração
como controle de autenticação, autorização ou isolamento entre empresas.
