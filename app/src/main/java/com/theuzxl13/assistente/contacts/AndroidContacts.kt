package com.theuzxl13.assistente.contacts

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.theuzxl13.assistente.domain.Contact
import com.theuzxl13.assistente.domain.ContactsPort
import com.theuzxl13.assistente.domain.TextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Busca na agenda do aparelho (só leitura). A comparação ignora acentos e maiúsculas, porque o
 * reconhecedor de voz nem sempre devolve o nome escrito igual ao da agenda ("Joao" x "João").
 */
class AndroidContacts(private val context: Context) : ContactsPort {

    override suspend fun findByName(query: String): List<Contact> = withContext(Dispatchers.IO) {
        val target = TextNormalizer.normalize(query)
        if (target.isBlank()) return@withContext emptyList()
        val words = target.split(" ")

        val all = mutableListOf<Pair<Contact, String>>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
            null,
            null,
            "${Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(Phone.NUMBER)
            val typeIndex = cursor.getColumnIndexOrThrow(Phone.TYPE)
            val labelIndex = cursor.getColumnIndexOrThrow(Phone.LABEL)
            val seen = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val number = cursor.getString(numberIndex) ?: continue
                val digits = number.filter { it.isDigit() }.takeLast(9)
                if (!seen.add("${name.lowercase()}|$digits")) continue // mesmo número salvo duas vezes
                val label = Phone.getTypeLabel(context.resources, cursor.getInt(typeIndex), cursor.getString(labelIndex))
                    .toString().lowercase()
                all += Contact(name, number, label) to TextNormalizer.normalize(name)
            }
        }

        // Prioridade: nome igual > começa com o que foi dito > contém todas as palavras ditas.
        val exact = all.filter { it.second == target }
        val prefix = all.filter { it.second.startsWith(target) }
        val allWords = all.filter { (_, name) -> words.all { word -> name.split(" ").any { it.startsWith(word) } } }
        (exact.ifEmpty { prefix.ifEmpty { allWords } }).map { it.first }
    }

    override suspend fun nameForNumber(number: String): String? = withContext(Dispatchers.IO) {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }
}
