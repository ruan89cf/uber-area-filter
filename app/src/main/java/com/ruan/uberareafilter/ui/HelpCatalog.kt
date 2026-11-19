package com.ruan.uberareafilter.ui

import com.ruan.uberareafilter.R

/**
 * Textos dos botões "?" do painel, escritos para quem nunca usou o app.
 * Chave = nome do id da view (switch, campo) ou nome da string do título do card.
 * Cada ajuda responde: o que faz, quando usar e um exemplo do dia a dia.
 */
object HelpCatalog {

    data class Help(
        val title: String,
        val body: String,
        val example: String? = null,
        val icon: Int = R.drawable.ic_info,
        val accent: Accent = Accent.TEAL
    )

    enum class Accent(val container: Int, val on: Int) {
        TEAL(R.color.acc_teal_container, R.color.acc_teal_on),
        BLUE(R.color.acc_blue_container, R.color.acc_blue_on),
        AMBER(R.color.acc_amber_container, R.color.acc_amber_on),
        ORANGE(R.color.acc_orange_container, R.color.acc_orange_on),
        VIOLET(R.color.acc_violet_container, R.color.acc_violet_on),
        ROSE(R.color.acc_rose_container, R.color.acc_rose_on),
        RED(R.color.acc_red_container, R.color.acc_red_on),
        CYAN(R.color.acc_cyan_container, R.color.acc_cyan_on),
        SLATE(R.color.acc_slate_container, R.color.acc_slate_on)
    }

