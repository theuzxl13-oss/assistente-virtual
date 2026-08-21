package com.theuzxl13.assistente.tools

import android.content.Context
import android.provider.ContactsContract

data class ContactMatch(val name: String, val phoneNumber: String)

object ContactsHelper {

    /** Procura contatos cujo nome contenha [query] (sem diferenciar maiúsculas/minúsculas). */
    fun findByName(context: Context, query: String): List<ContactMatch> {
        val results = mutableListOf<ContactMatch>()
        val resolver = context.contentResolver

        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
            arrayOf("%${escapeLikePattern(query)}%"),
            null
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val seen = mutableSetOf<String>()
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                val number = it.getString(numberIndex) ?: continue
                if (seen.add("$name|$number")) {
                    results.add(ContactMatch(name, number))
                }
            }
        }

        return results
    }

    /** Escapa os curingas do LIKE (% e _) para que apareçam como texto literal na busca. */
    private fun escapeLikePattern(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
