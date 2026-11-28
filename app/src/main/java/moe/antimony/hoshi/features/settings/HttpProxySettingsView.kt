package moe.antimony.hoshi.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.network.HttpProxySettings
import moe.antimony.hoshi.network.parseHttpProxy
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors
import moe.antimony.hoshi.ui.hoshiSingleLineTextFieldLineLimits
import moe.antimony.hoshi.ui.rememberSyncedTextFieldState
import moe.antimony.hoshi.ui.withErrorDetails

@Composable
internal fun HttpProxySettingsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiUiDependencies.current
    val repository = appContainer.httpProxySettingsRepository
    val loader = repository.settings.rememberSettingsLoader()
    val scope = rememberCoroutineScope()
    var operationError by remember { mutableStateOf<UiText?>(null) }
    var testSucceeded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    SettingsDetailScaffold(
        state = loader.state,
        title = stringResource(R.string.http_proxy_title),
        onClose = onClose,
        onRetry = loader.retry,
        modifier = modifier,
    ) { innerPadding, settings ->
        var addressDraft by remember(settings.address) { mutableStateOf(settings.address) }
        var testUrlDraft by remember(settings.testUrl) { mutableStateOf(settings.testUrl) }
        val addressState = rememberSyncedTextFieldState(addressDraft, { addressDraft = it })
        val testUrlState = rememberSyncedTextFieldState(testUrlDraft, { testUrlDraft = it })

        fun saveAndTest() {
            val proxy = parseHttpProxy(addressDraft)
            if (proxy == null) {
                operationError = UiText.Resource(R.string.http_proxy_invalid_address)
                testSucceeded = false
                return
            }
            val testUrl = testUrlDraft.trim().ifBlank { HttpProxySettings.DefaultHttpProxyTestUrl }
            operationError = null
            testSucceeded = false
            busy = true
            scope.launch {
                try {
                    repository.update {
                        it.copy(
                            enabled = false,
                            configured = false,
                            address = proxy.address,
                            testUrl = testUrl,
                        )
                    }
                    repository.test(proxy, testUrl)
                    repository.update { it.copy(enabled = true, configured = true) }
                    testSucceeded = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    operationError = UiText.Resource(R.string.http_proxy_test_failed).withErrorDetails(error)
                } finally {
                    busy = false
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                GroupCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.http_proxy_enabled)) },
                        supportingContent = { Text(stringResource(R.string.http_proxy_enabled_description)) },
                        trailingContent = {
                            Switch(
                                checked = settings.enabled,
                                enabled = !busy,
                                onCheckedChange = { enabled ->
                                    if (enabled && (!settings.configured || parseHttpProxy(settings.address) == null)) {
                                        operationError = UiText.Resource(R.string.http_proxy_test_first)
                                    } else {
                                        operationError = null
                                        testSucceeded = false
                                        scope.launch {
                                            try {
                                                repository.update { it.copy(enabled = enabled) }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                operationError = UiText.Resource(R.string.http_proxy_save_failed).withErrorDetails(error)
                                            }
                                        }
                                    }
                                },
                            )
                        },
                    )
                    GroupDivider()
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            state = addressState,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.http_proxy_address)) },
                            placeholder = { Text(stringResource(R.string.http_proxy_address_hint)) },
                            lineLimits = hoshiSingleLineTextFieldLineLimits(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = hoshiOutlinedTextFieldColors(),
                        )
                        OutlinedTextField(
                            state = testUrlState,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.http_proxy_test_url)) },
                            placeholder = { Text(stringResource(R.string.http_proxy_test_url_hint)) },
                            lineLimits = hoshiSingleLineTextFieldLineLimits(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = hoshiOutlinedTextFieldColors(),
                        )
                        Button(
                            onClick = ::saveAndTest,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.http_proxy_save_and_test))
                        }
                    }
                }
            }
            operationError?.let { error ->
                item { ErrorDetailsText(error.asString()) }
            }
            if (testSucceeded) {
                item {
                    Text(
                        text = stringResource(R.string.http_proxy_test_success),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
