package com.vayunmathur.email.intents

import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.plainTextBody
import com.vayunmathur.library.intents.email.EmailData

fun EmailMessage.toEmailData() = EmailData(
    subject = subject,
    from = from,
    to = to,
    date = date,
    body = plainTextBody()?.take(2000),
    isRead = isRead,
)
