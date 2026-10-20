package com.theuzxl13.assistente.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ConversationEngineTest {

    private class FakeSpeech : SpeechOutput {
        val spoken = mutableListOf<String>()
        override var rate = 1f
        override suspend fun speak(text: String, rateMultiplier: Float) { spoken += text }
        override fun stop() {}
    }

    private class FakeInput(vararg utterances: String) : SpeechInput {
        private val queue = ArrayDeque(utterances.map { Heard.Text(listOf(it)) as Heard })
        fun add(heard: Heard) = queue.addLast(heard)
        override suspend fun listen(): Heard = queue.removeFirstOrNull() ?: Heard.Silence
        override fun cancel() {}
    }

    private object NoSounds : SoundFeedback {
        override fun listening() {}
        override fun done() {}
        override fun error() {}
    }

    private class FakePermissions(var grantOnRequest: Boolean = true) : PermissionPort {
        val granted = mutableSetOf(*PermissionGroup.values())
        val requested = mutableListOf<PermissionGroup>()
        override fun has(group: PermissionGroup) = group in granted
        override suspend fun request(group: PermissionGroup): Boolean {
            requested += group
            if (grantOnRequest) granted += group
            return grantOnRequest
        }
    }

    private class FakeContacts(private val all: List<Contact>) : ContactsPort {
        override suspend fun findByName(query: String) =
            all.filter { TextNormalizer.normalize(it.name).contains(TextNormalizer.normalize(query)) }
        override suspend fun nameForNumber(number: String) = all.firstOrNull { it.number == number }?.name
    }

    private class FakePhone : PhonePort {
        var state = CallState.IDLE
        var ringing: String? = null
        val placed = mutableListOf<String>()
        var placeResult = PlaceCallResult.STARTED
        var answered = false
        var ended = false
        override fun placeCall(number: String): PlaceCallResult {
            placed += number
            if (placeResult == PlaceCallResult.STARTED) state = CallState.OFFHOOK
            return placeResult
        }
        override fun callState() = state
        override suspend fun awaitState(target: CallState, timeoutMs: Long) = state == target
        override fun answer(): Boolean { answered = true; state = CallState.OFFHOOK; return true }
        override fun endCall(): Boolean { ended = true; state = CallState.IDLE; return true }
        override fun ringingNumber() = ringing
    }

    private class FakeMedia(var result: MediaSearchResult = MediaSearchResult.NotConfigured) : MediaPort {
        val played = mutableListOf<MediaItem>()
        var openedSearch: String? = null
        var paused = false
        override suspend fun search(query: String) = result
        override fun play(item: MediaItem): Boolean { played += item; return true }
        override fun openSearch(query: String): Boolean { openedSearch = query; return true }
        override fun pause() { paused = true }
        override fun resume() {}
        override fun stop() {}
        override fun next() {}
        override fun isPlaying() = played.isNotEmpty()
    }

    private class FakeSearch(var result: SearchResult = SearchResult.NotConfigured) : SearchPort {
        val questions = mutableListOf<String>()
        override suspend fun ask(question: String, history: List<Turn>): SearchResult {
            questions += question
            return result
        }
    }

    private class FakeApps(private val installed: List<InstalledApp>) : AppsPort {
        val launched = mutableListOf<InstalledApp>()
        override suspend fun find(name: String) =
            installed.filter { TextNormalizer.normalize(it.label).contains(TextNormalizer.normalize(name)) }
        override fun launch(app: InstalledApp): Boolean { launched += app; return true }
    }

    private class FakeDevice(var online: Boolean = true) : DevicePort {
        var volume = 50
        override fun isOnline() = online
        override fun raiseVolume(): Int { volume = minOf(100, volume + 10); return volume }
        override fun lowerVolume(): Int { volume = maxOf(0, volume - 10); return volume }
        override fun isTalkBackOn() = true
        override fun isDefaultAssistant() = true
        override fun openAssistantSettings() = true
    }

    private class FakePrefs : PreferencesPort {
        override var speechRate = 1f
        override var onboardingDone = false
    }

    private val speech = FakeSpeech()
    private val permissions = FakePermissions()
    private val phone = FakePhone()
    private val media = FakeMedia()
    private val search = FakeSearch()
    private val device = FakeDevice()
    private val states = mutableListOf<ConversationState>()
    private val contacts = FakeContacts(
        listOf(
            Contact("Matheus Marcelino", "+55 11 98888-1234"),
            Contact("Matheus Silva", "+55 11 97777-5678"),
            Contact("Ana Paula", "+55 11 96666-0001")
        )
    )
    private val apps = FakeApps(
        listOf(InstalledApp("WhatsApp", "com.whatsapp"), InstalledApp("YouTube", "com.google.android.youtube"))
    )

    private fun engine(input: FakeInput) = ConversationEngine(
        speech, input, NoSounds, permissions, contacts, phone, media, search, apps, device, FakePrefs(),
        now = { ZonedDateTime.of(2026, 10, 20, 14, 5, 0, 0, ZoneId.of("America/Sao_Paulo")) },
        wait = {}
    ).also { it.onStateChanged = { s -> states += s } }

    @Test
    fun `contato unico pede confirmacao antes de ligar`() = runTest {
        engine(FakeInput("ligue para a Ana", "sim")).runSession()
        assertEquals("Encontrei Ana Paula. Quer que eu ligue?", speech.spoken[0])
        assertEquals(listOf("+55 11 96666-0001"), phone.placed)
        assertTrue(speech.spoken.contains("Vou ligar para Ana Paula."))
    }

    @Test
    fun `nao liga se ela disser nao`() = runTest {
        engine(FakeInput("ligue para a Ana", "não")).runSession()
        assertTrue(phone.placed.isEmpty())
        assertEquals("Tudo bem, não vou ligar.", speech.spoken.last())
    }

    @Test
    fun `nomes duplicados sao lidos e escolhidos por ordinal`() = runTest {
        engine(FakeInput("ligue para o Matheus", "o segundo")).runSession()
        assertEquals(
            "Encontrei 2 contatos. O primeiro é Matheus Marcelino e o segundo é Matheus Silva. Qual você quer chamar?",
            speech.spoken[0]
        )
        assertEquals(listOf("+55 11 97777-5678"), phone.placed)
    }

    @Test
    fun `nomes duplicados podem ser escolhidos pelo nome completo`() = runTest {
        engine(FakeInput("liga pro Matheus", "Matheus Marcelino")).runSession()
        assertEquals(listOf("+55 11 98888-1234"), phone.placed)
    }

    @Test
    fun `contato inexistente pergunta outro nome`() = runTest {
        engine(FakeInput("ligue para o Zé", "Ana")).runSession()
        assertTrue(speech.spoken[0].startsWith("Não encontrei ninguém chamado ze"))
        assertEquals("Encontrei Ana Paula. Quer que eu ligue?", speech.spoken[1])
        assertTrue(phone.placed.isEmpty())
    }

    @Test
    fun `permissao de contatos negada nao tenta ligar`() = runTest {
        permissions.granted -= PermissionGroup.CONTACTS
        permissions.grantOnRequest = false
        engine(FakeInput("ligue para a Ana")).runSession()
        assertEquals(listOf(PermissionGroup.CONTACTS), permissions.requested)
        assertTrue(phone.placed.isEmpty())
        assertTrue(speech.spoken.last().startsWith("Sem essa permissão"))
    }

    @Test
    fun `nao confirma ligacao que nao comecou`() = runTest {
        phone.placeResult = PlaceCallResult.STARTED
        val stuckPhone = object : PhonePort by phone {
            override fun placeCall(number: String) = PlaceCallResult.STARTED
        }
        ConversationEngine(
            speech, FakeInput("ligue para a Ana", "sim"), NoSounds, permissions, contacts, stuckPhone,
            media, search, apps, device, FakePrefs(), wait = {}
        ).runSession()
        assertTrue(speech.spoken.last().startsWith("Não consegui confirmar que a ligação começou"))
    }

    @Test
    fun `quem esta ligando identifica o contato e atende`() = runTest {
        phone.state = CallState.RINGING
        phone.ringing = "+55 11 98888-1234"
        engine(FakeInput("quem está me ligando", "atender")).runSession()
        assertTrue(speech.spoken[0].startsWith("Quem está ligando é Matheus Marcelino."))
        assertTrue(phone.answered)
    }

    @Test
    fun `numero desconhecido nao inventa nome`() = runTest {
        phone.state = CallState.RINGING
        phone.ringing = "+55 21 3333-4321"
        engine(FakeInput("quem é que está ligando", "não")).runSession()
        assertTrue(speech.spoken[0].startsWith("É um número que não está na sua agenda, terminado em 4 3 2 1."))
        assertTrue(phone.ended)
        assertEquals("Recusei a ligação.", speech.spoken.last())
    }

    @Test
    fun `atender sem ligacao tocando avisa`() = runTest {
        engine(FakeInput("atender")).runSession()
        assertEquals("Não tem nenhuma ligação tocando agora.", speech.spoken.last())
        assertFalse(phone.answered)
    }

    @Test
    fun `desligar encerra ligacao em andamento`() = runTest {
        phone.state = CallState.OFFHOOK
        engine(FakeInput("desligue")).runSession()
        assertTrue(phone.ended)
        assertEquals("Ligação encerrada.", speech.spoken.last())
    }

    @Test
    fun `repetir fala de novo a ultima resposta`() = runTest {
        engine(FakeInput("que horas são", "repita")).runSession()
        assertEquals("São 14 horas e 5 minutos.", speech.spoken[0])
        assertEquals(speech.spoken[0], speech.spoken[1])
    }

    @Test
    fun `velocidade da fala muda`() = runTest {
        engine(FakeInput("fale mais devagar")).runSession()
        assertEquals(1f - ConversationEngine.RATE_STEP, speech.rate, 0.001f)
    }

    @Test
    fun `musica le titulos e toca o escolhido`() = runTest {
        media.result = MediaSearchResult.Found(
            listOf(
                MediaItem("Me Atraiu", "Gabriela Rocha", "a"),
                MediaItem("Lugar Secreto", "Gabriela Rocha", "b"),
                MediaItem("Atos 2", "Gabriela Rocha", "c")
            )
        )
        engine(FakeInput("quero ouvir Gabriela Rocha", "o segundo")).runSession()
        assertTrue(speech.spoken[0].contains("primeiro: Me Atraiu, do canal Gabriela Rocha"))
        assertEquals("b", media.played.single().videoId)
    }

    @Test
    fun `sem chave do youtube abre a busca e explica`() = runTest {
        engine(FakeInput("toque um louvor")).runSession()
        assertEquals("louvor", media.openedSearch)
        assertTrue(speech.spoken.last().contains("não consigo ler os resultados"))
    }

    @Test
    fun `pergunta cita a fonte e oferece detalhes`() = runTest {
        search.result = SearchResult.Success(
            Answer("Misture cenoura, ovos e óleo no liquidificador.", "Depois asse por 40 minutos.", listOf(Source("tudogostoso.com.br", "https://x")))
        )
        engine(FakeInput("pesquise uma receita de bolo de cenoura", "sim")).runSession()
        assertTrue(speech.spoken.any { it.endsWith("A informação é de tudogostoso.com.br.") })
        assertTrue(speech.spoken.contains("Depois asse por 40 minutos."))
    }

    @Test
    fun `sem internet nao finge que pesquisou`() = runTest {
        device.online = false
        engine(FakeInput("qual a capital da França")).runSession()
        assertTrue(search.questions.isEmpty())
        assertTrue(speech.spoken.last().contains("sem conexão"))
    }

    @Test
    fun `abre aplicativo pelo nome`() = runTest {
        engine(FakeInput("abra o WhatsApp")).runSession()
        assertEquals("com.whatsapp", apps.launched.single().packageName)
    }

    @Test
    fun `silencio durante pergunta repete a pergunta uma vez e encerra`() = runTest {
        engine(FakeInput("ligue para a Ana")).runSession()
        assertEquals(speech.spoken[0], speech.spoken[1])
        assertTrue(phone.placed.isEmpty())
        assertEquals(ConversationState.INATIVA, states.last())
    }

    @Test
    fun `falha de rede no reconhecimento vira erro falado`() = runTest {
        val input = FakeInput()
        input.add(Heard.Failure(ListenFailure.NO_NETWORK))
        engine(input).runSession()
        assertTrue(speech.spoken.last().contains("precisa de internet"))
        assertEquals(ConversationState.ERRO, states.last())
    }

    @Test
    fun `texto compartilhado e lido em partes`() = runTest {
        val longText = "Primeira frase. ".repeat(60)
        val e = engine(FakeInput("não"))
        e.sharedText = longText
        e.runSession(start = BelaIntent.ReadShared)
        assertEquals("Quer que eu continue lendo?", speech.spoken[1])
        assertEquals("Tudo bem, parei a leitura.", speech.spoken.last())
    }

    @Test
    fun `split de texto respeita fim de frase`() {
        val (first, rest) = ConversationEngine.splitChunk("Uma frase curta. Outra frase bem maior que o limite.", 25)
        assertEquals("Uma frase curta.", first)
        assertEquals("Outra frase bem maior que o limite.", rest)
    }
}
