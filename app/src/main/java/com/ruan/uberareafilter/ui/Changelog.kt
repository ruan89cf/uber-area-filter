package com.ruan.uberareafilter.ui

/**
 * Lista de novidades por atualizacao. A cada nova versao: suba versionCode no build.gradle.kts
 * e adicione um Release NO TOPO com o mesmo versionCode. A tela de Novidades abre sozinha uma vez
 * por atualizacao mostrando tudo que veio depois da ultima versao vista.
 */
object Changelog {

    data class Item(val emoji: String, val title: String, val body: String)
    data class Release(val versionCode: Int, val versionName: String, val date: String, val items: List<Item>)

    val releases: List<Release> = listOf(
        Release(
            versionCode = 17, versionName = "2.6", date = "18/11/2026",
            items = listOf(
                Item("⏳", "Botão novo em ampulheta", "O ícone do app e o ON/PAUSA viraram um botão só: em cima o ícone (toque para abrir o app, sem passar pelas configurações do Android), embaixo o estado (toque para pausar/ligar). Arrastando, vão os dois juntos. Segurar continua abrindo o “🐞 Algo deu errado?”."),
                Item("🎨", "Cores de cada app", "Com a 99 na frente: ON amarelo e PAUSA preto. Com a Uber: ON preto e PAUSA âmbar, como antes. Desligado continua vermelho. Ícone novo, sem fundo."),
                Item("💬", "R$/h próprio na Negocia", "Em Filtros, “Mínimo por hora diferente na Negocia”: ligado, esse valor vale no lugar do mínimo normal só nas corridas Negocia (para aceitar e para calcular quanto pedir). Ex.: normal R$ 50/h e Negocia R$ 40/h → nas Negocia vale R$ 40/h."),
                Item("🔁", "“Aceitar se tocar de novo” só na 99", "A Uber não repete a mesma corrida, então o botão agora só aparece nas corridas da 99.")
            )
        ),
        Release(
            versionCode = 16, versionName = "2.5", date = "18/11/2026",
            items = listOf(
                Item("💬", "Negocia no automático", "Se o valor do passageiro passa nas suas regras, o app aceita como uma corrida normal. Se só o preço não bate, ele calcula o menor valor que passa no seu R$/km e R$/h e envia a contraproposta sozinho: toca no lápis, escreve o valor no campo (sem usar o teclado), confere na tela e toca em “Confirmar oferta”. Se algo sair diferente, ele desiste e mostra o valor para você digitar. Se o passageiro aceitar, a corrida entra no painel de Ganhos com o valor pedido."),
                Item("🛡️", "Sempre com as suas regras", "Só envia com o aceite automático ligado e nunca confirma um valor abaixo do mínimo. Se precisar pedir mais de 50% acima do passageiro, ou se o problema for área, bloqueio ou nota, recusa como sempre.")
            )
        ),
        Release(
            versionCode = 15, versionName = "2.4", date = "18/11/2026",
            items = listOf(
                Item("🛠️", "Visual novo da 99", "A 99 colocou um ícone redondo antes do tempo de cada trecho e o app lia “O6 min” no lugar de “6 min”: a corrida era ignorada (nem aparecia o card) ou o destino era lido como embarque. Agora o app lê os dois visuais, o novo e o antigo — se a 99 voltar atrás, continua funcionando."),
                Item("📱", "Só a 99 ligada (ou só a Uber)", "Com um app só, o filtro agora também acompanha a corrida: “Corrida aceita”, “Iniciar/Finalizar corrida”, o painel “Mais” e “Você cancelou a corrida”. Cancelamento conta como cancelado no painel de Ganhos e a corrida que você aceita na mão também entra."),
                Item("💬", "Corridas Negocia", "Na Negocia o app nunca aceita sozinho: o valor é do passageiro e você decide. Se só o preço não bate com as suas regras, ele não recusa e mostra quanto pedir (ex.: “Negocie: peça 24,30” = qual botão da 99 tocar, ou o valor para digitar no lápis). Se o problema for área, bloqueio ou nota, recusa como sempre. A tela do lápis (“Sua oferta”) nunca é tocada.")
            )
        ),
        Release(
            versionCode = 14, versionName = "2.3", date = "18/11/2026",
            items = listOf(
                Item("🗺️", "Trocar zonas com amigos", "Na aba Zonas: “Enviar zonas” gera um arquivo com as suas zonas para mandar pelo WhatsApp. Quem recebe toca no arquivo e escolhe “Filtro Inteligente” (ou usa “Receber zonas”), e decide se JUNTA com as dele (a Zona 1 dele continua a principal) ou SUBSTITUI. As ruas de cada zona vão junto.")
            )
        ),
        Release(
            versionCode = 13, versionName = "2.2", date = "18/11/2026",
            items = listOf(
                Item("🛠️", "Marcar problema na hora", "Algo deu errado (aceitou corrida ruim, travou, recusou uma boa)? Segure o botão flutuante e toque em “🐞 Algo deu errado agora?”. Fica uma marca no registro com a hora e o que o app estava vendo, para achar e corrigir depois."),
                Item("📸", "Até 500 corridas com foto", "O histórico agora guarda até 500 corridas, cada uma com a sua foto (antes eram 20). Dá para mudar em Diagnóstico → Opções de desenvolvedor. Na lista, toque em “Mostrar mais” para ver as antigas."),
                Item("📋", "Registro mais completo", "O registro guarda vários dias (antes perdia a manhã num dia longo), anota por que o Android fechou o app (falta de memória, travamento, atualização) e, a cada 10 min, se o celular está esquentando e se a tela está ligada.")
            )
        ),
        Release(
            versionCode = 12, versionName = "2.1", date = "18/11/2026",
            items = listOf(
                Item("🟣", "Encaixe 99/Uber com a sua regra", "Só encaixa a próxima corrida do outro app com o passageiro atual JÁ A BORDO (depois de iniciar a corrida). Indo buscar ou esperando o passageiro, nunca encaixa. E o que falta da corrida mais a ida até o próximo passageiro tem que caber em 3 min (dá para mudar): faltando 2 min, o próximo embarque pode estar a até 1 min do destino; quanto mais perto do destino, mais longe ele pode estar.")
            )
        ),
        Release(
            versionCode = 11, versionName = "2.0", date = "18/11/2026",
            items = listOf(
                Item("🟣", "Encaixe entre 99 e Uber", "Durante uma corrida num app, a oferta do outro app agora é medida a partir do DESTINO da corrida atual (onde você fica livre), não de onde o carro está. Se a busca a partir de lá for curta, passar nas suas regras e a corrida atual estiver acabando, o app aceita e ela entra na fila. O card mostra “Depois da 99: busca a 1,2 km do destino dela”."),
                Item("🔁", "Botão “Aceitar se tocar de novo”", "Quando o app recusa ou não aceita uma corrida, aparece um botão discreto embaixo do card. Toque nele e, se a mesma corrida tocar de novo nos próximos 20 min, o app aceita sozinho. Se ela ainda estiver na tela, aceita na hora."),
                Item("🧭", "Bloqueio pelo endereço", "Bloqueou um lugar pelo nome (ex.: Caxias Shopping)? O app aprende o endereço dele e também recusa as entradas com outro nome no mesmo lugar (ex.: “Entrada norte, Rod. Washington Luiz, 2895”). No histórico, “Bloquear” agora pergunta: só o local, só o endereço, a rua inteira ou o bairro."),
                Item("🔒", "Cancelamento feito por você", "“Você cancelou a corrida” agora é reconhecido: a corrida sai do ganho e a trava 99/Uber libera sozinha. Aceitou por engano e voltou para a tela inicial sem iniciar a viagem? Também conta como cancelada."),
                Item("💰", "Horas online de verdade", "O painel só conta horas enquanto chegam ofertas ou há corrida em andamento. Pausa de mais de 20 min e a tela “Você está offline” não contam mais, nem o app aberto sem você estar online. Agora mostra também o tempo em corrida e o ganho por hora em corrida."),
                Item("🧾", "Fim das corridas fantasmas", "Corrida que o app não viu terminar não fica mais aberta para sempre (ela inflava as horas e entrava na fila de corridas novas)."),
                Item("📸", "Foto da corrida com o card", "A foto guardada agora junta o card do filtro em cima da tela da 99/Uber, para comparar o que o app decidiu com o que apareceu."),
                Item("📏", "Quanto fora da área", "Quando o app sabe o número da rua, o card diz quanto o endereço fica fora da área (ex.: “1,8 km fora da área”).")
            )
        ),
        Release(
            versionCode = 10, versionName = "1.9", date = "17/11/2026",
            items = listOf(
                Item("📱", "Motor da Uber com qualquer Wi-Fi", "Depois de reiniciar o celular, basta conectar em qualquer Wi-Fi por uns 10 segundos, até no roteador de outro celular sem internet. O app liga o motor sozinho, sem você abrir nada, e ele segue no 4G/5G até o próximo reinício. Antes, Wi-Fi sem internet não era reconhecido.")
            )
        ),
        Release(
            versionCode = 9, versionName = "1.8", date = "17/11/2026",
            items = listOf(
                Item("🎨", "Cores com uma piscadinha", "O app agora se veste com as cores dos colegas: preto e verde no estilo Uber quando está ligado, amarelo no estilo 99 para dinheiro e pausa, e vermelho no estilo iFood para risco e o que está faltando. No painel de Ganhos, a 99 aparece em amarelo e a Uber em preto.")
            )
        ),
        Release(
            versionCode = 8, versionName = "1.7", date = "17/11/2026",
            items = listOf(
                Item("🛠️", "Fim dos cancelamentos falsos", "O atalho de voz \"OK, corrida cancelada\" que fica no mapa da 99 às vezes era lido junto com o nome da rua e o app achava que a corrida tinha sido cancelada: tirava do ganho e soltava a trava no meio da viagem. Corrigido."),
                Item("🚗", "Corrida aceita durante a viagem", "Quando a 99 manda a próxima corrida antes de você terminar a atual, ela agora espera na fila. Antes a corrida nova era marcada como concluída na hora em que a anterior terminava."),
                Item("💰", "Valor da corrida mesmo com a tela borrada", "Se o preço não der para ler, o painel de Ganhos usa o R$/hora da 99 para estimar o valor (marcado como \"estimado — confira\") em vez de gravar R$ 0."),
                Item("🔒", "Corrida não se perde ao religar", "Desligar e religar o filtro (ou uma atualização) no meio de uma corrida não faz mais o app esquecer dela."),
                Item("⏱️", "Botão OFF sem religar sem querer", "Um toque rápido logo depois de desligar caía no botão OFF que acabou de aparecer e religava o filtro. Agora ele ignora toques no primeiro segundo."),
                Item("✅", "Aceite que não virou corrida", "Se o aceite automático não pegar (outro motorista levou), a corrida sai do ganho sozinha depois de 1 min e meio na tela livre.")
            )
        ),
        Release(
            versionCode = 7, versionName = "1.6", date = "17/11/2026",
            items = listOf(
                Item("✨", "Visual novo", "Cada assunto tem a sua cor (dinheiro em âmbar, mapa em azul, regras em violeta, risco em vermelho), ícones de verdade no lugar dos emojis e um topo com o estado do filtro sempre à vista."),
                Item("🚦", "Verificação do app que diz o que falta", "Cada item aparece em verde (pronto), amarelo (recomendado) ou vermelho (falta), com uma frase simples do que fazer e um botão “Resolver” que abre a tela certa do Android."),
                Item("❓", "Botões de ajuda", "Toque no “?” ao lado de qualquer opção para ver o que ela faz, quando usar e um exemplo do dia a dia."),
                Item("🔤", "Textos mais claros", "Todas as opções foram reescritas com palavras do dia a dia do motorista."),
                Item("🎬", "Transições suaves", "As abas aparecem com movimento, as seções abrem e fecham sem pular, o cartão principal muda de cor com o estado e as chaves dão um toque leve ao virar."),
                Item("🎨", "Cartão principal colorido", "Verde quando o filtro está ligado, laranja na pausa (com botão para sair dela), vermelho quando falta uma permissão e cinza quando está desligado.")
            )
        ),
        Release(
            versionCode = 6, versionName = "1.5", date = "17/11/2026",
            items = listOf(
                Item("🧲", "Corrida que leva você para a área", "Embarque fora da área com destino dentro agora é ✔ e conta como corrida boa (pode ser aceita sozinha). Antes era recusada. Embarque em zona proibida continua recusado, e o limite de km até o embarque continua valendo. Dá para desligar em Zonas."),
                Item("🏠", "Zona 1 é a principal", "A primeira zona criada é a sua área principal: corrida de uma zona adicional voltando para a Zona 1 não conta mais como \"entre zonas\"."),
                Item("🟣", "Entre zonas: recusar e fechar", "O botão agora diz o que faz: com \"Permitir corridas entre zonas\" desligado, a corrida entre zonas é recusada e fechada sozinha."),
                Item("🃏", "Card de exemplo corrigido", "O exemplo de \"Local incerto\" agora mostra a dúvida no destino (o embarque fora não é mais motivo de dúvida).")
            )
        ),
        Release(
            versionCode = 5, versionName = "1.4", date = "17/11/2026",
            items = listOf(
                Item("💰", "Painel de Ganhos", "Nova tela com bruto, gastos e líquido em 7, 15 e 30 dias, semana de segunda a domingo, balanço do mês, horas trabalhadas, R$/h e R$/km reais, melhores horários e de onde veio o dinheiro (99 e Uber na mesma conta)."),
                Item("🧾", "Gastos e extras", "Lance pneu furado, troca de óleo, lavagem, combustível, gorjetas e bônus. Gastos fixos mensais (aluguel do carro, seguro, celular) entram rateados por dia."),
                Item("✅", "Vale a pena?", "Diga quanto vale sua hora e o balanço do mês mostra se está compensando, com projeção do fim do mês e meta semanal com barra de progresso."),
                Item("🚗", "Corridas registradas sozinhas", "Aceite automático ou no dedo vira corrida no painel; a tela de avaliação conclui e a de cancelamento tira do ganho. Dá para corrigir qualquer corrida."),
                Item("⏯️", "Botão de 3 estados", "ON → PAUSA (só lê, não aceita nem recusa) → OFF (desliga a leitura de tela, para apps de banco)."),
                Item("⏱️", "OFF com contagem regressiva", "No OFF o botão fica na tela contando o tempo (padrão 10 min, ajustável). Toque para religar; segure para esconder. Acabou o tempo, o app fecha para poupar bateria."),
                Item("🧩", "Bloco \"Filtro\" nas Configurações rápidas", "Religue ou desligue o filtro pela cortina de notificações."),
                Item("🗺️", "Mapa de zonas renovado", "Busca de endereço, botão da sua localização, lista de zonas, editor para renomear, trocar permitida/proibida, arrastar e inserir pontos. Apagar tudo agora pede confirmação."),
                Item("🎨", "Cor do card por zona", "Pinte o card de cada zona adicional (roxo por padrão) para saber o destino sem ler. Desligado por padrão."),
                Item("📏", "R$ mínimo por km", "Recusa corridas abaixo do valor por km que você definir. Desligado por padrão."),
                Item("🔁", "\"Continuar aceitando\" da 99", "O app volta a tocar sozinho em \"Continuar aceitando solicitações\"."),
                Item("⛽", "Ícones de combustível e lucro", "⛽ e 💰 voltaram ao card da corrida."),
                Item("🛠️", "Correções", "\"OK, corrida cancelada\" do mapa da 99 não é mais lido como cancelamento; divisa mostra \"por dentro/por fora\" em vez de metros negativos."),
                Item("📋", "Logs muito mais completos", "Guarda dias de histórico, regras ativas, mudanças de regra, ciclo da corrida, ganhos, status a cada 10 min (bateria, leituras) e travamentos.")
            )
        ),
        Release(
            versionCode = 4, versionName = "1.3", date = "",
            items = listOf(
                Item("🧭", "Demarcação de área", "Regras por rua e por número, divisa e local incerto."),
                Item("🎨", "Visual novo", "Painel organizado em abas e card de corrida redesenhado."),
                Item("📱", "Instalação e status", "Guia de instalação e status de cada recurso.")
            )
        ),
        Release(
            versionCode = 3, versionName = "1.2", date = "",
            items = listOf(
                Item("🗺️", "Zonas desenhadas no mapa", "Polígonos e círculos como área permitida ou proibida."),
                Item("🔒", "Modo duplo 99 + Uber", "Ao aceitar numa, a outra fica travada."),
                Item("⛽", "R$/h líquido", "Desconta o combustível do trajeto inteiro."),
                Item("📸", "Print de cada oferta", "Guardado só no app, aberto pelo histórico."),
                Item("❤️", "Passageiros favoritos", "Aviso 30, 20 e 10 minutos antes.")
            )
        )
    )

    /** Releases mais novos que a ultima versao vista (todos se nada foi visto). */
    fun newerThan(lastSeen: Int): List<Release> = releases.filter { it.versionCode > lastSeen }
}
