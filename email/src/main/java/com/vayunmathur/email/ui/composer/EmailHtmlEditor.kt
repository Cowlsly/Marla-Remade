package com.vayunmathur.email.composer

import android.text.Editable
import android.view.Gravity
import android.widget.EditText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import androidx.core.text.parseAsHtml
import com.vayunmathur.library.ui.RichEditText

/**
 * Email-specific HtmlEditor that uses EmailHtmlTagHandler to preserve rich formatting
 * (headings, blockquote, inline code, colors, font size/family, alignment, hr)
 * when re-loading raw HTML (drafts, setHtml, signature injection etc.).
 *
 * Identical to library HtmlEditor otherwise.
 */
@Composable
fun EmailHtmlEditor(
    controller: EmailHtmlEditorController,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    val textColor = LocalContentColor.current.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val appliedVersion = remember { mutableIntStateOf(0) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            RichEditText(ctx).apply {
                background = null
                setTextColor(textColor)
                setHintTextColor(hintColor)
                hint = placeholder
                gravity = Gravity.TOP or Gravity.START
                setHorizontallyScrolling(false)
                isSingleLine = false
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                this.richController = controller
                controller.editText = this
                onSelectionChange = { s, e ->
                    controller.updateSelection(s, e)
                }
                setOnFocusChangeListener { _, hasFocus -> controller.updateFocus(hasFocus) }
                controller.updating = true
                setText(
                    controller.html.parseAsHtml(
                        HtmlCompat.FROM_HTML_MODE_COMPACT,
                        null,
                        controller.tagHandler,
                    )
                )
                setSelection(text?.length ?: 0)
                controller.updating = false
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (!controller.updating && s != null) {
                            controller.commitHtml(controller.htmlSerializer(s))
                        }
                    }
                })
            }
        },
        update = { et ->
            val v = controller.currentSetVersion
            if (v != appliedVersion.intValue) {
                val prevSel = et.selectionStart.coerceAtLeast(0)
                controller.updating = true
                et.setText(
                    controller.html.parseAsHtml(
                        HtmlCompat.FROM_HTML_MODE_COMPACT,
                        null,
                        controller.tagHandler,
                    )
                )
                val newLen = et.text?.length ?: 0
                et.setSelection(prevSel.coerceIn(0, newLen))
                controller.updating = false
                appliedVersion.intValue = v
            }
        },
    )
}
