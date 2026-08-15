package com.vayunmathur.email.intents

import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.library.intents.email.EmailData
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.serializer

class GetRecentIntent : AssistantIntent<Unit, List<EmailData>>(serializer<Unit>(), serializer<List<EmailData>>()) {

    override suspend fun performCalculation(input: Unit): List<EmailData> {
        val dao = EmailRepository.get(this).getDatabase().emailDao()
        return dao.getRecentInboxMessages().map { it.toEmailData() }
    }
}
