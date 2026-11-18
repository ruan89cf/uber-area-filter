# Design visual: leitura em menos de 1 segundo

Prancha com os mockups: https://claude.ai/artifact/LqUmQBqCSPJ1diqRacEGeV

## Princípio
Ordem de leitura fixa em todos os cards: **símbolo + cor da decisão → valor e R$/h → o que está errado**. O olho aprende o lugar de cada coisa e não precisa procurar.

## 1. Cores de decisão (`overlay/CardPalette.kt`)
- A cor escolhida vira o **matiz** do card, não a cor literal.
- **Fundo:** sempre escuro e pouco saturado (luminância travada). Nunca ofusca à noite, e o texto claro fica sempre acima de 12,9:1 de contraste, mesmo se a escolha for "Laranja" (antes 3,8:1) ou "Amarelo puro" (antes 1,2:1).
- **Acento** (barra lateral, símbolo, R$/h, pílula): o mesmo matiz, clareado até passar 4,5:1 sobre o fundo.
- **Cor neutra** (preto, branco, cinza): o fundo fica neutro, mas o acento usa a cor da decisão (vermelho na recusa etc.).
- **À noite** (tema escuro do sistema ou das 19h às 6h): fundo ~20% mais escuro e texto #ECE9E2 em vez de branco puro, o que tira o "halo" das letras.
- **Forma, além da cor:** cada decisão tem um símbolo (✓ ✕ ⇄ ↩ ?). Funciona com daltonismo e com sol batendo na tela.
- **Garantia:** testes em `CoreFeaturesTest` checam o contraste de todas as cores pré-definidas, em todas as decisões, de dia e de noite.

## 2. Números e tipografia
- Valores em sans-serif medium com algarismos de largura fixa (`tnum`): o número não "pula" quando chega outra oferta.
- "R$" e "/h líq." em metade do tamanho e cor discreta, para o número dominar.
- R$/h na cor de acento, à direita. Valor da corrida em branco, à esquerda.
- No painel, títulos em serifa (Noto Serif, já presente no Android) e controles em Roboto: aparência editorial e calma no app, leitura rápida no card.

## 3. Card flutuante: nível de informação, não só tamanho
| Tamanho | Mostra |
|---|---|
| Compacto | símbolo, título, status, valores e **uma** linha com o motivo principal |
| Normal | + métricas, combustível/lucro, só as falhas do checklist (os acertos viram "4 ok") e as duas ruas |
| Grande | tudo, letras 18% maiores, cada item do checklist, endereços em até 2 linhas |

## 4. Organização do card
- O checklist ordena por gravidade: trava em corrida → falha → atenção → entre zonas → info → ok.
- Linhas "Embarque:"/"Destino:" com o rótulo discreto e o endereço em destaque. Uma falha fica em vermelho-claro.
- Combustível e lucro numa linha só, sem emoji: o valor colorido, o resto discreto.
- A barra de tempo ganhou trilho, então dá para ver quanto falta.

## 5. Abas e rolagem (`SectionTabsLayout.kt`)
- Cada aba lembra onde você parou.
- Tocar de novo na aba ativa volta ao topo.
- Abas com 3 ou mais seções ganham atalhos no topo (Início, Filtros, Zonas). Os nomes vêm do título de cada card, então um card novo entra sozinho.
- Botões do histórico com 48 dp de altura.

## 6. Tema escuro e botão flutuante
- Grafite **quente** (#1C1B19) em vez de azul-marinho. Não é preto puro, para evitar halo.
- O verde neon #00E676 virou #72CC97 à noite e #1F7A4E de dia.
- De dia, marfim (#F6F5F0) com cards quase brancos.
- **Botão arrastável com anel duplo:** escuro por fora (aparece em mapa claro) e claro por dentro (aparece em mapa escuro).
- O botão tem 44 dp de altura, e o estado desligado agora aparece como "PAUSA" (antes "OFF").
- Os cards do histórico seguem o tema (antes eram sempre pretos, até no tema claro).

## Como aplicar e desfazer
1. Rode `aplicar_melhorias_area.bat`. Ele copia `CardPalette.kt`, `OverlayManager.kt`, `SectionTabsLayout.kt` e `MainActivity.kt`. As cores, estilos e o layout já foram gravados direto em `app\src\main\res`.
2. Rode `instalar_no_celular.bat`.
3. Para voltar ao visual anterior **sem perder a demarcação de área**, copie o conteúdo de `_backup_antes_design\`:
   - `main\...` vai para `app\src\main\java\com\ruan\uberareafilter\...`
   - `res\...` vai para `app\src\main\res\...`
   - `overlay\CardPalette.kt` pode ficar: sem os outros arquivos ele não é usado e não atrapalha.

Antes de entregar, revisei o código contra a versão que já compila no seu PC. Corrigi um erro que quebraria o build (`orEmpty()` usado em texto formatado) e um ajuste de layout: os botões do histórico quebravam o rótulo em duas linhas. A compilação desta parte de design ainda não foi feita. Se der erro, o `build_log.txt` mostra a linha.
