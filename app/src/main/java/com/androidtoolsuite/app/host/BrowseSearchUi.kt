package com.androidtoolsuite.app.host

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

@Composable
internal fun BrowseSearchToggle(active: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    IconButton(onClick = {if(active){focus.clearFocus();keyboard?.hide()};onClick()}, enabled = enabled) {
        Icon(if (active) Icons.Rounded.Close else Icons.Rounded.Search, if (active) "关闭搜索" else "搜索")
    }
}

@Composable
internal fun BrowseSearchField(query: String, onQuery: (String) -> Unit, label: String, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
        label = { Text(label) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
        keyboardActions=KeyboardActions(onSearch={focusManager.clearFocus();keyboard?.hide()}),
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = {onQuery("");focus.requestFocus()}) { Icon(Icons.Rounded.Close, "清除搜索") } })
    LaunchedEffect(Unit) { focus.requestFocus() }
}
