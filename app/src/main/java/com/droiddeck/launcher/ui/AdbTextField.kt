package com.droiddeck.launcher.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun AdbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    compact: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    clearFocus: Boolean = false,
    onNext: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    focusOnShow: Boolean = false,
) {
    val context = LocalContext.current
    val focusAsked = remember { booleanArrayOf(false) }
    val palette = LocalPalette.current
    val onValueChangeLatest = rememberUpdatedState(onValueChange)
    val onNextLatest = rememberUpdatedState(onNext)
    val onDoneLatest = rememberUpdatedState(onDone)
    val onFocusChangeLatest = rememberUpdatedState(onFocusChange)
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val inputHeight = if (compact) 44.dp else 52.dp
    val colors = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(inputHeight)
                .background(colors.surfaceVariant, shape)
                .glideBorder(focused, shape, palette.signal, palette.line2)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    EditText(viewContext).apply {
                        isSingleLine = true
                        setHorizontallyScrolling(true)
                        background = null
                        setPadding((2 * context.resources.displayMetrics.density).roundToInt(), 0, 0, 0)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f)
                        setTextColor(colors.onBackground.toArgb())
                        setHintTextColor(colors.onSurfaceVariant.toArgb())
                        contentDescription = label
                        inputType = if (keyboardType == KeyboardType.Number) {
                            InputType.TYPE_CLASS_NUMBER
                        } else {
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }
                        imeOptions = imeActionFlag(imeAction) or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        hint = placeholder
                        setOnEditorActionListener { _, actionId, event ->
                            val isEnter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                            when {
                                imeAction == ImeAction.Next && (actionId == EditorInfo.IME_ACTION_NEXT || isEnter) -> {
                                    onNextLatest.value?.invoke()
                                    true
                                }
                                imeAction == ImeAction.Done && (actionId == EditorInfo.IME_ACTION_DONE || isEnter) -> {
                                    onDoneLatest.value?.invoke()
                                    true
                                }
                                else -> false
                            }
                        }
                        setOnFocusChangeListener { _, hasFocus ->
                            focused = hasFocus
                            onFocusChangeLatest.value(hasFocus)
                        }
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: Editable?) {
                                onValueChangeLatest.value(s?.toString().orEmpty())
                            }
                        })
                    }
                },
                update = { editText ->
                    if (focusOnShow && !focusAsked[0]) {
                        focusAsked[0] = true
                        editText.post {
                            editText.requestFocus()
                            (editText.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                                ?.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                    if (clearFocus && editText.hasFocus()) {
                        editText.clearFocus()
                        (editText.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                            ?.hideSoftInputFromWindow(editText.windowToken, 0)
                    }
                    editText.setTextColor(colors.onBackground.toArgb())
                    editText.setHintTextColor(colors.onSurfaceVariant.toArgb())
                    editText.hint = placeholder
                    editText.contentDescription = label
                    if (editText.text.toString() != value) {
                        val cursor = if (editText.hasFocus()) editText.selectionStart.coerceAtLeast(0) else value.length
                        editText.setText(value)
                        editText.setSelection(min(cursor, value.length))
                    }
                },
            )
        }
    }
}


private fun imeActionFlag(action: ImeAction): Int = when (action) {
    ImeAction.Next -> EditorInfo.IME_ACTION_NEXT
    ImeAction.Done -> EditorInfo.IME_ACTION_DONE
    ImeAction.Go -> EditorInfo.IME_ACTION_GO
    ImeAction.Search -> EditorInfo.IME_ACTION_SEARCH
    else -> EditorInfo.IME_ACTION_UNSPECIFIED
}
