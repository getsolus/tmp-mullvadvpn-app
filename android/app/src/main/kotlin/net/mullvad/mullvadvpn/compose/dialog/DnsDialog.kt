package net.mullvad.mullvadvpn.compose.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.result.ResultBackNavigator
import com.ramcosta.composedestinations.spec.DestinationStyle
import net.mullvad.mullvadvpn.R
import net.mullvad.mullvadvpn.compose.button.NegativeButton
import net.mullvad.mullvadvpn.compose.button.PrimaryButton
import net.mullvad.mullvadvpn.compose.communication.DnsDialogResult
import net.mullvad.mullvadvpn.compose.textfield.DnsTextField
import net.mullvad.mullvadvpn.compose.util.LaunchedEffectCollect
import net.mullvad.mullvadvpn.lib.theme.AppTheme
import net.mullvad.mullvadvpn.lib.theme.Dimens
import net.mullvad.mullvadvpn.viewmodel.DnsDialogSideEffect
import net.mullvad.mullvadvpn.viewmodel.DnsDialogViewModel
import net.mullvad.mullvadvpn.viewmodel.DnsDialogViewState
import net.mullvad.mullvadvpn.viewmodel.ValidationError
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Preview
@Composable
private fun PreviewDnsDialogNew() {
    AppTheme { DnsDialog(DnsDialogViewState("1.1.1.1", null, false, false, null), {}, {}, {}, {}) }
}

@Preview
@Composable
private fun PreviewDnsDialogEdit() {
    AppTheme { DnsDialog(DnsDialogViewState("1.1.1.1", null, false, false, 0), {}, {}, {}, {}) }
}

@Preview
@Composable
private fun PreviewDnsDialogEditAllowLanDisabled() {
    AppTheme { DnsDialog(DnsDialogViewState("192.168.1.1", null, true, false, 0), {}, {}, {}, {}) }
}

@Destination(style = DestinationStyle.Dialog::class)
@Composable
fun DnsDialog(
    resultNavigator: ResultBackNavigator<DnsDialogResult>,
    index: Int?,
    initialValue: String?,
) {
    val viewModel =
        koinViewModel<DnsDialogViewModel>(parameters = { parametersOf(initialValue, index) })

    LaunchedEffectCollect(viewModel.uiSideEffect) {
        when (it) {
            DnsDialogSideEffect.Complete ->
                resultNavigator.navigateBack(result = DnsDialogResult.Success)
            DnsDialogSideEffect.Error ->
                resultNavigator.navigateBack(result = DnsDialogResult.Error)
        }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    DnsDialog(
        state,
        viewModel::onDnsInputChange,
        onSaveDnsClick = viewModel::onSaveDnsClick,
        onRemoveDnsClick = viewModel::onRemoveDnsClick,
        onDismiss = { resultNavigator.navigateBack(result = DnsDialogResult.Cancel) }
    )
}

@Composable
fun DnsDialog(
    state: DnsDialogViewState,
    onDnsInputChange: (String) -> Unit,
    onSaveDnsClick: () -> Unit,
    onRemoveDnsClick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        title = {
            Text(
                text =
                    if (state.isNewEntry) {
                        stringResource(R.string.add_dns_server_dialog_title)
                    } else {
                        stringResource(R.string.update_dns_server_dialog_title)
                    },
                color = Color.White,
            )
        },
        text = {
            Column {
                DnsTextField(
                    value = state.input,
                    isValidValue = state.isValid(),
                    onValueChanged = { newDnsValue -> onDnsInputChange(newDnsValue) },
                    onSubmit = onSaveDnsClick,
                    isEnabled = true,
                    placeholderText = stringResource(R.string.custom_dns_hint),
                    modifier = Modifier.fillMaxWidth()
                )

                val errorMessage =
                    when {
                        state.validationError is ValidationError.DuplicateAddress -> {
                            stringResource(R.string.duplicate_address_warning)
                        }
                        state.isLocal && !state.isAllowLanEnabled -> {
                            stringResource(id = R.string.confirm_local_dns)
                        }
                        else -> {
                            null
                        }
                    }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Dimens.smallPadding)
                    )
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.buttonSpacing)) {
                PrimaryButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSaveDnsClick,
                    isEnabled = state.isValid(),
                    text = stringResource(id = R.string.submit_button),
                )

                if (state.index != null) {
                    NegativeButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onRemoveDnsClick(state.index) },
                        text = stringResource(id = R.string.remove_button)
                    )
                }

                PrimaryButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismiss,
                    text = stringResource(id = R.string.cancel)
                )
            }
        },
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
    )
}
