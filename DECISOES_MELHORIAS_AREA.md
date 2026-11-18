# Decisões sobre a avaliação técnica: demarcação de área

Este documento acompanha `avalia_o_t_cnica_do_filtro_inteligente.md`. Cada ponto da avaliação foi conferido no código real antes de qualquer decisão.

## 1. A avaliação está certa?

Em boa parte, sim. Mas ela deixou de fora a **causa raiz** do problema de demarcação, e alguns pontos estão imprecisos.

### Confirmado no código

| # | Achado da avaliação | Situação real |
|---|---|---|
| 1 | Nome da rua atropela o GPS | **Confirmado** (`AreaEvaluator`, bloco `if (matchedStreet != null) ok = true` depois do Geocoder dizer "fora"). |
| 2 | Aprova se qualquer um dos 5 palpites do Geocoder cair dentro | **Confirmado**. |
| 3 | Busca sem limite geográfico como último recurso | **Confirmado**. |
| 4 | `contains("mare")` casa com "Marechal"; regex ignora nº de 1 dígito | **Confirmado**. |
| 5 | Palavras bloqueadas por substring | **Confirmado**. Detalhe: o card de oferta não mostra o nome do passageiro. O erro real é a substring ("Luz" bloqueava "Santa Luzia"). |
| 6 | Latência de até ~14 s | **Confirmado, com ressalva**: embarque e destino já rodavam em paralelo e cada chamada já tinha 1,8 s de limite. O problema era o encadeamento de até 8 chamadas por endereço (4 variações × com/sem caixa). |
| 7 | Um processo novo por toque | **Parcial**: o daemon já é um socket persistente. O custo que sobra é o `input tap` disparado a cada toque. |
| 8 | Trava Uber/99 só em memória | **Confirmado**. |
| 9 | Lucro ignora o retorno vazio | **Confirmado**. |
| 10 | Diagnóstico ligado por padrão, sem regra de backup | **Confirmado**. |
| 11 | `release-key.jks` e senhas no projeto | **Confirmado**, e o projeto tem remoto no GitHub. As senhas estavam escritas no `build.gradle.kts`. |

### O que a avaliação não viu (e era o mais grave)

1. **A lista de ruas da zona inclui por construção todas as ruas que encostam na zona**, inclusive as que entram e saem dela. Não basta inverter a precedência rua × GPS: enquanto essa lista existir, uma rua de divisa sem número (caso comum no embarque da Uber) é aprovada só pelo nome.
2. **Havia tolerância de 45 m para fora da divisa**: um ponto já fora do polígono contava como dentro. A distância era medida só até os vértices, não até as arestas.
3. **A caixa de busca do Geocoder força homônimos para dentro**: com a busca restrita a um raio de cerca de 2,8 km, uma "Rua Santa Maria" distante pode voltar com coordenadas de outra rua de mesmo nome perto da zona. A bounding box fixa do Grande Rio proposta na avaliação (P3) mantém esse problema.
4. **Zonas vizinhas** (Olaria | Penha | Ramos…): a correção ingênua, sem considerar a união das zonas, faria uma rua que passa de uma zona verde para a outra ser tratada como "de divisa".
5. **Embarque só com bairro** ("Olaria, Rio de Janeiro"): nenhuma regra de rua resolve esse caso.

## 2. O que foi feito: motor de localização com nível de confiança

Princípio: **só aprova quando há prova de que o endereço está dentro**. Sem prova suficiente, o resultado é **🟠 Local incerto**, que nunca é aceito automaticamente.

### Evidências, da mais forte para a mais fraca

1. **Zona proibida**: qualquer coordenada validada a até 25 m dela → recusa.
2. **Coordenada do número**: só vale se o Geocoder devolver a **mesma rua** e o **mesmo número** do card, e o bairro não conflitar.
   - ≥ 40 m dentro (da união das zonas verdes) → aprova.
   - ≥ 40 m fora → recusa.
   - Faixa da divisa → depende da rua (veja abaixo).
3. **Rua interna**: o mapa é analisado ao salvar as zonas, e cada rua é classificada assim:
   - **interna**: o traçado inteiro (inclusive homônimos até ~500 m da zona) fica dentro da área, sem correr pela divisa e sem tocar zona proibida. Só essas aprovam **só pelo nome**, e o nome precisa ser **idêntico** (sem tolerância de letra: "Santa Lúcia" nunca aprova "Santa Luzia").
   - Mesmo assim, o mapa é consultado: se o bairro do card fica a mais de 2,5 km da área, ou se a rua só aparece em outro bairro longe dali, é **homônimo** → 🟠 incerto.
   - **de divisa**: entra e sai da área. Sem número → 🟠 incerto.
   - **não encosta em nenhuma zona** → recusa.
