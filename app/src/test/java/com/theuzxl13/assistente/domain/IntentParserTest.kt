package com.theuzxl13.assistente.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParserTest {

    private fun parse(text: String, expectingChoice: Boolean = false) = IntentParser.parse(text, expectingChoice)

    @Test
    fun `ligacoes com variacoes naturais`() {
        assertEquals(BelaIntent.Call("matheus"), parse("Bela, ligue para o Matheus."))
        assertEquals(BelaIntent.Call("matheus"), parse("liga pro Matheus"))
        assertEquals(BelaIntent.Call("maria"), parse("quero falar com a Maria"))
        assertEquals(BelaIntent.Call("minha filha"), parse("telefona pra minha filha"))
        assertEquals(BelaIntent.Call("joao"), parse("Você pode ligar para o João, por favor?"))
        assertEquals(BelaIntent.Call("tia lurdes"), parse("faz uma ligação pra tia Lurdes"))
        assertEquals(BelaIntent.Call("pedro"), parse("ligar pro Pedro no celular"))
        assertEquals(BelaIntent.CallWithoutName, parse("Bela, ligue"))
    }

    @Test
    fun `emergencias`() {
        assertEquals(BelaIntent.CallEmergency("192", "o SAMU"), parse("ligue para o SAMU"))
        assertEquals(BelaIntent.CallEmergency("190", "a polícia"), parse("chama a polícia"))
        assertEquals(BelaIntent.CallEmergency("193", "os bombeiros"), parse("ligar para os bombeiros"))
    }

    @Test
    fun `controle de chamadas`() {
        assertEquals(BelaIntent.WhoIsCalling, parse("Bela, quem está me ligando?"))
        assertEquals(BelaIntent.WhoIsCalling, parse("quem tá ligando"))
        assertEquals(BelaIntent.AnswerCall, parse("Bela, atenda a ligação."))
        assertEquals(BelaIntent.AnswerCall, parse("atender"))
        assertEquals(BelaIntent.DeclineCall, parse("Bela, recusar"))
        assertEquals(BelaIntent.DeclineCall, parse("não quero atender"))
        assertEquals(BelaIntent.HangUp, parse("Bela, desligue."))
        assertEquals(BelaIntent.HangUp, parse("encerra a ligação"))
    }

    @Test
    fun `controles da conversa`() {
        assertEquals(BelaIntent.Stop, parse("Bela, pare."))
        assertEquals(BelaIntent.Cancel, parse("cancele"))
        assertEquals(BelaIntent.Repeat, parse("Bela, repita."))
        assertEquals(BelaIntent.Repeat, parse("fala de novo"))
        assertEquals(BelaIntent.SpeakSlower, parse("Bela, fale mais devagar."))
        assertEquals(BelaIntent.SpeakFaster, parse("fala mais rápido"))
        assertEquals(BelaIntent.NotUnderstood, parse("Bela, não entendi, me ajude."))
        assertEquals(BelaIntent.Help, parse("Bela, me ajude."))
        assertEquals(BelaIntent.Back, parse("Bela, volte."))
        assertEquals(BelaIntent.Capabilities, parse("Bela, o que posso fazer?"))
        assertEquals(BelaIntent.Capabilities, parse("o que você sabe fazer"))
        assertEquals(BelaIntent.Goodbye, parse("obrigada"))
    }

    @Test
    fun `respostas curtas e escolhas`() {
        assertEquals(BelaIntent.Yes, parse("pode"))
        assertEquals(BelaIntent.Yes, parse("sim, por favor"))
        assertEquals(BelaIntent.No, parse("não"))
        assertEquals(BelaIntent.Choice(0), parse("o primeiro", expectingChoice = true))
        assertEquals(BelaIntent.Choice(1), parse("a segunda opção", expectingChoice = true))
        assertEquals(BelaIntent.Choice(1), parse("dois", expectingChoice = true))
        assertEquals(BelaIntent.Choice(-1), parse("o último", expectingChoice = true))
        assertEquals(BelaIntent.Another, parse("quero ouvir outro"))
    }

    @Test
    fun `musica e youtube`() {
        assertEquals(BelaIntent.PlayMedia("gabriela rocha"), parse("Bela, quero ouvir Gabriela Rocha."))
        assertEquals(BelaIntent.PlayMedia("hino da harpa crista"), parse("Procure um hino da Harpa Cristã"))
        assertEquals(BelaIntent.PlayMedia("louvor sobre fe"), parse("Toque um louvor sobre fé"))
        assertEquals(BelaIntent.PlayMedia("louvor"), parse("Bela, quero ouvir um louvor."))
        assertEquals(BelaIntent.PlayMedia(""), parse("coloca uma música"))
        assertEquals(BelaIntent.PauseMedia, parse("Pause."))
        assertEquals(BelaIntent.ResumeMedia, parse("Continue."))
        assertEquals(BelaIntent.StopMedia, parse("Pare a música."))
        assertEquals(BelaIntent.StopMedia, parse("desliga a música"))
        assertEquals(BelaIntent.NextMedia, parse("próxima música"))
    }

    @Test
    fun `dia a dia`() {
        assertEquals(BelaIntent.Time, parse("Bela, que horas são?"))
        assertEquals(BelaIntent.Time, parse("que horas"))
        assertEquals(BelaIntent.Date, parse("que dia é hoje"))
        assertEquals(BelaIntent.VolumeUp, parse("Bela, me ajude a aumentar o volume."))
        assertEquals(BelaIntent.VolumeDown, parse("abaixa o volume"))
        assertEquals(BelaIntent.OpenApp("whatsapp"), parse("Bela, abra o WhatsApp."))
        assertEquals(BelaIntent.OpenApp("youtube"), parse("abre o YouTube"))
        assertEquals(BelaIntent.ReadShared, parse("Bela, leia essa notícia."))
    }

    @Test
    fun `perguntas viram pesquisa sem cair em comandos por engano`() {
        assertEquals(BelaIntent.Ask("pesquise uma receita de bolo de cenoura."), parse("Bela, pesquise uma receita de bolo de cenoura."))
        assertTrue(parse("Leia as notícias de hoje") is BelaIntent.Ask)
        assertTrue(parse("a que horas abre a farmácia") is BelaIntent.Ask)
        assertTrue(parse("qual é o prédio mais alto do mundo") is BelaIntent.Ask)
        assertTrue(parse("como se chama a capital da França") is BelaIntent.Ask)
        assertTrue(parse("Como faço para usar o WhatsApp?") is BelaIntent.Ask)
        assertTrue(parse("quem é Gabriela Rocha") is BelaIntent.Ask)
    }

    @Test
    fun `pedidos de pesquisa chegam na pesquisa`() {
        assertTrue(parse("Bela, pesquise quem foi Moisés.") is BelaIntent.Ask)
        assertTrue(parse("Pesquise notícias de hoje.") is BelaIntent.Ask)
        assertTrue(parse("Pesquise uma receita de bolo.") is BelaIntent.Ask)
        assertTrue(parse("Pesquise no Google como usar o WhatsApp.") is BelaIntent.Ask)
    }

    @Test
    fun `comandos durante a leitura de resultados`() {
        assertEquals(BelaIntent.Another, parse("próximo resultado"))
        assertEquals(BelaIntent.Another, parse("a próxima notícia"))
        assertEquals(BelaIntent.Details, parse("mais detalhes"))
        assertEquals(BelaIntent.Details, parse("leia tudo"))
        assertEquals(BelaIntent.OpenInBrowser, parse("abra no navegador"))
        assertEquals(BelaIntent.OpenInBrowser, parse("abre a página"))
        assertEquals(BelaIntent.Retry, parse("tente de novo"))
        assertEquals(BelaIntent.ResumeMedia, parse("continua"))
        assertEquals(BelaIntent.OpenApp("google"), parse("abra o Google"))
    }

    @Test
    fun `numeros so viram escolha quando ha lista`() {
        assertTrue(parse("um") !is BelaIntent.Choice)
        assertEquals(BelaIntent.Empty, parse("Bela"))
    }
}
