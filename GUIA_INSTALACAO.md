# Filtro Inteligente: guia de instalação

Guia para quem nunca mexeu em "modo desenvolvedor". O app tem um **Assistente de configuração** que faz quase tudo com um botão por passo. Este guia explica o que cada passo faz e o que fazer quando algo não sai como esperado.

## O que é obrigatório e o que não é

| Passo | Para quê | Quem precisa |
|---|---|---|
| Leitor de corridas (Acessibilidade) | Ler as ofertas da 99 e da Uber | **Todos** |
| Notificações | Digitar o código de pareamento e receber alertas | Recomendado |
| Bateria sem restrição | O Android não fechar o filtro no meio do turno | Recomendado |
| Toque automático na Uber | Aceitar ou recusar sozinho na Uber | Só quem usa a Uber com aceite ou recusa automática |
| Localização | Confirmar embarques sem número | Opcional |
| Sobrepor tela | Ver a prévia do card no painel | Opcional (os cards na 99 e na Uber não precisam) |

Na 99, o toque comum da Acessibilidade funciona. A Uber ignora esse toque, por isso ela pede o "motor interno", explicado na Parte 3.

## Parte 1: instalar o app

### Pelo arquivo (WhatsApp, Drive)

1. Toque no arquivo **FiltroInteligente.apk**.
2. Se aparecer *"não pode instalar apps desconhecidos desta fonte"*, toque em **Configurações**, ligue **Permitir desta fonte** e volte.
3. Toque em **Instalar**.
4. Se o Play Protect avisar, toque em **Mais detalhes** e depois em **Instalar mesmo assim**. O aviso aparece só porque o app não está na Play Store.
5. Abra o app. O Assistente abre sozinho na primeira vez.

### Pelo PC (para quem ajuda outros motoristas)

Com o celular no cabo USB e a **Depuração USB** ligada, rode `instalar_no_celular.bat`. Além de instalar, ele já libera pelo cabo:

- a permissão que deixa o app **ligar a própria Acessibilidade** com um toque;
- o bloqueio de **"Configuração restrita"**;
- **bateria** sem restrição;
- **notificações**.

Depois, no celular, é só abrir o Assistente e tocar em **Ligar automaticamente**.

Para gerar o arquivo que você manda para outras pessoas, rode `gerar_apk_para_enviar.bat`. Ele cria a pasta `apk_para_enviar` com o APK e o texto `COMO_INSTALAR.txt`, pronto para colar na conversa. Gere sempre no mesmo PC: com a mesma assinatura, a pessoa atualiza por cima sem perder as zonas.

## Parte 2: o Assistente

No painel, toque em **Assistente de configuração**. Cada passo mostra:

- se já está **pronto** (verde) ou o que falta;
- o que fazer, em frases curtas, com o caminho do **seu** fabricante;
- um botão que abre exatamente a tela certa.

Ao voltar de cada tela do Android, a lista se atualiza sozinha.

### Apareceu "Configuração restrita"?

Acontece no Android 13 ou mais novo com apps instalados por arquivo.

1. Tente ligar a chave uma vez e toque em **OK** no aviso. Sem esse aviso, o Android não mostra a opção de liberar.
2. No Assistente, toque em **Abrir dados do app**.
3. Toque nos **⋮** (três pontinhos, no canto de cima) e em **Permitir configurações restritas**. Confirme com a senha ou a digital.
4. Volte para a Acessibilidade e ligue a chave.

Quem já pareou o motor interno (Parte 3) ou instalou pelo PC não passa por isso.

## Parte 3: toque automático na Uber (uma vez só, uns 3 minutos)

O app usa a **Depuração por Wi-Fi** do próprio Android para tocar na Uber como se fosse o seu dedo. Não precisa de PC nem de outro app.

### 1. Ligar o modo desenvolvedor

Abra **Sobre o telefone** (o Assistente tem o botão) e toque **7 vezes seguidas** no item da tabela abaixo. Se pedir, digite a senha. Vai aparecer *"Você agora é um desenvolvedor"*.

| Marca | Onde tocar 7 vezes | Onde ficam as Opções do desenvolvedor |
|---|---|---|
| Motorola / Pixel | Sobre o telefone > **Número da versão** | Sistema > Opções do desenvolvedor |
| Samsung | Sobre o telefone > Informações do software > **Número de compilação** | No fim da lista de Configurações |
| Xiaomi / Redmi / POCO | Sobre o telefone > **Versão do HyperOS** (ou da MIUI) | Configurações adicionais > Opções do desenvolvedor |
| Oppo / Realme / OnePlus | Sobre o dispositivo > Versão > **Número da versão** | Configurações adicionais > Opções do desenvolvedor |

### 2. Parear (só na primeira vez)

1. Conecte num **Wi-Fi** (o de casa serve).
2. No Assistente, toque em **Parear agora**. Chega uma notificação do Filtro e abrem as Opções do desenvolvedor.
3. Ligue **Depuração por Wi-Fi** e toque em **Permitir**. Marque **Sempre permitir nesta rede**.
4. Toque no **nome** "Depuração por Wi-Fi" para entrar e depois em **Parear dispositivo com código de pareamento**.
5. Aparece um código de 6 números. **Sem fechar essa janela**, puxe a barra de notificações, toque em **Digitar código** na notificação do Filtro e digite os números.
6. Pronto: o motor liga sozinho e o passo fica verde.

Nesse pareamento o app também libera, uma única vez: bateria sem restrição, o bloqueio de "Configuração restrita" e a permissão para religar o motor sozinho.

### 3. Depois disso

- Pode **desligar o Wi-Fi**: o motor continua funcionando no 4G/5G.
- **Reiniciou o celular?** O motor desliga. Quando o celular estiver num Wi-Fi já autorizado, o app liga a Depuração por Wi-Fi sozinho, sobe o motor e desliga a depuração de novo. Dá para desligar isso no Assistente.

## Problemas comuns

**"Leitor de corridas: ligado, mas parado pelo Android"**
Acontece depois de atualizar o app ou quando a economia de bateria fecha apps. No Assistente, toque em **Religar automaticamente** (ou desligue e ligue a chave na Acessibilidade). Depois faça o passo da bateria.

**Não aparece "Parear dispositivo com código"**
Você precisa **entrar** na Depuração por Wi-Fi (tocar no nome, não só na chave). Ela só liga com Wi-Fi conectado.

**"Código rejeitado"**
O código muda toda vez que a janela abre. Abra de novo e digite o código novo, sem sair da janela.

**Motor ligado, mas a Uber não aceita o toque**
- **Xiaomi / Redmi / POCO:** nas Opções do desenvolvedor, ligue também **Depuração USB (configurações de segurança)**.
- **Oppo / Realme / OnePlus:** ligue **Desativar monitoramento de permissões**.

O painel mostra *"o Android recusou o último toque"* quando isso acontece.

**O Shizuku**
Quem já usa o app Shizuku pode usá-lo no lugar do motor interno. O botão só aparece no painel se o Shizuku estiver instalado.

## Status no painel

O card do topo mostra o estado real de cada recurso:

- ● verde: funcionando;
- ▲ âmbar ou vermelho: falta algo;
- ○ cinza: desligado de propósito.

O botão logo abaixo diz quantos passos faltam e abre o Assistente.