    val items: Map<String, Help> = mapOf(
        // ---------- Cards (titulo) ----------
        "health_title" to Help(
            "Verificação do app",
            "O app precisa de algumas permissões do Android para ler as corridas e tocar nos botões por você. Esta lista mostra o que já está pronto (verde), o que é recomendado (amarelo) e o que está faltando (vermelho).\n\nToque em “Resolver” ao lado de um item e o app abre a tela certa do Android.",
            "Se aparecer “Bateria: o Android pode desligar o filtro”, toque em Resolver e escolha “Sem restrição”. Assim o celular não fecha o filtro no meio do turno.",
            R.drawable.ic_check_circle, Accent.TEAL
        ),
        "main_auto_title" to Help(
            "O que o app faz sozinho",
            "Aqui você decide se o app só avisa ou se ele também toca nos botões da 99 e da Uber por você.\n\nCom tudo desligado, o app só mostra o card colorido e você decide no dedo.\n\nCorridas “Negocia” da 99: se o valor do passageiro passa nas suas regras, o app aceita como uma corrida normal. Se só o preço não bate, o app calcula o menor valor que passa (R$/km e R$/h) e envia a contraproposta sozinho: toca no lápis, escreve o valor no campo, confere e toca em “Confirmar oferta”. Se precisar pedir mais de 50% acima, ou se o problema for área, bloqueio ou nota, recusa como sempre.",
            "Ligue “Recusar sozinho as corridas ruins” e deixe “Aceitar” desligado no começo. Assim você confere como o app decide antes de deixar ele aceitar.",
            R.drawable.ic_bolt, Accent.CYAN
        ),
        "main_modules_title" to Help(
            "Em quais apps você roda",
            "Ligue os aplicativos em que você fica online. O filtro só lê a tela desses apps e ignora todo o resto (WhatsApp, banco, mapa).",
            "Se hoje você só vai rodar na 99, desligue a Uber: o app gasta menos bateria.",
            R.drawable.ic_phone, Accent.BLUE
        ),
        "main_dual_title" to Help(
            "99 e Uber ao mesmo tempo",
            "Quando você roda nos dois apps juntos, existe o risco de aceitar uma corrida na Uber enquanto já está levando alguém da 99.\n\nCom a trava ligada, assim que você aceita uma corrida em um app, o outro fica travado até essa corrida terminar ou ser cancelada (por você ou pelo passageiro).\n\nCom o encaixe ligado, depois que o passageiro embarca, a oferta do outro app é refeita como se você saísse do DESTINO da corrida atual. O app aceita quando ela passa nas suas regras e o que falta da corrida mais a ida até o próximo passageiro cabe no tempo máximo (padrão 3 min). Ela vira a corrida #2.",
            "Passageiro da 99 a bordo, faltam 2 min para o destino. Toca uma da Uber com embarque a 1 min desse destino: 2 + 1 = 3 min, o app aceita. Se o embarque fosse a 2 min de lá (2 + 2 = 4 min), não aceitava.",
            R.drawable.ic_swap, Accent.VIOLET
        ),
        "main_fav_title" to Help(
            "Passageiros fixos",
            "Guarde os clientes que você leva sempre, com endereço e horário. O app avisa antes do horário para você se posicionar perto do passageiro.",
            "Dona Maria vai ao médico toda terça às 8h. Cadastre e o app avisa às 7h30, 7h40 e 7h50.",
            R.drawable.ic_favorite, Accent.ROSE
        ),
        "main_hourly_title" to Help(
            "Quanto a corrida paga",
            "Uma corrida pode parecer boa pelo valor, mas pagar pouco pelo tempo que leva. O app calcula quanto você ganharia por hora e por km e recusa o que ficar abaixo do mínimo que você escolher.",
            "Corrida de R$ 10 que leva 30 minutos = R$ 20 por hora. Se o seu mínimo é R$ 30/h, ela é recusada.",
            R.drawable.ic_money, Accent.AMBER
        ),
        "main_dist_title" to Help(
            "Distância",
            "Evita ir longe demais para buscar o passageiro ou aceitar uma viagem muito comprida que te leva para longe da sua área.",
            "Com “Distância máxima até o passageiro” em 2,5 km, uma corrida com busca a 4 km é recusada.",
            R.drawable.ic_route, Accent.BLUE
        ),
        "main_pax_title" to Help(
            "Passageiro e paradas",
            "Filtra pela nota do passageiro e pelas corridas com várias paradas no caminho (que demoram mais e pagam igual).",
            "Com nota mínima 4,80, um passageiro com nota 4,60 é recusado.",
            R.drawable.ic_person, Accent.VIOLET
        ),
        "main_fuel_title" to Help(
            "Combustível",
            "Informe quanto você paga no litro e quantos km o carro faz com um litro. O app desconta o combustível de cada corrida e mostra no card quanto sobra de verdade.",
            "Gasolina a R$ 6,29 e carro que faz 12 km/L: uma corrida de 6 km gasta R$ 3,15. Se ela paga R$ 15, sobram R$ 11,85.",
            R.drawable.ic_fuel, Accent.ORANGE
        ),
        "main_map_title" to Help(
            "Sua área de trabalho",
            "É o coração do app. Você desenha no mapa a região onde quer trabalhar (zona verde) e os lugares onde não quer entrar (zona vermelha).\n\nA primeira zona verde que você desenha é a sua área principal (Zona 1).\n\nTrocar zonas com um amigo: “Enviar zonas” gera um arquivo para mandar pelo WhatsApp. Quem recebe toca no arquivo, escolhe “Filtro Inteligente” (ou usa “Receber zonas”) e decide se junta com as dele ou substitui.",
            "Desenhe Olaria e Penha como zona verde e uma comunidade como zona vermelha. Corridas que saem da área ou entram na zona vermelha são recusadas.",
            R.drawable.ic_map, Accent.BLUE
        ),
        "main_rules_title" to Help(
            "Regras da área",
            "Decide o que fazer quando a corrida sai da sua área, volta para ela ou passa de uma zona sua para outra.",
            null, R.drawable.ic_place, Accent.VIOLET
        ),
        "main_risk_title" to Help(
            "Lugares perigosos",
            "Regras de segurança: recusar corridas em lugares que você marcou como perigosos ou que a própria 99 marca como área de risco.",
            null, R.drawable.ic_warning, Accent.RED
        ),
        "main_streets_title" to Help(
            "Listas de ruas (avançado)",
            "O app monta essas listas sozinho a partir do mapa. Só mexa aqui se quiser liberar ou bloquear uma rua específica na mão.\n\nNos bloqueados vale nome de lugar (“Caxias Shopping”), de rua, de bairro ou um endereço exato (“Rodovia Washington Luiz nº 2895”). Bloqueou um lugar pelo nome? O app aprende o endereço dele e também recusa as entradas com outro nome no mesmo endereço (ex.: “Entrada norte”).",
            "Escreva “Rua Uranos” em ruas liberadas para o app sempre aceitar essa rua, mesmo na divisa.",
            R.drawable.ic_list, Accent.SLATE
        ),
        "main_shots_title" to Help(
            "Fotos das corridas",
            "No instante em que a corrida aparece, o app tira uma foto da tela e junta com o card do filtro em cima, para você comparar o que a 99/Uber mostrou com o que o app decidiu. Fica guardada só no seu celular.",
            null, R.drawable.ic_camera, Accent.CYAN
        ),
        "main_history_title" to Help(
            "Últimas corridas",
            "Lista das corridas que o app leu, com a decisão e o motivo. Toque numa corrida para ver a foto, liberar ou bloquear a rua dela, ou salvar o passageiro como fixo.",
            null, R.drawable.ic_history, Accent.BLUE
        ),
        "main_visual_title" to Help(
            "Aparência",
            "Muda o tema do app (claro ou escuro) e o card que aparece por cima da 99 e da Uber: tamanho, cores e quanto tempo ele fica na tela.",
            null, R.drawable.ic_palette, Accent.ROSE
        ),
        "main_logs_title" to Help(
            "Diagnóstico (para suporte)",
            "Registros técnicos do que o app viu e fez. Você não precisa mexer aqui no dia a dia. Use “Compartilhar” para mandar os registros quando algo não funcionar como esperado.",
            null, R.drawable.ic_code, Accent.SLATE
        ),

        // ---------- Opcoes ----------
        "switchAutoAccept" to Help(
            "Aceitar sozinho as corridas boas",
            "Quando chega uma corrida que passa em todas as suas regras (área, valor, distância, nota), o app toca em Aceitar por você.\n\nCorridas com local incerto nunca são aceitas sozinhas: nelas você sempre decide.",
            "Corrida de Olaria para a Penha pagando R$ 35/h com o seu mínimo em R$ 30/h: aceita na hora.",
            R.drawable.ic_check_circle, Accent.CYAN
        ),
        "switchAutoReject" to Help(
            "Recusar sozinho as corridas ruins",
            "Quando a corrida não passa nas suas regras, o app toca no X por você. Isso poupa dezenas de toques por dia e evita ficar olhando o celular dirigindo.",
            "Destino fora da sua área: recusada em menos de 1 segundo, e o card vermelho mostra o motivo.",
            R.drawable.ic_error, Accent.CYAN
        ),
        "switchAutoContinue" to Help(
            "Responder “Continuar aceitando”",
            "Depois de várias recusas seguidas, a 99 pergunta se você quer continuar recebendo corridas. Com isso ligado, o app toca em “Continuar aceitando” sozinho e você não fica offline sem perceber.",
            null, R.drawable.ic_touch, Accent.CYAN
        ),
        "switchHumanDelay" to Help(
            "Esperar meio segundo antes de tocar",
            "Faz o app esperar um tempinho variado (entre 0,3 e 0,6 segundo) antes de tocar, como uma pessoa faria. Deixe ligado.",
            null, R.drawable.ic_touch, Accent.CYAN
        ),
        "switchFloatingBtn" to Help(
            "Botão flutuante",
            "Um botão redondo que fica por cima da 99 e da Uber. Cada toque muda o modo:\n\n• ON (verde): faz tudo.\n• PAUSA (laranja): só mostra o card, não toca em nada.\n• OFF (vermelho): desliga a leitura de tela. Use antes de abrir o app do banco, que reclama quando a leitura está ligada.\n\nNo OFF o botão fica um tempo na tela com uma contagem; toque nele para religar.\n\nSEGURE o botão para favoritar o passageiro ou para marcar um problema (🐞 Algo deu errado agora?). A marca fica no registro com a hora exata, para achar e corrigir depois.",
            "Vai fazer um Pix? Toque duas vezes no botão (ON → PAUSA → OFF), use o banco e depois toque no botão OFF para voltar. O app aceitou uma corrida errada? Segure o botão e toque em 🐞.",
            R.drawable.ic_power, Accent.CYAN
        ),
        "edtOffButtonMinutes" to Help(
            "Tempo do botão OFF",
            "Depois de desligar pelo botão flutuante, ele continua na tela por esse tempo, para você religar com um toque. Se acabar o tempo, ele some e o app fecha para poupar bateria. Coloque 0 para ele sumir na hora.",
            null, R.drawable.ic_power, Accent.CYAN
        ),
        "switchModule99" to Help("99 Motorista", "Liga a leitura das corridas da 99.", null, R.drawable.ic_phone, Accent.BLUE),
        "switchModuleUber" to Help(
            "Uber Driver",
            "Liga a leitura das corridas da Uber. Para o app conseguir tocar nos botões da Uber, também é preciso “Liberar o toque automático na Uber” (a Uber bloqueia o toque comum).",
            null, R.drawable.ic_phone, Accent.BLUE
        ),
        "switchChainApps" to Help(
            "Encaixar a próxima corrida do outro app",
            "Durante uma corrida num app, a oferta do outro app mostra a distância de onde o carro está AGORA. Mas você só fica livre no destino da corrida atual.\n\nCom isto ligado, o app mede a busca a partir desse destino, recalcula R$/h e R$/km e passa a oferta pelas suas regras (área, busca máxima, mínimo por hora).\n\nSó vale com o passageiro da corrida atual A BORDO (depois de “Iniciar corrida”). Indo buscar ou esperando o passageiro, nunca encaixa: ele pode demorar ou a corrida pode ser cancelada. Nesse caso vale a trava.",
            "Passageiro da 99 a bordo, faltam 2 min para a Vila São Luís. Toca Uber com embarque a 1 min de lá e destino dentro da área: 2 + 1 = 3 min, o app encaixa. O card mostra “Depois da 99: busca a 0,4 km do destino dela · falta 2 min + 1 min até o embarque = 3 min”.",
            R.drawable.ic_swap, Accent.VIOLET
        ),
        "edtChainMaxWait" to Help(
            "Tempo máximo do encaixe",
            "Soma o que falta da corrida atual (o app lê a “Chegada prevista” na tela da corrida) com o tempo de ir do destino dela até o próximo passageiro. Só encaixa se a soma couber nesse tempo. Quanto mais perto você está do destino, mais longe o próximo embarque pode ficar.",
            "3 minutos: faltando 3 min, só encaixa embarque no próprio destino; faltando 2 min, embarque a até 1 min; faltando 1 min, embarque a até 2 min.",
            R.drawable.ic_swap, Accent.VIOLET
        ),
        "switchDualConflictGuard" to Help(
            "Travar o outro app durante a corrida",
            "Enquanto você está numa corrida em um app, o outro fica travado para não aceitar uma segunda corrida sem querer. Escolha abaixo se ele só deixa de aceitar sozinho ou se recusa tudo.",
            null, R.drawable.ic_lock, Accent.VIOLET
        ),
        "switchFavAlerts" to Help("Avisos de horário", "Manda uma notificação 30, 20 e 10 minutos antes do horário de cada passageiro fixo.", null, R.drawable.ic_notifications, Accent.ROSE),
        "switchFilterHourly" to Help(
            "Mínimo por hora",
            "O app estima quanto você ganharia em uma hora fazendo corridas iguais a essa (já contando a busca e 2 minutos de espera). Abaixo do mínimo, recusa.",
            "R$ 12 por uma corrida de 24 minutos = R$ 30/h.",
            R.drawable.ic_money, Accent.AMBER
        ),
        "switchUseNetHourlyRate" to Help(
            "Descontar o combustível",
            "Faz o mínimo por hora usar o valor que sobra depois do combustível, que é o que realmente fica com você. Precisa do preço e do consumo preenchidos em Combustível.",
            "R$ 32/h brutos podem virar R$ 27/h depois da gasolina.",
            R.drawable.ic_fuel, Accent.AMBER
        ),
        "switchRoundDownNetHourly" to Help("Arredondar para baixo", "Ignora os centavos para ser mais rigoroso: R$ 29,80 por hora conta como R$ 29.", null, R.drawable.ic_money, Accent.AMBER),
        "switchFilterPricePerKm" to Help(
            "Mínimo por km",
            "Divide o valor da corrida pelos km rodados (busca + viagem). Abaixo do mínimo, recusa. Bom para não rodar muito por pouco.",
            "R$ 9 por 10 km = R$ 0,90 por km. Com mínimo de R$ 1,20, recusa.",
            R.drawable.ic_route, Accent.AMBER
        ),
        "switchTrafficCheck" to Help("Conferir o trânsito", "Último passo antes de aceitar sozinho: o app pergunta ao serviço de mapas (AWS ou Google, pela chave colada) quanto tempo a corrida leva com o trânsito de agora (sua posição → embarque → destino) e refaz o R$/h. Se o trânsito derrubar o R$/h abaixo do seu mínimo, ele não aceita. Se não tiver chave, internet ou resposta em 1,5 s, aceita normal pela estimativa da 99. Manda só coordenadas, nada de nome. No campo de km, 0 = conferir todas; 2,5 = só corridas de 2,5 km para cima (Av. Brasil sempre confere).", "A 99 diz 11 min para Bonsucesso, mas a Av. Brasil está parada e o Google diz 25 min: o R$ 40/h vira R$ 17/h e o app recusa.", R.drawable.ic_info, Accent.CYAN),
        "switchNegociaHourly" to Help("Mínimo diferente na Negocia", "Nas corridas Negocia da 99 você pode pedir o valor. Ligado, o mínimo por hora daqui vale no lugar do mínimo normal, só nessas corridas: para aceitar e para calcular a contraproposta. As outras regras (R$/km, área, nota) continuam as mesmas.", "Seu mínimo normal é R$ 30/h e aqui está R$ 45/h: uma Negocia que paga R$ 35/h não é aceita; o app pede o valor que dá R$ 45/h.", R.drawable.ic_money, Accent.AMBER),
        "switchUberCustomHourly" to Help("Mínimo diferente na Uber", "Use um mínimo por hora só para a Uber, que costuma pagar diferente da 99.", null, R.drawable.ic_money, Accent.AMBER),
        "switchFilterTotalDist" to Help("Tamanho máximo da corrida", "Recusa corridas cuja distância total (ir buscar + levar) passa do limite.", "Limite 15 km: uma corrida de 3 km de busca + 14 km de viagem é recusada.", R.drawable.ic_route, Accent.BLUE),
        "switchFilterPickupDist" to Help("Distância até o passageiro", "Recusa quando o passageiro está longe demais de você. Ir buscar longe é rodar sem ganhar.", "Limite 2,5 km: passageiro a 3,1 km é recusado.", R.drawable.ic_route, Accent.BLUE),
        "switchFilterRating" to Help("Nota mínima", "Recusa passageiros com nota abaixo do mínimo.", null, R.drawable.ic_person, Accent.VIOLET),
        "switchRejectNewPassenger" to Help("Passageiro novo", "Passageiro novo ainda não tem nota. Com isso ligado, ele é recusado (só vale com o filtro de nota ligado).", null, R.drawable.ic_person, Accent.VIOLET),
        "switchRejectUberStops" to Help("Paradas na Uber", "Recusa corridas da Uber que têm uma ou mais paradas no caminho.", null, R.drawable.ic_route, Accent.VIOLET),
        "switchFuelEnabled" to Help("Gasto e lucro no card", "Mostra no card da corrida quanto você vai gastar de combustível e quanto sobra.", "“R$ 1,20 de gasolina · Lucro R$ 13,80”", R.drawable.ic_fuel, Accent.ORANGE),
        "switchRequireBoth" to Help(
            "Busca e destino na área",
            "Ligado: o app confere onde o passageiro está e para onde ele vai. Desligado: confere só o destino.",
            null, R.drawable.ic_place, Accent.VIOLET
        ),
        "switchRequireSameZone" to Help(
            "Corridas entre as suas zonas",
            "Se você desenhou mais de uma zona verde, uma corrida pode sair de uma e ir para outra.\n\nLigado: ela aparece num card roxo para você decidir.\nDesligado: ela é recusada e fechada sozinha.\n\nVoltar para a Zona 1 (a principal) nunca conta como “entre zonas”.",
            "Zonas: Olaria (Zona 1) e Bonsucesso (Zona 2). Bonsucesso → Olaria: é volta para casa, aceita normal. Olaria → Bonsucesso: é entre zonas.",
            R.drawable.ic_swap, Accent.VIOLET
        ),
        "switchAutoAcceptInterZone" to Help("Aceitar sozinho entre zonas", "Aceita as corridas entre zonas sem esperar você. Só funciona com “Aceitar corridas entre as suas zonas” ligado.", null, R.drawable.ic_swap, Accent.VIOLET),
        "switchIntoArea" to Help(
            "Corrida que traz você para a área",
            "O objetivo é você terminar a corrida dentro da sua área. Por isso, se o passageiro está fora mas o destino é dentro, a corrida conta como boa (✔).\n\nCorrida que busca em zona vermelha continua sendo recusada, e o limite de distância até o passageiro continua valendo.",
            "Você está em Ramos (fora da área). Corrida de Ramos para Olaria (sua área): aceita, porque te leva de volta.",
            R.drawable.ic_place, Accent.VIOLET
        ),
        "switchYellowOutside" to Help("Card amarelo de retorno", "Quando a busca é fora da área, mas a até 1 km, e o destino é dentro, o app mostra um card amarelo para você decidir. Só vale com a opção acima desligada.", null, R.drawable.ic_place, Accent.VIOLET),
        "switchZoneCardColors" to Help(
            "Cor da zona no card",
            "Pinta o card com a cor da zona de destino. Assim você sabe para onde vai sem precisar ler. A Zona 1 fica com a cor normal; as outras você escolhe ao desenhar.",
            null, R.drawable.ic_palette, Accent.VIOLET
        ),
        "switchAvBrasilOdd" to Help("Av. Brasil", "Na Av. Brasil, o lado ímpar (Bonsucesso/Ramos) é aceito e o lado par (Maré) é recusado, pelo número do endereço.", null, R.drawable.ic_warning, Accent.RED),
        "switchRejectRiskBadge" to Help("Selo “Área de risco”", "A 99 coloca esse selo em algumas corridas. Com isso ligado, toda corrida com o selo é recusada.", null, R.drawable.ic_warning, Accent.RED),
        "switchRejectUncertain" to Help(
            "Recusar quando houver dúvida",
            "Às vezes o endereço vem incompleto (rua de divisa sem número, só o bairro) e o app não tem certeza se é dentro da área. Ligado: recusa. Desligado: mostra um card laranja e você decide.",
            null, R.drawable.ic_help, Accent.RED
        ),
        "switchSaveRideScreenshots" to Help("Guardar fotos", "Tira uma foto da tela em cada corrida que aparece. As fotos ocupam espaço; apague de vez em quando.", null, R.drawable.ic_camera, Accent.CYAN),
        "switchDarkTheme" to Help("Tema escuro", "Deixa o app escuro. Cansa menos a vista à noite.", null, R.drawable.ic_palette, Accent.ROSE),
        "switchShowRideBanner" to Help("Card da corrida", "O card colorido que aparece por cima da 99/Uber com o valor, a decisão e o motivo. Desligue se não quiser ver nada na tela.", null, R.drawable.ic_layers, Accent.ROSE),
        "switchDebug" to Help("Registrar os textos lidos", "Guarda nos registros o que o app leu da tela. Útil quando uma corrida não foi entendida. Deixa o registro maior.", null, R.drawable.ic_code, Accent.SLATE),
        "switchXrayDiagnostic" to Help("Raio-X", "Registro ainda mais detalhado (posição das janelas e dos botões). Só ligue se pedirem para investigar um problema.", null, R.drawable.ic_code, Accent.SLATE),
        "edtMaxRideHistory" to Help(
            "Quantas corridas guardar",
            "Opção de desenvolvedor: quantas corridas o histórico guarda, cada uma com a sua foto (a tela da oferta com o card do filtro em cima). Com 500 dá para conferir dias de rodagem. Cada foto ocupa uns 150 a 300 KB: 500 fotos dão uns 100 MB, só no seu celular.",
            "Deixe 500 enquanto estiver ajustando o app; depois pode baixar para 50.",
            R.drawable.ic_camera, Accent.SLATE
        ),
    )
}