4. **Ruas confiáveis**: as que você marcar no histórico com "Adicionar à Área Permitida" aprovam pelo nome (é decisão sua).
5. **Seu GPS (opcional)**, só para o embarque: a distância de rota até o embarque é sempre ≥ à distância em linha reta. Se você está mais fundo na área do que 1,5 × essa distância + precisão do GPS + 100 m, o embarque está **garantidamente** dentro. Isso resolve o "Olaria, Rio de Janeiro".
6. **Só POI/bairro**: um POI só é aprovado se estiver ≥ 250 m dentro da área. Só o bairro **nunca** aprova.

### Outras correções
- O Geocoder agora roda em paralelo (rua+nº e rua sem nº), com teto de 2,4 s por endereço e cache. A busca usa caixa de ~9 km e não tem mais fallback nacional.
- Palpites do Geocoder com outra rua, outro bairro, outra cidade ou outro estado são descartados.
- Av. Brasil: "Maré" e os demais indicadores só casam como palavra inteira (Marechal Hermes liberado), e o número pode ter 1 dígito.
- Palavras bloqueadas casam só como palavra inteira.
- Zona redesenhada, criada ou apagada (inclusive uma zona **vizinha**): o índice de ruas de todas as zonas é invalidado sozinho, e até ser refeito nada é aprovado só pelo nome. Zonas antigas são analisadas automaticamente ao abrir o app.
- "Adicionar à Área Permitida" no histórico agora guarda só o nome da rua. Antes ia junto o prefixo "UBER: …", e a rua nunca casava (defeito que já existia).

### Novidades na tela
- **Card 🟠 "Local incerto — confira"**: não aceita sozinho, e você decide.
- Interruptor em **Zonas › Vias e áreas de risco**: *"Na dúvida sobre o local, recusar automaticamente"* (desligado por padrão).

  Por que desligado: deixar a oferta incerta na sua mão nunca aceita fora da área e ainda permite aceitar com o dedo. Se preferir mãos livres ("na dúvida, recusa"), ligue.
- Ao abrir o app, ele pede a localização "Permitir o tempo todo" uma única vez. É opcional; sem a permissão, só a prova por GPS fica desligada.

## 3. Itens da avaliação que NÃO foram feitos agora (e por quê)

| Proposta | Decisão |
|---|---|
| P2 "só o candidato #1 aprova" | **Substituída por algo mais forte**: o candidato precisa conferir rua, número e bairro, e todos os candidatos validados precisam concordar. |
| P3 bounding box fixa do Grande Rio | **Substituída**: caixa das suas zonas + 9 km, validando estado, cidade e bairro. A caixa fixa não impedia o homônimo forçado. |
| P4 limitar a regra do par/ímpar a um trecho numérico | **Não feito**: depende dos números exatos do trecho da Maré, que só você conhece, e suas zonas ficam na região da Maré. A faixa "1.000–14.000" da avaliação é uma estimativa e não foi verificada. |
| P7 toque < 15 ms | **Adiado**: o daemon já é persistente. Ganhar mais exige injetar eventos sem `input`, e isso não afeta a demarcação. |
| P8 trava Uber/99 em disco | **Adiado**: é válido, mas é outro problema (aceite duplo). Bom próximo passo. |
| P9/P10 retorno vazio e painel do turno | **Adiado**: é financeiro, fora do gargalo de demarcação. |
| P11 diagnóstico desligado por padrão | **Mantido ligado**: o celular é seu e os prints e o Raio-X são o que permite calibrar a área em campo. Os prints das corridas agora ficam **fora do backup do Google**. |
| P12 remover `_fontes_espelho` | **Mantido**: a pasta rasa permite revisar o código por ferramentas que não alcançam pastas profundas. Já está no `.gitignore`. |

## 4. Assinatura do APK

- As senhas saíram do `app/build.gradle.kts` e foram para `keystore.properties` (raiz do projeto). O arquivo já vem preenchido com os mesmos valores, então o build continua igual.
- O `.gitignore` agora ignora `*.jks`, `*.keystore` e `keystore.properties`.
- **Falta você rodar uma vez** (o arquivo já estava rastreado pelo git):
  `git rm --cached app/release-key.jks` e depois commit.
- Se o repositório do GitHub for **público**, a chave e a senha já vazaram no histórico. Gerar uma chave nova exige desinstalar e reinstalar o app no celular, porque a assinatura muda. Se for privado, basta o passo acima.

## 5. Revisão final (o que foi corrigido na segunda passada)

1. Rua interna sem número era aprovada **sem consultar o mapa** → homônimo de outro bairro passaria. Agora sempre consulta.
2. Busca só dentro da caixa podia esconder o homônimo verdadeiro → agora busca com **e** sem caixa, em paralelo.
3. Nome com 1 letra diferente aprovava como rua interna → agora a aprovação só pelo nome exige nome idêntico.
4. Mudar uma zona vizinha não invalidava o índice das outras → corrigido.
5. Prefixo "UBER:"/"99:" impedia as ruas confiáveis de funcionar → corrigido.
6. Pedido de permissão de localização trocado para a API de resultado do AndroidX (evita erro de compilação dependendo da versão da biblioteca).
7. **Erro real de compilação** (visto no seu `build_log.txt`): no `app/build.gradle.kts`, `java.util.Properties()` não resolve dentro do bloco `android { }`. Corrigido com `import java.util.Properties` no topo.
8. O `aplicar_melhorias_area.bat` não sobrescreve mais o backup original se for rodado de novo.

