# FixFlow — Home

Página institucional estática com a identidade visual do FixFlow, layout responsivo, painel ilustrativo, etapas interativas e modal de apresentação.

## Executar localmente

Na raiz do repositório:

```bash
python3 -m http.server 5500 --directory frontend --bind 127.0.0.1
```

Abra http://localhost:5500. No VS Code, também é possível abrir `frontend/index.html` com a extensão Live Server.

Não há instalação de dependências nem etapa de build. Edite `index.html`, `styles.css` e `main.js`; a logo está em `assets/fixflow-logo.jpg`.

## Continuar com Codex

Abra a raiz do repositório no VS Code ou no terminal e trabalhe nesta branch. Exemplo de pedido:

> Continue o frontend em frontend/. Preserve a identidade visual e o backend existente. Antes de integrar autenticação, inspecione os contratos e a segurança do backend/fixflow. Não trate os dados ilustrativos como dados reais.

## Escopo atual

A home não chama a API. Login, cadastro e operação real ainda não foram implementados. Os números e serviços do painel são fictícios. Os recursos descritos representam a proposta do produto.

A página pode ser servida por qualquer servidor estático. As configurações e a identidade da hospedagem privada de origem não fazem parte deste frontend.