## 6. Como aplicar e testar

1. Rode `aplicar_melhorias_area.bat` (de novo, se já tinha rodado: os fontes mudaram na revisão). Na primeira vez ele faz backup dos fontes originais em `_backup_antes_melhorias_area\`; nas seguintes, mantém esse backup intacto.
2. Rode `instalar_no_celular.bat` como sempre.
3. Abra o app. Ele analisa as ruas das zonas já salvas e mostra um aviso "X internas • Y de divisa". Para forçar a análise, reabra e salve o mapa.
4. Testes: `gradlew test`. Foram incluídos casos de rua de divisa, número fora, divisa entre zonas vizinhas, zona proibida, Marechal Hermes, homônimo em outro bairro e GPS.

### O que observar em campo (logs)
- `⚠ … Local incerto` mostra o motivo exato, por exemplo "rua de divisa sem nº", "na divisa: 12 m" ou "só bairro".
- Se uma rua que você sabe ser segura aparecer como incerta com frequência, use "Adicionar à Área Permitida" no histórico.
- Se ruas internas aparecerem como divisa, a zona provavelmente foi desenhada passando pelo meio de quarteirões. Redesenhe a borda pela rua.

> Compilação: a primeira versão destas mudanças compilou e foi instalada no seu celular (`BUILD SUCCESSFUL`, moto g86). A parte de design visual (seção 7) ainda não foi compilada.

## 7. Design visual

As decisões de cor, tipografia, níveis do card, abas e tema escuro estão em `DESIGN_VISUAL.md`.

## 8. Status dos recursos e instalação

Para o motorista, o guia é `GUIA_INSTALACAO.md`. Aqui ficam as decisões técnicas.

### Status corrigido (`setup/SetupChecks.kt`, fonte única)
- **Motor interno (ADB Wi-Fi):** o PING é por socket e, na thread principal, o Android bloqueia (`NetworkOnMainThreadException`). Por isso o log do serviço dizia "não pareado" mesmo com o motor rodando. Agora o PING roda fora da thread principal, e quem pergunta da thread principal recebe o último resultado conhecido.
- **Acessibilidade:** antes, "OK" só queria dizer "ligada nas Configurações". Agora há três estados: desligada, ligada mas parada pelo Android, e funcionando. O terceiro vem de uma flag do próprio serviço (`onServiceConnected`/`onDestroy`).
- **Sobrepor tela** deixou de ser cobrada. Os cards em cima da 99 e da Uber usam `TYPE_ACCESSIBILITY_OVERLAY`; só a prévia do painel precisa dessa permissão.
- **Shizuku:** antes aparecia "Ativo" só com a permissão dada. Agora distingue negado de vez (o pedido não aparece mais) e conectando, e só mostra "ligado" com o serviço de toque conectado.
- **Toque na Uber recusado:** quando o motor responde `ERR`, o painel avisa. Na Xiaomi isso costuma ser falta da "Depuração USB (configurações de segurança)".
- **Novos itens:** bateria e notificações.

### Menos passos para o motorista
- **Assistente** (`ui/SetupWizardActivity.kt`) com status ao vivo, caminho por fabricante e um botão que abre a tela certa. Abre sozinho no primeiro uso.
- **Pareamento do motor:** aproveita a conexão ADB que o motorista já autorizou e, uma única vez, roda `pm grant WRITE_SECURE_SETTINGS`, `appops ACCESS_RESTRICTED_SETTINGS allow` e `deviceidle whitelist`. Com isso:
  - a Acessibilidade liga com um toque ("Ligar automaticamente");
  - o aviso de "configuração restrita" some;
  - depois de reiniciar o celular, num Wi-Fi já autorizado, o app liga a Depuração por Wi-Fi sozinho, sobe o motor e desliga a depuração de novo. No máximo uma tentativa a cada 10 minutos, e dá para desligar no Assistente.
- **Pelo PC:** `instalar_no_celular.bat` faz as mesmas liberações pelo cabo.
- **Para enviar o app:** `gerar_apk_para_enviar.bat` gera o APK assinado e o texto `COMO_INSTALAR.txt`.
- **Para quem já tinha o motor ligado antes desta versão:** use "Liberar atalhos" no Assistente (com a Depuração por Wi-Fi ligada) para ganhar o religamento automático.

### Para desfazer
Copie de volta o conteúdo de `_backup_antes_instalacao`:
- `main\...` vai para `app\src\main\java\com\ruan\uberareafilter\...`;
- `res\...` vai para `app\src\main\res\...`;
- `AndroidManifest.xml` vai para `app\src\main\`;
- `app_build.gradle.kts` vai para `app\build.gradle.kts`.
- apague os arquivos novos: `setup\SetupChecks.kt`, `ui\SetupWizardActivity.kt`, `res\layout\activity_setup_wizard.xml` e o teste `SetupChecksTest.kt`.
